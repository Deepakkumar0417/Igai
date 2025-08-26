package com.example.azuremanagement.service;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.google.gson.*;



@Service
public class ActivityLogService {
    private static final Logger logger = LoggerFactory.getLogger(ActivityLogService.class);
    private static final String DELTA_FILE_ACTIVITY_TS = "lastActivityLogTimestamp.txt";
    private static final String ACTIVITY_LOGS_DIR = "activity_logs"; // Directory to save all weekly logs

    private static final int MAX_DAYS_PER_REQUEST = 7;
    private static final int TOTAL_MONTHS_TO_FETCH = 3;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ClientSecretCredential credential;
    private final String subscriptionId;
    private final Neo4jService neo4jService;

    private final Map<String, JsonArray> categorizedLogs = new HashMap<>();

    public ActivityLogService(
            @Value("${azure.ad.client-id}")     String clientId,
            @Value("${azure.ad.client-secret}") String clientSecret,
            @Value("${azure.ad.tenant-id}")     String tenantId,
            @Value("${azure.subscription-id}")  String subscriptionId,
            Neo4jService neo4jService
    ) {
        this.subscriptionId = subscriptionId;
        this.credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();
        this.neo4jService = neo4jService;

        // Create logs directory if not present
        File logDir = new File(ACTIVITY_LOGS_DIR);
        if (!logDir.exists()) logDir.mkdirs();
    }

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void downloadAllActivityLogs() {
        try {
            AccessToken token = credential
                    .getToken(new TokenRequestContext()
                            .addScopes("https://management.azure.com/.default"))
                    .block();

            if (token != null) {
                ingestAllActivityLogs(token.getToken());
            } else {
                logger.error("❌ Failed to obtain Azure access token");
            }
        } catch (Exception ex) {
            logger.error("❌ Failed to fetch token: {}", ex.getMessage(), ex);
        }
    }

    public void ingestAllActivityLogs(String accessToken) {
        logger.info("🚀 Starting activity log sync for last {} months", TOTAL_MONTHS_TO_FETCH);
        categorizedLogs.clear();

        Instant endTime = Instant.now();
        Instant startTime = endTime.minus(TOTAL_MONTHS_TO_FETCH * 30L, ChronoUnit.DAYS);
        Instant currentStart = startTime;

        int totalLogs = 0;

        while (currentStart.isBefore(endTime)) {
            Instant currentEnd = currentStart.plus(MAX_DAYS_PER_REQUEST, ChronoUnit.DAYS);
            if (currentEnd.isAfter(endTime)) currentEnd = endTime;

            logger.info("📦 Fetching chunk {} → {}", currentStart, currentEnd);

            int logsThisChunk = fetchLogsForRange(accessToken, currentStart, currentEnd);
            totalLogs += logsThisChunk;

            saveCategorizedLogs(currentStart, currentEnd);
            currentStart = currentEnd;
        }

        logger.info("✅ Completed: Total logs processed: {}", totalLogs);

        // Save to Neo4j
        JsonArray allLogs = new JsonArray();
        for (JsonArray categoryLogs : categorizedLogs.values()) {
            for (JsonElement log : categoryLogs) {
                allLogs.add(log);
            }
        }


        if (allLogs.size() > 0) {
            neo4jService.saveActivityLogs(allLogs);
        }
    }

    private int fetchLogsForRange(String accessToken, Instant start, Instant end) {
        int total = 0;

        try {
            String url = "https://management.azure.com/subscriptions/" + subscriptionId +
                    "/providers/Microsoft.Insights/eventtypes/management/values";

            String filter = String.format("eventTimestamp ge %s and eventTimestamp le %s",
                    start.truncatedTo(ChronoUnit.SECONDS),
                    end.truncatedTo(ChronoUnit.SECONDS));

            String select = "eventName,operationName,status,eventTimestamp,correlationId," +
                    "submissionTimestamp,level,resourceGroupName,resourceProviderName," +
                    "resourceId,resourceType,caller,authorization,claims,description," +
                    "eventDataId,operationId,properties,category,subscriptionId";

            String requestUrl = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("api-version", "2015-04-01")
                    .queryParam("$filter", filter)
                    .queryParam("$select", select)
                    .build(false).toUriString();

            String nextUrl;
            do {
                ResponseEntity<String> resp = makeApiCallWithRetry(requestUrl, accessToken);
                if (resp == null || !resp.getStatusCode().is2xxSuccessful()) break;

                JsonObject body = JsonParser.parseString(resp.getBody()).getAsJsonObject();
                JsonArray logs = body.has("value") ? body.getAsJsonArray("value") : new JsonArray();

                for (JsonElement el : logs) {
                    JsonObject log = el.getAsJsonObject();
                    String category = determineCategory(log);
                    categorizedLogs.computeIfAbsent(category, k -> new JsonArray()).add(log);
                }

                total += logs.size();
                nextUrl = body.has("nextLink") ? body.get("nextLink").getAsString() : null;
                requestUrl = nextUrl;
            } while (nextUrl != null);

        } catch (Exception e) {
            logger.error("❌ Error fetching logs: {}", e.getMessage());
        }

        return total;
    }

    private String determineCategory(JsonObject log) {
        if (log.has("category")) {
            String cat = log.get("category").getAsString().toLowerCase();
            if (cat.contains("security")) return "Security";
            if (cat.contains("policy")) return "Policy";
            if (cat.contains("servicehealth")) return "ServiceHealth";
        }

        if (log.has("operationName")) {
            String op = log.get("operationName").getAsString().toLowerCase();
            if (op.contains("create") || op.contains("delete") || op.contains("update"))
                return "ResourceManagement";
            if (op.contains("list") || op.contains("get"))
                return "Administrative";
        }

        return "Uncategorized";
    }

    private void saveCategorizedLogs(Instant start, Instant end) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss"));
        String fileName = ACTIVITY_LOGS_DIR + "/activityLogs_" + timestamp + ".json";

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("start", start.toString());
        wrapper.addProperty("end", end.toString());

        categorizedLogs.forEach(wrapper::add);

        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println(wrapper.toString());
            logger.info("💾 Saved activity logs to {}", fileName);
        } catch (IOException e) {
            logger.error("❌ File save error: {}", e.getMessage());
        }
    }


    private ResponseEntity<String> makeApiCallWithRetry(String url, String accessToken) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(accessToken);
                headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                if (response.getStatusCode().is2xxSuccessful()) return response;

                logger.warn("⚠️ Retry {}: HTTP {} from {}", attempt, response.getStatusCode(), url);
                Thread.sleep(RETRY_DELAY_MS * attempt);

            } catch (Exception e) {
                logger.error("❌ Attempt {} failed: {}", attempt, e.getMessage());
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { break; }
            }
        }

        return null;
    }
}