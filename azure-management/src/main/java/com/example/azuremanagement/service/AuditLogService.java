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

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);
    private final GraphServiceClient<Request> graphClient;
    private final Neo4jService neo4jService;

    @Autowired
    public AuditLogService(GraphServiceClient<Request> graphClient, Neo4jService neo4jService) {
        this.graphClient = graphClient;
        this.neo4jService = neo4jService;
    }

    @Scheduled(fixedRate = 3600000)  // Runs hourly
    public void downloadAuditLogs() {
        try {
            JsonObject response = graphClient
                    .customRequest("/auditLogs/directoryAudits", JsonObject.class)
                    .buildRequest()
                    .get();

            if (response.has("value")) {
                JsonArray auditLogs = response.getAsJsonArray("value");
                logger.info("Downloaded {} audit log entries", auditLogs.size());

                // Save audit logs to a file for visual inspection
                saveAuditLogsToFile(auditLogs);

                // Also save audit logs to Neo4j
                neo4jService.saveAuditLogs(auditLogs);
                logger.info("Audit logs successfully pushed to Neo4j.");
            } else {
                logger.warn("No audit logs found in the response.");
            }
        } catch (Exception e) {
            logger.error("Error downloading audit logs: {}", e.getMessage(), e);
        }
    }

    private void saveAuditLogsToFile(JsonArray auditLogs) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "audit-logs_" + timestamp + ".json";

        try (PrintWriter out = new PrintWriter(new FileWriter(fileName))) {
            out.println(auditLogs.toString());
            logger.info("Audit logs saved to file: {}", fileName);
        } catch (IOException e) {
            logger.error("Error writing audit logs to file: {}", e.getMessage(), e);
        }
    }
}
