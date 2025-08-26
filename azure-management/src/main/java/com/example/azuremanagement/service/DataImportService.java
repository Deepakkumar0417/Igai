// File: src/main/java/com/example/azuremanagement/service/DataImportService.java
package com.example.azuremanagement.service;

import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.model.Department;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.PasswordProfile;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
public class DataImportService {

    private static final Logger logger = LoggerFactory.getLogger(DataImportService.class);

    // defaults
    private static final String DEFAULT_PERMISSION      = "microsoft.directory/users/manager/read";
    private static final String TENANT_DOMAIN           = "AmritaVishwaVidyapeetham687.onmicrosoft.com";

    // delta/timestamp files
    private static final String DELTA_FILE_DIRECTORY_AUDIT     = "lastDirectoryAuditDeltaLink.txt";
    private static final String DELTA_FILE_SIGNIN              = "lastSignInDeltaLink.txt";
    private static final String DELTA_FILE_ACTIVITY_TIMESTAMP  = "lastActivityLogTimestamp.txt";

    private String lastDeltaLinkDirectory;
    private String lastDeltaLinkSignIn;
    private String lastActivityTimestamp;

    @Value("${azure.subscription-id}")
    private String subscriptionId;

    private final AzureGraphService azureGraphService;
    private final GraphServiceClient<Request> graphClient;
    private final Neo4jService neo4jService;
    private final ActivityLogService activityLogService;

    @Autowired
    public DataImportService(
            AzureGraphService azureGraphService,
            GraphServiceClient<Request> graphClient,
            Neo4jService neo4jService,
            ActivityLogService activityLogService) {
        this.azureGraphService    = azureGraphService;
        this.graphClient          = graphClient;
        this.neo4jService         = neo4jService;
        this.activityLogService   = activityLogService;

        // load saved state
        this.lastDeltaLinkDirectory = loadFromFile(DELTA_FILE_DIRECTORY_AUDIT);
        this.lastDeltaLinkSignIn    = loadFromFile(DELTA_FILE_SIGNIN);
        this.lastActivityTimestamp  = loadFromFile(DELTA_FILE_ACTIVITY_TIMESTAMP);
    }

