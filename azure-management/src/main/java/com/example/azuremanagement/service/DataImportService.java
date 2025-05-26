package com.example.azuremanagement.service;

import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.model.Department;
import com.google.gson.Gson;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.PasswordProfile;
import com.microsoft.graph.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;    // for mapping departments → groups
import java.util.Objects;            // for filtering nulls

@Service
public class DataImportService {

    private static final Logger logger = LoggerFactory.getLogger(DataImportService.class);

    @Autowired
    private AzureGraphService azureGraphService;

    // fallback permission
    private static final String DEFAULT_PERMISSION   = "microsoft.directory/users/manager/read";
    private static final String TENANT_DOMAIN        = "AmritaVishwaVidyapeetham687.onmicrosoft.com";

    public void importDemoData(String filePath) {
        try {
            String jsonContent = new String(
                    Files.readAllBytes(Paths.get(filePath)),
                    StandardCharsets.UTF_8
            );
            logger.info("Starting demo data import from file: {}", filePath);

            Gson gson = new Gson();
            DemoData demoData = gson.fromJson(jsonContent, DemoData.class);

            // track existing
            Map<String, String> roleNameToId  = new HashMap<>();
            Map<String, String> groupNameToId = new HashMap<>();
            Set<String>         userUPNs      = new HashSet<>();

            // collect for bulk-assign
            Map<String, List<String>> groupPermMap = new HashMap<>();
            Map<String, List<String>> userPermMap  = new HashMap<>();

            // 1) fetch existing custom roles
            try {
                for (CustomRole role : azureGraphService.getAllCustomRoles()) {
                    roleNameToId.put(role.getRoleName(), role.getId());
                }
            } catch (Exception ex) {
                logger.error("Error fetching roles: {}", ex.getMessage());
            }

            // 2) fetch existing groups
            try {
                for (Group g : azureGraphService.getAllGroups()) {
                    groupNameToId.put(g.displayName, g.id);
                }
            } catch (Exception ex) {
                logger.error("Error fetching groups: {}", ex.getMessage());
            }

            // 3) fetch existing users
            try {
                for (User u : azureGraphService.getAllUsers()) {
                    userUPNs.add(u.userPrincipalName);
                }
            } catch (Exception ex) {
                logger.error("Error fetching users: {}", ex.getMessage());
            }

            // 4) process roles
            if (demoData.roles != null) {
                for (RoleData rd : demoData.roles) {
                    if (roleNameToId.containsKey(rd.name) || roleNameToId.size() >= 100) {
                        continue;
                    }
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

            // 5) process groups
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
                    } else {
                        // collect permissions even if exists
                        if (gd.permissions != null) {
                            groupPermMap.put(groupNameToId.get(gd.name), gd.permissions);
                        }
                    }
                }
            }

            // 6) bulk‐assign group permissions
            if (!groupPermMap.isEmpty()) {
                azureGraphService.processGroupPermissions(groupPermMap);
            }

            // ── STEP 7: AUTO‐PROVISION DEPARTMENTS BASED ON USERS ────────────
            if (demoData.users != null) {
                // 7.1) map each department → its parent top-level group (first occurrence)
                Map<String, String> deptToParent = demoData.users.stream()
                        .filter(ud -> ud.department != null && !ud.department.isBlank())
                        .collect(Collectors.toMap(
                                ud -> ud.department.trim(),
                                ud -> ud.group,                // parent group name from JSON
                                (existing, replacement) -> existing // keep first
                        ));

                // 7.2) create one department‐group per distinct department
                for (Map.Entry<String, String> e : deptToParent.entrySet()) {
                    String deptName    = e.getKey();
                    String parentGroup = e.getValue();

                    try {
                        azureGraphService.createDepartment(
                                deptName,
                                "Department " + deptName,
                                List.of("Igai"),       // or adjust as needed
                                parentGroup           // dynamic parent
                        );
                    } catch (Exception ex) {
                        logger.error(
                                "Error creating department '{}' under '{}': {}",
                                deptName, parentGroup, ex.getMessage()
                        );
                    }
                }
            }
            // ────────────────────────────────────────────────────────────────

            // 8) process users (now departments exist)
            if (demoData.users != null) {
                for (UserData ud : demoData.users) {
                    try {
                        String nick   = sanitizeNickname(ud.name.split("\\s+")[0]);
                        String userUPN= nick + "@" + TENANT_DOMAIN;
                        if (userUPNs.contains(userUPN)) {
                            continue;
                        }

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

                        // assign directory role if specified
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

                        // collect user perms
                        if (ud.permissions != null && !ud.permissions.isEmpty()) {
                            userPermMap.put(created.id, ud.permissions);
                        }

                        // direct group
                        if (ud.group != null && groupNameToId.containsKey(ud.group)) {
                            azureGraphService.addUserToGroup(
                                    groupNameToId.get(ud.group),
                                    created.id
                            );
                        }

                        // department membership
                        if (ud.department != null && !ud.department.isBlank()) {
                            azureGraphService.assignUserToDepartment(
                                    ud.department,
                                    created.id
                            );
                        }

                    } catch (Exception e) {
                        logger.error("Error creating user '{}': {}", ud.name, e.getMessage());
                    }
                }
            }

            // 9) bulk‐assign user permissions
            if (!userPermMap.isEmpty()) {
                azureGraphService.processUserPermissions(userPermMap);
            }

            // 10) push to Neo4j
            azureGraphService.pushDataToNeo4j();
            logger.info("Demo data import completed successfully.");

        } catch (Exception e) {
            logger.error("Import failed: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private String sanitizeNickname(String in) {
        if (in == null || in.isBlank()) return "user";
        String cleaned = in.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return cleaned.isEmpty() ? "user" : cleaned;
    }

    // JSON schema classes
    private static class DemoData {
        List<UserData>   users;
        List<GroupData>  groups;
        List<RoleData>   roles;
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
}
