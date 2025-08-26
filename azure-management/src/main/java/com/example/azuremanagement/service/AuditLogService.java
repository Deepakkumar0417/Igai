package com.example.azuremanagement.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

@Service
public class AuditLogService {

    @Autowired
    private ActivityLogService activityLogService;

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private static final String DELTA_FILE_DIRECTORY = "lastDirectoryAuditDeltaLink.txt";
    private static final String DELTA_FILE_SIGNIN = "lastSignInDeltaLink.txt";

    private final GraphServiceClient<Request> graphClient;
    private final Neo4jService neo4jService;

    private String lastDeltaLinkDirectory;
    private String lastDeltaLinkSignIn;

    public AuditLogService(GraphServiceClient<Request> graphClient, Neo4jService neo4jService) {
        this.graphClient = graphClient;
        graphClient.setServiceRoot("https://graph.microsoft.com/beta");
        this.neo4jService = neo4jService;
        this.lastDeltaLinkDirectory = loadFromFile(DELTA_FILE_DIRECTORY);
        this.lastDeltaLinkSignIn = loadFromFile(DELTA_FILE_SIGNIN);
    }

    @Scheduled(fixedRate = 900_000) // 15 minutes
    public void downloadLogs() {
        ingestStream("directoryAudits", lastDeltaLinkDirectory, DELTA_FILE_DIRECTORY, neo4jService::saveAuditLogs);
        ingestSignIns(lastDeltaLinkSignIn, DELTA_FILE_SIGNIN, neo4jService::saveSignInLogs);
    }

    private void ingestSignIns(String savedDeltaLink, String deltaFile, Consumer<JsonArray> handler) {
        String sixMonthsAgo = ZonedDateTime.now().minusMonths(6).toInstant().toString();

        String requestUrl = (lastDeltaLinkSignIn == null)
                ? "/auditLogs/signIns"
                + "?$top=1000"
                + "&$filter=(createdDateTime ge " + sixMonthsAgo
                + " and signInEventTypes/any(t: t eq 'interactiveUser' or t eq 'nonInteractiveUser'))"
                : lastDeltaLinkSignIn;
        String newDeltaLink = null;

        do {
            JsonObject response;
            try {
                response = graphClient
                        .customRequest(requestUrl, JsonObject.class)
                        .buildRequest()
                        .get();
            } catch (Exception ex) {
                logger.error("Graph API error on signIns: {}", ex.getMessage());
                return;
            }

            if (response.has("value")) {
                JsonArray items = response.getAsJsonArray("value");
                logger.info("Pulled {} sign-in items", items.size());
                savePageJsonToFile("signIns", items);
                handler.accept(items);
            }

            if (response.has("@odata.nextLink")) {
                requestUrl = response.get("@odata.nextLink").getAsString();
            } else if (response.has("@odata.deltaLink")) {
                newDeltaLink = response.get("@odata.deltaLink").getAsString();
                requestUrl = null;
            } else {
                requestUrl = null;
            }
        } while (requestUrl != null);

        if (newDeltaLink != null) {
            saveToFile(deltaFile, newDeltaLink);
            lastDeltaLinkSignIn = newDeltaLink;
            logger.info("Saved new deltaLink for signIns: {}", newDeltaLink);
        }
    }

    private void ingestStream(
            String streamName,
            String savedDeltaLink,
            String deltaFile,
            Consumer<JsonArray> handler
    ) {
        String requestUrl = (savedDeltaLink == null)
                ? "/auditLogs/" + streamName
                : savedDeltaLink;
        String newDeltaLink = null;

        do {
            JsonObject response;
            try {
                response = graphClient
                        .customRequest(requestUrl, JsonObject.class)
                        .buildRequest()
                        .get();
            } catch (Exception ex) {
                logger.error("Graph API error on {}: {}", streamName, ex.getMessage());
                return;
            }

            if (response.has("value")) {
                JsonArray items = response.getAsJsonArray("value");
                logger.info("Pulled {} items from {}", items.size(), streamName);
                savePageJsonToFile(streamName, items);
                handler.accept(items);
            }

            if (response.has("@odata.nextLink")) {
                requestUrl = response.get("@odata.nextLink").getAsString();
            } else if (response.has("@odata.deltaLink")) {
                newDeltaLink = response.get("@odata.deltaLink").getAsString();
                requestUrl = null;
            } else {
                requestUrl = null;
            }
        } while (requestUrl != null);

        if (newDeltaLink != null) {
            saveToFile(deltaFile, newDeltaLink);
            if ("directoryAudits".equals(streamName)) {
                lastDeltaLinkDirectory = newDeltaLink;
            }
            logger.info("Saved new deltaLink for {}: {}", streamName, newDeltaLink);
        }
    }

    private String loadFromFile(String fileName) {
        try (BufferedReader r = new BufferedReader(new FileReader(fileName))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private void saveToFile(String fileName, String content) {
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println(content);
        } catch (IOException e) {
            logger.error("Failed writing {}: {}", fileName, e.getMessage());
        }
    }

    private void savePageJsonToFile(String prefix, JsonArray pageItems) {
        String ts = java.time.Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .toString()
                .replace(":", "")
                .replace("Z", "");
        String fileName = prefix + "_" + ts + ".json";
        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println(pageItems.toString());
            logger.info("Wrote {} JSON to {}", prefix, fileName);
        } catch (IOException e) {
            logger.error("Error writing {} JSON: {}", prefix, e.getMessage());
        }
    }
}