    /**
     * Import roles, groups, users from a JSON file and then ingest ARM activity logs.
     */
    public void importDemoData(String filePath) {
        try {
            String jsonContent = new String(
                    Files.readAllBytes(Paths.get(filePath)),
                    StandardCharsets.UTF_8
            );
            logger.info("Starting demo data import from file: {}", filePath);

            Gson gson = new Gson();
            DemoData demoData = gson.fromJson(jsonContent, DemoData.class);

            Map<String, String> roleNameToId    = new HashMap<>();
            Map<String, String> groupNameToId   = new HashMap<>();
            Set<String>         userUPNs        = new HashSet<>();
            Map<String, List<String>> groupPermMap = new HashMap<>();
            Map<String, List<String>> userPermMap  = new HashMap<>();

            // 1) existing roles
            try {
                for (CustomRole role : azureGraphService.getAllCustomRoles()) {
                    roleNameToId.put(role.getRoleName(), role.getId());
                }
            } catch (Exception ex) {
                logger.error("Error fetching roles: {}", ex.getMessage());
            }

            // 2) existing groups
            try {
                for (Group g : azureGraphService.getAllGroups()) {
                    groupNameToId.put(g.displayName, g.id);
                }
            } catch (Exception ex) {
                logger.error("Error fetching groups: {}", ex.getMessage());
            }

            // 3) existing users
            try {
                for (User u : azureGraphService.getAllUsers()) {
                    userUPNs.add(u.userPrincipalName);
                }
            } catch (Exception ex) {
                logger.error("Error fetching users: {}", ex.getMessage());
            }

            // 4) create custom roles
            if (demoData.roles != null) {
                for (RoleData rd : demoData.roles) {
                    if (roleNameToId.containsKey(rd.name) || roleNameToId.size() >= 100) continue;
                    try {
                        CustomRole cr = new CustomRole();
                        cr.setRoleName(rd.name);
                        cr.setDescription(rd.description);
                        cr.setPermissions(
                                (rd.permissions != null && !rd.permissions.isEmpty())
                                        ? rd.permissions
                                        : List.of(DEFAULT_PERMISSION)
                        );
                        CustomRole created = azureGraphService.createCustomRole(cr);
                        roleNameToId.put(rd.name, created.getId());
                    } catch (Exception e) {
                        logger.error("Error creating role '{}': {}", rd.name, e.getMessage());
                    }
                }
            }

            // 5) create groups
            if (demoData.groups != null) {
                for (GroupData gd : demoData.groups) {
                    if (!groupNameToId.containsKey(gd.name)) {
                        try {
                            Group g = new Group();
                            g.displayName     = gd.name;
                            g.description     = gd.description;
                            g.mailEnabled     = false;
                            g.securityEnabled = true;
                            g.groupTypes      = new ArrayList<>();
                            g.mailNickname    = sanitizeNickname(gd.name);

                            Group created = azureGraphService.createGroup(g);
                            groupNameToId.put(gd.name, created.id);

                            if (gd.permissions != null && !gd.permissions.isEmpty()) {
                                groupPermMap.put(created.id, gd.permissions);
                            }
                        } catch (Exception e) {
                            logger.error("Error creating group '{}': {}", gd.name, e.getMessage());
                        }
                    } else if (gd.permissions != null) {
                        groupPermMap.put(groupNameToId.get(gd.name), gd.permissions);
                    }
                }
            }

            // 6) bulk assign group permissions
            if (!groupPermMap.isEmpty()) {
                azureGraphService.processGroupPermissions(groupPermMap);
            }

            // 7) auto-provision departments
            if (demoData.users != null) {
                Map<String,String> deptToParent = demoData.users.stream()
                        .filter(ud -> ud.department != null && !ud.department.isBlank())
                        .collect(Collectors.toMap(
                                ud -> ud.department.trim(),
                                ud -> ud.group,
                                (existing, replacement) -> existing
                        ));

                for (Map.Entry<String,String> e : deptToParent.entrySet()) {
                    try {
                        azureGraphService.createDepartment(
                                e.getKey(),
                                "Department " + e.getKey(),
                                List.of("Igai"),
                                e.getValue()
                        );
                    } catch (Exception ex) {
                        logger.error("Error creating department '{}' under '{}': {}",
                                e.getKey(), e.getValue(), ex.getMessage());
                    }
                }
            }

            // 8) process users
            if (demoData.users != null) {
                for (UserData ud : demoData.users) {
                    try {
                        String nick    = sanitizeNickname(ud.name.split("\\s+")[0]);
                        String userUPN = nick + "@" + TENANT_DOMAIN;
                        if (userUPNs.contains(userUPN)) continue;

                        User u = new User();
                        u.displayName       = ud.name;
                        u.userPrincipalName = userUPN;
                        u.mailNickname      = nick;
                        u.accountEnabled    = true;
                        u.department        = ud.department;

                        PasswordProfile pwd = new PasswordProfile();
                        pwd.password                      = "Default@123";
                        pwd.forceChangePasswordNextSignIn = false;
                        u.passwordProfile                 = pwd;

                        User created = azureGraphService.createUser(u);
                        userUPNs.add(created.userPrincipalName);

                        if (ud.role != null) {
                            String rid = roleNameToId.get(ud.role);
                            if (rid == null) {
                                CustomRole cr = new CustomRole();
                                cr.setRoleName(ud.role);
                                cr.setDescription("Role for " + ud.name);
                                cr.setPermissions(List.of(DEFAULT_PERMISSION));
                                CustomRole c2 = azureGraphService.createCustomRole(cr);
                                rid = c2.getId();
                                roleNameToId.put(ud.role, rid);
                            }
                            azureGraphService.assignDirectoryRoleToUser(created.id, rid);
                        }

                        if (ud.permissions != null && !ud.permissions.isEmpty()) {
                            userPermMap.put(created.id, ud.permissions);
                        }

                        if (ud.group != null && groupNameToId.containsKey(ud.group)) {
                            azureGraphService.addUserToGroup(
                                    groupNameToId.get(ud.group), created.id
                            );
                        }

                        if (ud.department != null && !ud.department.isBlank()) {
                            azureGraphService.assignUserToDepartment(
                                    ud.department, created.id
                            );
                        }

                    } catch (Exception e) {
                        logger.error("Error creating user '{}': {}", ud.name, e.getMessage());
                    }
                }
            }

            // 9) bulk assign user permissions
            if (!userPermMap.isEmpty()) {
                azureGraphService.processUserPermissions(userPermMap);
            }

            // 10) push all imported data to Neo4j
            azureGraphService.pushDataToNeo4j();
            logger.info("Demo data import completed successfully.");

            // 11) ingest ARM Activity Logs for resources
            ingestActivityLogs();

            // 12) ingest logs fetched by ActivityLogService
            importActivityLogsFromActivityLogService("all_activity_logs.json");

        } catch (Exception e) {
            logger.error("Import failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Pull ARM activity logs via Azure Monitor endpoint and save them to Neo4j.
     */
    private void ingestActivityLogs() {
        String apiVersion = "2015-04-01";
        String filter = (lastActivityTimestamp != null)
                ? "&$filter=eventTimestamp ge '" + lastActivityTimestamp + "'"
                : "";
        String nextLink = "https://management.azure.com"
                + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Insights/eventtypes/management/values"
                + "?api-version=" + apiVersion
                + filter;

        String maxTs = lastActivityTimestamp;
        do {
            JsonObject response;
            try {
                response = graphClient
                        .customRequest(nextLink, JsonObject.class)
                        .buildRequest()
                        .get();
            } catch (Exception ex) {
                logger.error("Failed to pull ARM activities: {}", ex.getMessage());
                return;
            }

            if (response.has("value")) {
                JsonArray items = response.getAsJsonArray("value");
                logger.info("Pulled {} ARM activity entries", items.size());
                savePageJsonToFile("activityLogs", items);
                neo4jService.saveActivityLogs(items);

                for (JsonElement e : items) {
                    String ts = e.getAsJsonObject()
                            .get("eventTimestamp").getAsString();
                    if (maxTs == null || ts.compareTo(maxTs) > 0) {
                        maxTs = ts;
                    }
                }
            }

            nextLink = response.has("nextLink")
                    ? response.get("nextLink").getAsString()
                    : null;
        } while (nextLink != null);

        if (maxTs != null && !maxTs.equals(lastActivityTimestamp)) {
            saveToFile(DELTA_FILE_ACTIVITY_TIMESTAMP, maxTs);
            lastActivityTimestamp = maxTs;
            logger.info("Saved new ARM activity timestamp: {}", maxTs);
        }
    }

    // ─── File helpers ───────────────────────────────────────────────────────────

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
            logger.error("Failed saving {}: {}", fileName, e.getMessage());
        }
    }

    private void savePageJsonToFile(String prefix, JsonArray pageItems) {
        String ts = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fn = prefix + "_" + ts + ".json";
        try (PrintWriter out = new PrintWriter(new FileWriter(fn))) {
            out.println(pageItems.toString());
            logger.info("Wrote {} page JSON to {}", prefix, fn);
        } catch (IOException e) {
            logger.error("Error writing {} JSON: {}", prefix, e.getMessage());
        }
    }

    private String sanitizeNickname(String in) {
        if (in == null || in.isBlank()) return "user";
        String cleaned = in.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return cleaned.isEmpty() ? "user" : cleaned;
    }

    // JSON schema classes
    private static class DemoData {
        List<UserData>  users;
        List<GroupData> groups;
        List<RoleData>  roles;
    }
    private static class UserData {
        String       id;
        String       name;
        String       email;
        String       role;
        String       group;
        String       department;
        List<String> permissions;
    }
    private static class GroupData {
        String       name;
        String       description;
        List<String> permissions;
    }
    private static class RoleData {
        String       name;
        String       description;
        List<String> permissions;
    }

    /**
     * Import activity logs saved by ActivityLogService into Neo4j.
     */
    public void importActivityLogsFromActivityLogService(String fileName) {
        try (Reader reader = new FileReader(fileName)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            // note: the JSON key is "allActivityLogs" in your finalOutput object:
            JsonArray logs = root.getAsJsonArray("allActivityLogs");
            logger.info("Read {} activity logs from file {}", logs.size(), fileName);
            neo4jService.saveActivityLogs(logs);
        } catch (IOException e) {
            logger.error("Error importing activity logs from file {}: {}", fileName, e.getMessage(), e);
        }
    }
}
