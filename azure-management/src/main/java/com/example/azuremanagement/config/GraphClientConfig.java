package com.example.azuremanagement.config;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

@Configuration
public class GraphClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(GraphClientConfig.class);

    @Value("${azure.ad.tenant-id}")
    private String tenantId;

    @Value("${azure.ad.client-id}")
    private String clientId;

    @Value("${azure.ad.client-secret}")
    private String clientSecret;

    // Graph scopes for directory operations; typically "https://graph.microsoft.com/.default"
    private static final List<String> GRAPH_SCOPES = Collections.singletonList("https://graph.microsoft.com/.default");

    @Bean
    public GraphServiceClient<Request> graphClient() {
        // Build a credential using the client secret
        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

        // Create a TokenCredentialAuthProvider with the specified scopes and credential
        TokenCredentialAuthProvider tokenCredentialAuthProvider =
                new TokenCredentialAuthProvider(GRAPH_SCOPES, clientSecretCredential);

        // Build the GraphServiceClient and log the successful creation
        GraphServiceClient<Request> client = GraphServiceClient
                .builder()
                .authenticationProvider(tokenCredentialAuthProvider)
                .buildClient();
        logger.info("GraphServiceClient built successfully.");
        return client;
    }
}
