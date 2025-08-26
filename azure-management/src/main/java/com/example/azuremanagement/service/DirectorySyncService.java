package com.example.azuremanagement.service;


import com.example.azuremanagement.model.CustomRole;
import com.microsoft.graph.models.DirectoryRole;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.ServicePrincipal;
import com.microsoft.graph.models.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * Every 6 hours, pulls the latest directory state (users, groups,
 * custom roles, role assignments, group memberships) from Azure AD
 * and synchronizes it into Neo4j.
 */
@Service
public class DirectorySyncService {

    private final AzureGraphService azureGraphService;
    private final Neo4jService      neo4jService;

    @Autowired
    public DirectorySyncService(
            AzureGraphService azureGraphService,
            Neo4jService neo4jService
    ) {
        this.azureGraphService = azureGraphService;
        this.neo4jService      = neo4jService;
    }






    /**
     * Runs every 6 hours (6 * 60 * 60 * 1000 ms).
     * Fetches all users, groups, custom roles, assignments, and memberships,
     * then pushes them into Neo4j via Neo4jService.pushData(...)
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void syncDirectoryToNeo4j() {
        // 1) Fetch core directory entities
        List<CustomRole> roles = azureGraphService.getAllCustomRoles();
        List<Group> groups = azureGraphService.getAllGroups();
        List<User> users = azureGraphService.getAllUsers();

        // 2) Build a map of roleId → CustomRole
        Map<String, CustomRole> roleMap = roles.stream()
                .collect(Collectors.toMap(CustomRole::getId, r -> r));

        // 3) Fetch assignments & memberships
        Map<String, List<String>> userRoles = azureGraphService.getAllUserRoleAssignments();
        Map<String, List<String>> groupRoles = azureGraphService.getAllGroupRoleAssignments();
        Map<String, List<String>> memberships = azureGraphService.getAllGroupMemberships();

        // === ADDED ===
        List<DirectoryRole> directoryRoles = azureGraphService.getAllDirectoryRoles();
        List<ServicePrincipal> servicePrincipals = azureGraphService.getAllServicePrincipals();
        Map<String, List<String>> spRoleAssignments = azureGraphService.getAllServicePrincipalRoleAssignments();
        Map<String, Boolean> mfaStatusMap = azureGraphService.getAllMfaStates(users);

        // 4) Push everything into Neo4j
        neo4jService.pushData(
                users,
                groups,
                roleMap,
                userRoles,
                groupRoles,
                memberships,
                directoryRoles,
                servicePrincipals,
                spRoleAssignments,
                mfaStatusMap
        );
    }

}
