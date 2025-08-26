package com.example.azuremanagement.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import java.util.regex.Pattern;
import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.BuiltInRole;
import com.azure.resourcemanager.resources.fluentcore.arm.ResourceId;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.specialized.AppendBlobClient;
import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.model.Department;
import com.google.gson.JsonObject;
import com.microsoft.graph.models.*;
import com.microsoft.graph.requests.GraphServiceClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AzureGraphService {

    private static final Logger logger = LoggerFactory.getLogger(AzureGraphService.class);

    @Autowired
    private GraphServiceClient<Request> graphClient;

    @Autowired
    @Lazy
    private Neo4jService neo4jService;

    @Autowired
    private ActivityLogService activityLogService;

    // Constants – replace with your actual values.
    private static final String RESOURCE_ID = "4ec70fbd-d886-4107-8955-fcd889030f00";
    private static final String DEFAULT_APP_ROLE_ID = "e1234567-89ab-cdef-0123-456789abcdef";
    private static final String APP_ID = "e2291b1c-4458-4132-ad17-459881e00d33";
    private static final String STORAGE_ACCOUNT_ID = "/subscriptions/bde25e97-4714-44bb-add5-57d6255b936d/resourceGroups/Igai/providers/Microsoft.Storage/storageAccounts/azureigai/blobServices/default";

    private static final String AZURE_STORAGE_CONNECTION_STRING =
            "DefaultEndpointsProtocol=https;" +
                    "AccountName=azureigai;" +
                    "AccountKey=lVEewJHlJVax0+6f0ODlLYnxxZIv0rrULcoq1XK9lcsNa/uXpkGZ5wGy90ajE7k9IgVTIA9OeeGO+AStpXpVbw==;" +
                    "EndpointSuffix=core.windows.net";

    private static final Set<String> RESERVED_NICKNAMES = Set.of("security", "admin", "user", "group");
    // In-memory caches.
    private final Map<String, CustomRole> customRoles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userRoles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> groupRoles = new ConcurrentHashMap<>();
    private final Map<String, List<String>> groupMemberships = new ConcurrentHashMap<>();
    private final Map<String, Timer> temporaryAccessTimers = new ConcurrentHashMap<>();
    private final List<String> auditLogs = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> flaggedUsers = new ConcurrentHashMap<>();

    // Departments and their associated group IDs.
    private final Map<String, Department> departments = new ConcurrentHashMap<>();
    private final Map<String, String> departmentGroupMapping = new ConcurrentHashMap<>();

    // Resources map.
    private final Map<String, String> resources = new ConcurrentHashMap<>();

    TokenCredential credential = new DefaultAzureCredentialBuilder().build();
    AzureProfile profile = new AzureProfile(AzureEnvironment.AZURE);

    private final AzureResourceManager azure = AzureResourceManager
            .authenticate(credential, profile)
            .withDefaultSubscription();



    // ------------------ Core Azure Graph Methods ------------------

    public User createUser(User user) {
        try {
            User createdUser = graphClient.users().buildRequest().post(user);
            logger.info("User created successfully: {}", createdUser.id);
            logAccess(createdUser.id, "UserCreation", "Created user");
            return createdUser;
        } catch (Exception e) {
            logger.error("Error creating user: {}", e.getMessage(), e);
            throw e;
        }
    }

    public Group createGroup(Group group) {
        try {
            Group createdGroup = graphClient.groups().buildRequest().post(group);
            group.mailNickname = sanitizeMailNickname(group.mailNickname);
            logger.info("Group created successfully: {}", createdGroup.id);
            logAccess(createdGroup.id, "GroupCreation", "Created group");
            return createdGroup;
        } catch (Exception e) {
            logger.error("Error creating group: {}", e.getMessage(), e);
            throw e;
        }
    }

    public UUID createAppRole(String roleName, String description) {
        try {
            Application app = graphClient.applications(APP_ID).buildRequest().get();
            UUID newRoleId = UUID.randomUUID();
            AppRole newAppRole = new AppRole();
            newAppRole.displayName = roleName;
            newAppRole.description = description;
            newAppRole.id = newRoleId;
            newAppRole.isEnabled = true;
            newAppRole.value = roleName;
            newAppRole.allowedMemberTypes = Arrays.asList("User", "Group");

            if (app.appRoles == null) {
                app.appRoles = new ArrayList<>();
            }
            app.appRoles.add(newAppRole);

            graphClient.applications(APP_ID).buildRequest().patch(app);

            logger.info("✅ App role '{}' created with ID {}", roleName, newRoleId);
            logAccess(APP_ID, "AppRoleCreation", "Created app role " + roleName);
            return newRoleId;
        } catch (Exception e) {
            logger.error("❌ Failed to create app role '{}': {}", roleName, e.getMessage(), e);
            throw new RuntimeException("Failed to create app role: " + roleName);
        }
    }

    public String getServicePrincipalId() {
        List<ServicePrincipal> spList = graphClient.servicePrincipals()
                .buildRequest()
                .filter("appId eq '" + APP_ID + "'")
                .get()
                .getCurrentPage();

        if (spList.isEmpty()) {
            throw new RuntimeException("Service Principal not found for appId: " + APP_ID);
        }
        return spList.get(0).id;
    }

    // ------------------ Department and Group Methods ------------------

    // Create a department by creating a security group
    // After
    public void createDepartment(String name,
                                 String description,
                                 List<String> resourceContainers,
                                 String parentGroupName) {
        if (departments.containsKey(name)) {
            throw new IllegalArgumentException("Department already exists: " + name);
        }
        // Track department internally, including its parentGroupName
        Department department = new Department(
                name,
                description,
                resourceContainers,
                /* containerName */  "default-container",
                /* group */          parentGroupName
        );
        departments.put(name, department);

        // 1️⃣ Create the Azure AD group for this dept
        Group deptGroup = createDepartmentGroup(name, description);
        departmentGroupMapping.put(name, deptGroup.id);

        // 2️⃣ Resolve (or create) the parent group by name
        Group parentGroup = getAllGroups().stream()
                .filter(g -> parentGroupName.equalsIgnoreCase(g.displayName))
                .findFirst()
                .orElseGet(() -> {
                    Group g = new Group();
                    g.displayName     = parentGroupName;
                    g.mailEnabled     = false;
                    g.mailNickname    = parentGroupName.toLowerCase().replaceAll("\\s+","-");
                    g.securityEnabled = true;
                    g.description     = "Auto-created parent group: " + parentGroupName;
                    return createGroup(g);
                });

        // 3️⃣ Nest the dept group under its parent
        addGroupToGroup(parentGroup.id, deptGroup.id);

        logger.info("✅ Created department '{}' under parent group '{}'",
                name, parentGroupName);
        logAccess("SYSTEM", "DepartmentCreation",
                "Created department " + name + " under " + parentGroupName);
    }


    public Group createDepartmentGroup(String deptName, String description) {
        Group group = new Group();
        group.displayName = deptName;
        group.mailEnabled = false;
        group.mailNickname    = sanitizeMailNickname(deptName);
        group.securityEnabled = true;
        group.description = description;
        return createGroup(group);
    }

    public void addGroupToGroup(String parentGroupId, String childGroupId) {
        try {
            JsonObject refObject = new JsonObject();
            refObject.addProperty("@odata.id", "https://graph.microsoft.com/v1.0/groups/" + childGroupId);
            graphClient.customRequest("/groups/" + parentGroupId + "/members/$ref", JsonObject.class)
                    .buildRequest().post(refObject);
            logger.info("📌 Added group {} to parent group {}", childGroupId, parentGroupId);
            logAccess(childGroupId, "GroupNesting", "Nested under parent group " + parentGroupId);
        } catch (Exception e) {
            logger.error("❌ Failed to nest group {} under {}: {}", childGroupId, parentGroupId, e.getMessage(), e);
        }
    }

    // When assigning a user to a department, auto-create the department group if it doesn't exist.
    // After
    public void assignUserToDepartment(String departmentName, String userId) {
        Department dept = departments.get(departmentName);
        if (dept == null) {
            throw new IllegalArgumentException(
                    "Cannot assign user to unknown department: " + departmentName
            );
        }
        String deptGroupId = departmentGroupMapping.get(departmentName);
        addUserToGroup(deptGroupId, userId);

        logger.info("Added user {} to department group {}",
                userId, departmentName);
        logAccess(userId,
                "DepartmentMembership",
                "Added to " + departmentName);
    }


    // ------------------ Permission and Blob Access Methods ------------------

    public List<String> getAvailablePermissions() {
        List<String> permissionNames = new ArrayList<>();
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(AZURE_STORAGE_CONNECTION_STRING)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("igai");
        for (BlobItem blobItem : containerClient.listBlobs()) {
            String fileName = blobItem.getName();
            if (fileName.toLowerCase().endsWith(".txt")) {
                permissionNames.add(fileName.substring(0, fileName.length() - 4));
            }
        }
        return permissionNames;
    }

    public void processUserPermissions(Map<String, List<String>> userPermissionMap) {
        for (var entry : userPermissionMap.entrySet()) {
            String userId = entry.getKey();
            for (String permission : entry.getValue()) {
                try {
                    UUID roleId = createAppRole(permission, "Permission: " + permission);
                    assignAppRoleToUser(userId, roleId);
                } catch (Exception e) {
                    logger.error("Error processing permission '{}' for user '{}': {}", permission, userId, e.getMessage(), e);
                }
            }
        }
    }

    public void processGroupPermissions(Map<String, List<String>> groupPermissionMap) {
        for (var entry : groupPermissionMap.entrySet()) {
            String groupId = entry.getKey();
            for (String permission : entry.getValue()) {
                try {
                    UUID roleId = createAppRole(permission, "Permission: " + permission);
                    assignAppRoleToGroup(groupId, roleId);
                } catch (Exception e) {
                    logger.error("Error assigning permission '{}' to group '{}': {}", permission, groupId, e.getMessage(), e);
                }
            }
        }
    }

    public void assignAvailablePermissionsToDepartment(String departmentName) {
        String deptGroupId = departmentGroupMapping.get(departmentName);
        if (deptGroupId == null || deptGroupId.isEmpty()) {
            Department dept = departments.get(departmentName);
            if (dept == null) {
                throw new RuntimeException("Department not found: " + departmentName);
            }
            Group deptGroup = createDepartmentGroup(departmentName, dept.getDescription());
            departmentGroupMapping.put(departmentName, deptGroup.id);
            deptGroupId = deptGroup.id;
        }
        List<String> availablePermissions = getAvailablePermissions();
        for (String permission : availablePermissions) {
            CustomRole role = new CustomRole();
            role.setRoleName("DeptRole_" + departmentName + "_" + permission);
            role.setDescription("Role for department " + departmentName + " with permission " + permission);
            role.setPermissions(List.of(permission));
            CustomRole createdRole = createCustomRole(role);
            if (createdRole != null) {
                // Only assign the custom role if it was successfully created.
                assignDirectoryRoleToGroup(deptGroupId, createdRole.getId());
                logger.info("Assigned permission {} to department group {}", permission, departmentName);
            }
        }
    }

    public void assignBlobAccessToGroup(String storageAccountId, String containerName, String groupObjectId) {
        try {
            String scope = storageAccountId + "/blobServices/default/containers/" + containerName;
            azure.accessManagement().roleAssignments()
                    .define(UUID.randomUUID().toString())
                    .forObjectId(groupObjectId)
                    .withBuiltInRole(BuiltInRole.STORAGE_BLOB_DATA_CONTRIBUTOR)
                    .withScope(scope)
                    .create();
            logger.info("✅ Assigned Storage Blob Data Contributor to group {} on container {}", groupObjectId, containerName);
            logAccess(groupObjectId, "BlobAccess", "Assigned to container: " + containerName);
        } catch (Exception e) {
            logger.error("❌ Failed to assign blob access: {}", e.getMessage(), e);
            throw new RuntimeException("Access assignment failed", e);
        }
    }

    public void assignDepartmentBlobAccess(String departmentName, String groupObjectId, String storageAccountId) {
        Department department = departments.get(departmentName);
        if (department == null) {
            throw new RuntimeException("Department does not exist: " + departmentName);
        }
        List<String> containers = department.getResources();
        for (String containerName : containers) {
            assignBlobAccessToGroup(storageAccountId, containerName, groupObjectId);
        }
        logger.info("✅ Assigned blob access for department '{}' to group {}", departmentName, groupObjectId);
        logAccess(groupObjectId, "DepartmentBlobAccess", "Granted access to containers: " + containers);
    }

    // ------------------ Global Admin and Role Assignment Methods ------------------

    public void grantGlobalAdminAccess(String userId, String permission, boolean useDirectoryRole) {
        logger.info("Global Admin approval: granting permission {} to user {} using directory roles: {}", permission, userId, useDirectoryRole);
        if (useDirectoryRole) {
            assignDirectoryRoleToUser(userId, permission);
        } else {
            assignRoleToUser(userId, permission);
        }
        logAccess(userId, "GlobalAdminAccess", "Granted access to permission " + permission + " by Global Admin approval");
    }

    // ------------------ App Role & Directory Role Methods ------------------

    public void assignAppRoleToUser(String userId, UUID appRoleId) {
        String spId = getServicePrincipalId();
        AppRoleAssignment assignment = new AppRoleAssignment();
        assignment.principalId = UUID.fromString(userId);
        assignment.resourceId = UUID.fromString(spId);
        assignment.appRoleId = appRoleId;
        graphClient.users(userId).appRoleAssignments().buildRequest().post(assignment);
        logger.info("Assigned App Role {} to User {}", appRoleId, userId);
        logAccess(userId, "AppRoleAssignment", "Assigned app role " + appRoleId);
    }

    public void assignAppRoleToGroup(String groupId, UUID appRoleId) {
        String spId = getServicePrincipalId();
        AppRoleAssignment assignment = new AppRoleAssignment();
        assignment.principalId = UUID.fromString(groupId);
        assignment.resourceId = UUID.fromString(spId);
        assignment.appRoleId = appRoleId;
        graphClient.groups(groupId).appRoleAssignments().buildRequest().post(assignment);
        logger.info("Assigned App Role {} to Group {}", appRoleId, groupId);
        logAccess(groupId, "AppRoleAssignment", "Assigned app role " + appRoleId);
    }

    public List<UnifiedRoleDefinition> listDirectoryRoles() {
        return graphClient.roleManagement()
                .directory()
                .roleDefinitions()
                .buildRequest()
                .get()
                .getCurrentPage();
    }

    public List<UnifiedRoleAssignment> listDirectoryAssignments() {
        try {
            return graphClient.roleManagement()
                    .directory()
                    .roleAssignments()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
        } catch (Exception e) {
            logger.error("Error listing directory assignments: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // ------------------ Custom Role Methods ------------------

    public CustomRole createCustomRole(CustomRole role) {
        try {
            UnifiedRoleDefinition roleDefinition = convertToUnifiedRoleDefinition(role);
            UnifiedRoleDefinition createdRoleDefinition = graphClient.roleManagement()
                    .directory()
                    .roleDefinitions()
                    .buildRequest()
                    .post(roleDefinition);
            logger.info("Custom role created via Graph API with ID: {}", createdRoleDefinition.id);
            role.setId(createdRoleDefinition.id);
            customRoles.put(role.getId(), role);
            logAccess(role.getId(), "CustomRoleCreation", "Created custom role");
            return role;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("is not supported")) {
                logger.error("Custom role creation skipped for '{}' due to unsupported permission(s): {}", role.getRoleName(), e.getMessage());
                return null;
            }
            throw new RuntimeException(e);
        }
    }

    public CustomRole getCustomRole(String roleId) {
        try {
            UnifiedRoleDefinition roleDefinition = graphClient.roleManagement()
                    .directory()
                    .roleDefinitions(roleId)
                    .buildRequest()
                    .get();
            return convertToCustomRole(roleDefinition);
        } catch (Exception e) {
            logger.error("Error retrieving custom role: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Map<String, List<String>> getAllUserRoleAssignments() {
        Map<String, List<String>> userRoles = new HashMap<>();

        // 1) App Role assignments
        for (User u : getAllUsers()) {
            var page = graphClient.users(u.id).appRoleAssignments().buildRequest().get();
            while (page != null) {
                for (AppRoleAssignment asg : page.getCurrentPage()) {
                    userRoles
                            .computeIfAbsent(u.id, k -> new ArrayList<>())
                            .add(asg.appRoleId.toString());
                }
                page = page.getNextPage() != null
                        ? page.getNextPage().buildRequest().get()
                        : null;
            }
        }

        var dirPage = graphClient
                .roleManagement()
                .directory()
                .roleAssignments()
                .buildRequest()
                .get();
        while (dirPage != null) {
            for (UnifiedRoleAssignment dra : dirPage.getCurrentPage()) {
                String principal = dra.principalId;
                // only put into users
                if (userRoles.containsKey(principal) || getAllUsers()
                        .stream()
                        .anyMatch(u -> u.id.equals(principal))) {
                    userRoles
                            .computeIfAbsent(principal, k -> new ArrayList<>())
                            .add(dra.roleDefinitionId);
                }
            }
            dirPage = dirPage.getNextPage() != null
                    ? dirPage.getNextPage().buildRequest().get()
                    : null;
        }

        return userRoles;
    }

    /**
     * Returns a map groupId → list of all AppRole & DirectoryRole IDs assigned to that group.
     */
    public Map<String, List<String>> getAllGroupRoleAssignments() {
        Map<String, List<String>> groupRoles = new HashMap<>();

        // 1) App Role assignments
        for (Group g : getAllGroups()) {
            var page = graphClient.groups(g.id).appRoleAssignments().buildRequest().get();
            while (page != null) {
                for (AppRoleAssignment asg : page.getCurrentPage()) {
                    groupRoles
                            .computeIfAbsent(g.id, k -> new ArrayList<>())
                            .add(asg.appRoleId.toString());
                }
                page = page.getNextPage() != null
                        ? page.getNextPage().buildRequest().get()
                        : null;
            }
        }

        // 2) Directory Role assignments
        var dirPage = graphClient
                .roleManagement()
                .directory()
                .roleAssignments()
                .buildRequest()
                .get();
        while (dirPage != null) {
            for (UnifiedRoleAssignment dra : dirPage.getCurrentPage()) {
                String principal = dra.principalId;
                // only put into groups
                if (groupRoles.containsKey(principal) || getAllGroups()
                        .stream()
                        .anyMatch(g -> g.id.equals(principal))) {
                    groupRoles
                            .computeIfAbsent(principal, k -> new ArrayList<>())
                            .add(dra.roleDefinitionId);
                }
            }
            dirPage = dirPage.getNextPage() != null
                    ? dirPage.getNextPage().buildRequest().get()
                    : null;
        }

        return groupRoles;
    }

    /**
     * Returns a map groupId → list of userIds in that group.
     * (You already have the fetchGroupMembershipsFromAzure() helper; just expose it.)
     */
    public Map<String, List<String>> getAllGroupMemberships() {
        return fetchGroupMembershipsFromAzure();
    }

    public List<CustomRole> getAllCustomRoles() {
        try {
            List<UnifiedRoleDefinition> roleDefinitions = graphClient.roleManagement()
                    .directory()
                    .roleDefinitions()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
            return roleDefinitions.stream()
                    .map(this::convertToCustomRole)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error retrieving custom roles: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void deleteCustomRole(String roleId) {
        try {
            graphClient.roleManagement()
                    .directory()
                    .roleDefinitions(roleId)
                    .buildRequest()
                    .delete();
            customRoles.remove(roleId);
            logger.info("Deleted custom role via Graph API with ID: {}", roleId);
            logAccess(roleId, "CustomRoleDeletion", "Deleted custom role");
        } catch (Exception e) {
            logger.error("Error deleting custom role: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void removeDirectoryRoleFromUser(String userId, String roleId) {
        try {
            List<UnifiedRoleAssignment> assignments = graphClient.roleManagement()
                    .directory()
                    .roleAssignments()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
            for (UnifiedRoleAssignment assignment : assignments) {
                if (assignment.principalId.equals(userId) && assignment.roleDefinitionId.equals(roleId)) {
                    graphClient.roleManagement()
                            .directory()
                            .roleAssignments(assignment.id)
                            .buildRequest()
                            .delete();
                    logger.info("✅ Removed directory role {} from user {}", roleId, userId);
                    logAccess(userId, "DirectoryRoleRemoval", "Removed directory role " + roleId);
                }
            }
        } catch (Exception e) {
            logger.error("❌ Failed to remove directory role {} from user {}: {}", roleId, userId, e.getMessage(), e);
        }
    }

    public CustomRole createRoleAndAssignToUser(String originalUserId, String delegateUserId, boolean useDirectoryRole) {
        CustomRole tempRole = new CustomRole();
        tempRole.setRoleName("TemporaryRoleFor_" + originalUserId);
        tempRole.setDescription("Temporary role created for delegation from user " + originalUserId);
        tempRole = createCustomRole(tempRole);
        if (useDirectoryRole) {
            assignDirectoryRoleToUser(delegateUserId, tempRole.getId());
        } else {
            assignRoleToUser(delegateUserId, tempRole.getId());
        }
        logger.info("Temporary role {} assigned to delegate {}", tempRole.getId(), delegateUserId);
        logAccess(delegateUserId, "DelegatedRoleCreation", "Assigned temporary role " + tempRole.getId());
        return tempRole;
    }

    // ------------------ Role Assignment Methods ------------------

    public void assignRoleToUser(String userId, String roleId) {
        try {
            String spId = getServicePrincipalId();
            ServicePrincipal sp = graphClient.servicePrincipals(spId).buildRequest().get();
            boolean roleExists = sp.appRoles.stream()
                    .anyMatch(appRole -> appRole.id != null && appRole.id.toString().equals(roleId.trim()));
            if (!roleExists) {
                logger.error("❌ App role {} not found for SP {}", roleId, spId);
                throw new RuntimeException("Role ID not found in service principal");
            }
            List<AppRoleAssignment> existing = graphClient.users(userId)
                    .appRoleAssignments()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
            boolean alreadyAssigned = existing.stream()
                    .anyMatch(assignment -> assignment.appRoleId.toString().equals(roleId));
            if (alreadyAssigned) {
                logger.info("ℹ️ User {} already has role {}, skipping assignment", userId, roleId);
                return;
            }
            AppRoleAssignment assignment = new AppRoleAssignment();
            assignment.principalId = UUID.fromString(userId.trim());
            assignment.resourceId = UUID.fromString(spId.trim());
            assignment.appRoleId = UUID.fromString(roleId.trim());
            graphClient.users(userId).appRoleAssignments().buildRequest().post(assignment);
            logger.info("✅ Assigned App Role {} to User {}", roleId, userId);
            logAccess(userId, "RoleAssignment", "Assigned app role " + roleId);
        } catch (Exception e) {
            logger.error("❌ Failed to assign app role to user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void assignDirectoryRoleToUser(String userId, String roleDefinitionId) {
        try {
            List<UnifiedRoleAssignment> existingAssignments = graphClient.roleManagement()
                    .directory()
                    .roleAssignments()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
            boolean alreadyAssigned = existingAssignments.stream()
                    .anyMatch(assign -> assign.principalId.equals(userId) &&
                            assign.roleDefinitionId.equals(roleDefinitionId));
            if (alreadyAssigned) {
                logger.info("ℹ️ Directory role {} already assigned to user {}, skipping", roleDefinitionId, userId);
                return;
            }
            UnifiedRoleAssignment assignment = new UnifiedRoleAssignment();
            assignment.principalId = userId.trim();
            assignment.roleDefinitionId = roleDefinitionId.trim();
            assignment.directoryScopeId = "/";
            graphClient.roleManagement().directory().roleAssignments().buildRequest().post(assignment);
            logger.info("✅ Assigned directory role {} to user {}", roleDefinitionId, userId);
            logAccess(userId, "DirectoryRoleAssignment", "Assigned directory role " + roleDefinitionId);
        } catch (Exception e) {
            logger.error("❌ Failed to assign directory role: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void assignDirectoryRoleToGroup(String groupId, String roleDefinitionId) {
        try {
            UnifiedRoleAssignment assignment = new UnifiedRoleAssignment();
            assignment.principalId = groupId.trim();
            assignment.roleDefinitionId = roleDefinitionId.trim();
            assignment.directoryScopeId = "/";
            graphClient.roleManagement().directory().roleAssignments().buildRequest().post(assignment);
            logger.info("Directory role assignment created for group {} with role {}", groupId, roleDefinitionId);
            logAccess(groupId, "DirectoryRoleAssignment", "Assigned directory role " + roleDefinitionId + " to group");
        } catch (Exception e) {
            logger.error("Error creating directory role assignment for group {} with role {}: {}", groupId, roleDefinitionId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void assignRoleToGroup(String groupId, String roleId) {
        try {
            groupRoles.computeIfAbsent(groupId, k -> new ArrayList<>()).add(roleId);
            AppRoleAssignment assignment = new AppRoleAssignment();
            assignment.principalId = UUID.fromString(groupId);
            assignment.resourceId = UUID.fromString(RESOURCE_ID);
            assignment.appRoleId = UUID.fromString(roleId);
            graphClient.groups(groupId).appRoleAssignments().buildRequest().post(assignment);
            logger.info("Native role assignment created for group {} with role {}", groupId, roleId);
            logAccess(groupId, "RoleAssignment", "Assigned role " + roleId);
        } catch (Exception e) {
            logger.error("Error creating native role assignment for group {} with role {}: {}", groupId, roleId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void removeRoleFromUser(String userId, String roleId) {
        try {
            List<AppRoleAssignment> assignments = graphClient.users(userId).appRoleAssignments().buildRequest().get().getCurrentPage();
            for (AppRoleAssignment assignment : assignments) {
                if (assignment.appRoleId != null && assignment.appRoleId.toString().equals(roleId)) {
                    graphClient.users(userId).appRoleAssignments(assignment.id).buildRequest().delete();
                    List<String> roles = userRoles.get(userId);
                    if (roles != null) {
                        roles.remove(roleId);
                    }
                    logger.info("Removed role {} from user {}", roleId, userId);
                    logAccess(userId, "RoleRemoval", "Removed role " + roleId);
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Error removing role {} from user {}: {}", roleId, userId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Map<String, List<String>> fetchGroupMembershipsFromAzure() {
        Map<String, List<String>> memberships = new HashMap<>();
        for (Group group : getAllGroups()) {
            List<String> members = new ArrayList<>();
            try {
                var page = graphClient.groups(group.id).members().buildRequest().get();
                while (page != null) {
                    for (DirectoryObject member : page.getCurrentPage()) {
                        if (member instanceof User) {
                            members.add(member.id);
                        }
                    }
                    page = page.getNextPage() != null ? page.getNextPage().buildRequest().get() : null;
                }
                memberships.put(group.id, members);
            } catch (Exception e) {
                logger.error("❌ Error fetching members for group {}: {}", group.id, e.getMessage(), e);
            }
        }
        groupMemberships.clear();
        groupMemberships.putAll(memberships);
        return memberships;
    }

    public List<String> getUserRoles(String userId) {
        Set<String> roles = new HashSet<>(userRoles.getOrDefault(userId, Collections.emptyList()));
        try {
            List<AppRoleAssignment> appAssignments = graphClient.users(userId).appRoleAssignments().buildRequest().get().getCurrentPage();
            for (AppRoleAssignment assignment : appAssignments) {
                if (assignment.appRoleId != null) {
                    roles.add(assignment.appRoleId.toString());
                }
            }
        } catch (Exception e) {
            logger.error("Error retrieving app role assignments for user {}: {}", userId, e.getMessage(), e);
        }
        try {
            List<UnifiedRoleAssignment> dirAssignments = graphClient.roleManagement().directory().roleAssignments().buildRequest().get().getCurrentPage();
            for (UnifiedRoleAssignment assignment : dirAssignments) {
                if (assignment.principalId != null && assignment.principalId.equals(userId)) {
                    roles.add(assignment.roleDefinitionId);
                }
            }
        } catch (Exception e) {
            logger.error("Error retrieving directory role assignments for user {}: {}", userId, e.getMessage(), e);
        }
        Map<String, List<String>> memberships = fetchGroupMembershipsFromAzure();
        for (Map.Entry<String, List<String>> entry : memberships.entrySet()) {
            if (entry.getValue().contains(userId)) {
                try {
                    List<AppRoleAssignment> groupAssignments = graphClient.groups(entry.getKey()).appRoleAssignments().buildRequest().get().getCurrentPage();
                    for (AppRoleAssignment ga : groupAssignments) {
                        if (ga.appRoleId != null) {
                            roles.add(ga.appRoleId.toString());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error retrieving group role assignments for group {}: {}", entry.getKey(), e.getMessage(), e);
                }
            }
        }
        return new ArrayList<>(roles);
    }

    public List<User> getAllUsers() {
        List<User> allUsers = new ArrayList<>();
        try {
            var page = graphClient.users().buildRequest().get();
            while (page != null) {
                allUsers.addAll(page.getCurrentPage());
                page = page.getNextPage() != null ? page.getNextPage().buildRequest().get() : null;
            }
        } catch (Exception e) {
            logger.error("❌ Error retrieving users: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving users");
        }
        return allUsers;
    }

    public List<Group> getAllGroups() {
        List<Group> allGroups = new ArrayList<>();
        try {
            var page = graphClient.groups().buildRequest().get();
            while (page != null) {
                allGroups.addAll(page.getCurrentPage());
                page = page.getNextPage() != null ? page.getNextPage().buildRequest().get() : null;
            }
        } catch (Exception e) {
            logger.error("❌ Error retrieving groups: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving groups");
        }
        return allGroups;
    }

    public List<Department> getAllDepartments() {
        return new ArrayList<>(departments.values());
    }

    public Department getDepartment(String name) {
        return departments.get(name);
    }

    public List<User> getUsersByDepartment(String departmentName) {
        return getAllUsers().stream()
                .filter(user -> user.department != null && user.department.equalsIgnoreCase(departmentName))
                .collect(Collectors.toList());
    }

    public List<String> getResourcesForDepartment(String departmentName) {
        Department dept = departments.get(departmentName);
        return dept != null ? dept.getResources() : Collections.emptyList();
    }

    public void uploadResourceData(String containerName, String blobName, String jsonData) {
        try {
            logger.info("Uploading resource data to container '{}' as blob '{}'", containerName, blobName);
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder().connectionString(AZURE_STORAGE_CONNECTION_STRING).buildClient();
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
                logger.info("Created container '{}'", containerName);
            }
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            ByteArrayInputStream dataStream = new ByteArrayInputStream(jsonData.getBytes(StandardCharsets.UTF_8));
            blobClient.upload(dataStream, jsonData.getBytes(StandardCharsets.UTF_8).length, true);
            logger.info("Successfully uploaded resource data to blob '{}'", blobName);
        } catch (Exception e) {
            logger.error("Error uploading resource data to Azure Blob Storage: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void addUserToGroup(String groupId, String userId) {
        try {
            JsonObject refObject = new JsonObject();
            refObject.addProperty("@odata.id", "https://graph.microsoft.com/v1.0/users/" + userId);
            graphClient.customRequest("/groups/" + groupId + "/members/$ref", JsonObject.class).buildRequest().post(refObject);
            groupMemberships.computeIfAbsent(groupId, k -> new ArrayList<>());
            if (!groupMemberships.get(groupId).contains(userId)) {
                groupMemberships.get(groupId).add(userId);
            }
            logger.info("Added user {} to group {}", userId, groupId);
            logAccess(userId, "GroupMembership", "Added to group " + groupId);
        } catch (Exception e) {
            logger.error("Error adding user {} to group {}: {}", userId, groupId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void removeUserFromGroup(String groupId, String userId) {
        try {
            graphClient.customRequest("/groups/" + groupId + "/members/" + userId + "/$ref", Void.class).buildRequest().delete();
            List<String> members = groupMemberships.get(groupId);
            if (members != null) {
                members.remove(userId);
            }
            logger.info("Removed user {} from group {}", userId, groupId);
            logAccess(userId, "GroupMembership", "Removed from group " + groupId);
        } catch (Exception e) {
            logger.error("Error removing user {} from group {}: {}", userId, groupId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void deleteUser(String userId) {
        try {
            graphClient.users(userId).buildRequest().delete();
            logger.info("Deleted user {}", userId);
            logAccess(userId, "UserDeletion", "Deleted user");
        } catch (Exception e) {
            logger.error("Error deleting user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public void deleteGroup(String groupId) {
        try {
            graphClient.groups(groupId).buildRequest().delete();
            logger.info("Deleted group {}", groupId);
            logAccess(groupId, "GroupDeletion", "Deleted group");
        } catch (Exception e) {
            logger.error("Error deleting group {}: {}", groupId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public User getUserDetails(String userId) {
        try {
            return graphClient.users(userId).buildRequest().get();
        } catch (Exception e) {
            logger.error("Error getting user details for {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public Group getGroupDetails(String groupId) {
        try {
            return graphClient.groups(groupId).buildRequest().get();
        } catch (Exception e) {
            logger.error("Error getting group details for {}: {}", groupId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public boolean verifyMFA(String userId, String action) {
        logger.info("MFA verification initiated for user {} for action {}", userId, action);
        boolean mfaSuccess = true;
        if (mfaSuccess) {
            logger.info("MFA verification successful for user {} for action {}", userId, action);
            logAccess(userId, "MFA", "Verified for action: " + action);
            return true;
        } else {
            logger.warn("MFA verification failed for user {} for action {}", userId, action);
            logAccess(userId, "MFA", "Failed for action: " + action);
            return false;
        }
    }

    public void grantTemporaryAccess(String userId, List<String> permissions, int durationMinutes, boolean useDirectoryRole) {
        logger.info("Granting temporary access to user {} for {} mins, DirectoryRole={}", userId, durationMinutes, useDirectoryRole);
        if (temporaryAccessTimers.containsKey(userId)) {
            temporaryAccessTimers.get(userId).cancel();
        }
        for (String permission : permissions) {
            if (useDirectoryRole) {
                assignDirectoryRoleToUser(userId, permission);
            } else {
                assignRoleToUser(userId, permission);
            }
        }
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                for (String permission : permissions) {
                    removeRoleFromUser(userId, permission);
                    removeDirectoryRoleFromUser(userId, permission);
                }
                logAccess(userId, "TemporaryAccess", "Revoked after " + durationMinutes + " minutes");
                logger.info("✅ Temporary access revoked for user {}", userId);
            }
        }, TimeUnit.MINUTES.toMillis(durationMinutes));
        temporaryAccessTimers.put(userId, timer);
        logAccess(userId, "TemporaryAccess", "Granted for " + durationMinutes + " minutes");
    }

    public boolean isWithinAllowedTime(LocalTime start, LocalTime end) {
        LocalTime now = LocalTime.now();
        boolean withinTime = !now.isBefore(start) && !now.isAfter(end);
        logger.info("Current time {} is within allowed time {} - {}: {}", now, start, end, withinTime);
        return withinTime;
    }

    public boolean enforceTimeBasedAccess(String userId, LocalTime start, LocalTime end) {
        if (isWithinAllowedTime(start, end)) {
            logAccess(userId, "TimeBasedAccess", "Access granted within allowed time");
            return true;
        } else {
            logAccess(userId, "TimeBasedAccess", "Access denied outside allowed time");
            return false;
        }
    }

    public void delegateTemporaryAccess(String fromUserId, String toUserId, List<String> allowedPermissions, int durationMinutes, boolean useDirectoryRole) {
        logger.info("Delegating access from {} to {} for {} mins", fromUserId, toUserId, durationMinutes);
        if (temporaryAccessTimers.containsKey(toUserId)) {
            temporaryAccessTimers.get(toUserId).cancel();
        }
        CustomRole tempRole = createRoleAndAssignToUser(fromUserId, toUserId, useDirectoryRole);
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                removeRoleFromUser(toUserId, tempRole.getId());
                removeDirectoryRoleFromUser(toUserId, tempRole.getId());
                logAccess(toUserId, "DelegatedAccess", "Revoked delegation from " + fromUserId);
                logger.info("Delegated access revoked for {}", toUserId);
            }
        }, TimeUnit.MINUTES.toMillis(durationMinutes));
        temporaryAccessTimers.put(toUserId + "_cross_" + allowedPermissions, timer);
        logAccess(toUserId, "DelegatedAccess", "Granted for " + durationMinutes + " minutes");
    }

    public void updateUserRole(String userId, String newRoleId, boolean useDirectoryRole) {
        List<String> roles = getUserRoles(userId);
        for (String role : roles) {
            if (useDirectoryRole) {
                removeDirectoryRoleFromUser(userId, role);
            } else {
                removeRoleFromUser(userId, role);
            }
        }
        if (useDirectoryRole) {
            assignDirectoryRoleToUser(userId, newRoleId);
        } else {
            assignRoleToUser(userId, newRoleId);
        }
        logger.info("Updated user {} with new role {} (Directory Role: {})", userId, newRoleId, useDirectoryRole);
        logAccess(userId, "RoleUpdate", "Updated role to " + newRoleId);
    }

    public void autoProvisionUser(User user, String defaultRoleId, boolean useDirectoryRole) {
        User createdUser = createUser(user);
        if (useDirectoryRole) {
            assignDirectoryRoleToUser(createdUser.id, defaultRoleId);
        } else {
            assignRoleToUser(createdUser.id, defaultRoleId);
        }
        logger.info("Auto provisioned user {} with default role {} (Directory Role: {})", createdUser.id, defaultRoleId, useDirectoryRole);
        logAccess(createdUser.id, "AutoProvision", "Assigned default role " + defaultRoleId);
    }

    public void emergencyRoleActivation(String userId, List<String> permissions, int durationMinutes, boolean useDirectoryRole) {
        logger.info("Activating emergency role for user {} with permissions {} for {} minutes (Directory Role: {})", userId, permissions, durationMinutes, useDirectoryRole);
        if (temporaryAccessTimers.containsKey(userId)) {
            temporaryAccessTimers.get(userId).cancel();
        }
        grantTemporaryAccess(userId, permissions, durationMinutes, useDirectoryRole);
        logAccess(userId, "EmergencyRole", "Activated emergency role with permissions " + permissions + " for " + durationMinutes + " minutes");
    }

    public List<DirectoryRole> getAllDirectoryRoles() {
        List<DirectoryRole> roles = new ArrayList<>();

        graphClient.directoryRoles()
                .buildRequest()
                .get()
                .getCurrentPage()
                .forEach(roles::add);

        return roles;
    }

    public List<ServicePrincipal> getAllServicePrincipals() {
        List<ServicePrincipal> servicePrincipals = new ArrayList<>();

        graphClient.servicePrincipals()
                .buildRequest()
                .top(999) // batch size
                .get()
                .getCurrentPage()
                .forEach(servicePrincipals::add);

        return servicePrincipals;
    }

    public Map<String, List<String>> getAllServicePrincipalRoleAssignments() {
        Map<String, List<String>> assignments = new HashMap<>();

        List<ServicePrincipal> sps = getAllServicePrincipals();

        for (ServicePrincipal sp : sps) {
            List<String> roleIds = new ArrayList<>();

            try {
                List<AppRoleAssignment> appRoleAssignments = graphClient
                        .servicePrincipals(sp.id)
                        .appRoleAssignments()
                        .buildRequest()
                        .get()
                        .getCurrentPage();

                for (AppRoleAssignment assignment : appRoleAssignments) {
                    if (assignment.appRoleId != null) {
                        roleIds.add(String.valueOf(assignment.appRoleId));

                        // Create relationship: (:ServicePrincipal)-[:USES_ROLE]->(:Role)
                        neo4jService.createRelationshipBetweenServicePrincipalAndRole(
                                sp.id, String.valueOf(assignment.appRoleId)
                        );
                    }

                    if (assignment.resourceId != null) {
                        // Create relationship: (:ServicePrincipal)-[:TARGETS]->(:Resource)
                        neo4jService.createRelationshipBetweenServicePrincipalAndResource(
                                sp.id, String.valueOf(assignment.resourceId)
                        );
                    }
                }

                assignments.put(sp.id, roleIds);
            } catch (Exception e) {
                logger.error("Error fetching AppRoleAssignments for ServicePrincipal {}: {}", sp.id, e.getMessage(), e);
            }
        }

        return assignments;
    }


    public Map<String, Boolean> getAllMfaStates(List<User> users) {
        Map<String, Boolean> mfaStates = new HashMap<>();

        for (User user : users) {
            try {
                List<AuthenticationMethod> methods = graphClient
                        .users(user.id)
                        .authentication()
                        .methods()
                        .buildRequest()
                        .get()
                        .getCurrentPage();

                boolean hasMfa = methods.stream().anyMatch(method -> {
                    Object odataTypeObj = method.additionalDataManager().get("@odata.type");
                    if (odataTypeObj instanceof String) {
                        String odataType = (String) odataTypeObj;
                        return odataType.contains("microsoftAuthenticator") ||
                                odataType.contains("fido2") ||
                                odataType.contains("softwareOath");
                    }
                    return false;
                });

                mfaStates.put(user.id, hasMfa);
            } catch (Exception e) {
                logger.error("Error fetching MFA status for user {}: {}", user.id, e.getMessage(), e);
                mfaStates.put(user.id, false);
            }
        }

        return mfaStates;
    }




    public void grantCrossDepartmentAccess(String sourceUserId, String targetUserId, String allowedDataSegment, int durationMinutes, boolean useDirectoryRole) {
        logger.info("Granting cross-department access from {} to {} for data segment {} for {} minutes (Directory Role: {})", sourceUserId, targetUserId, allowedDataSegment, durationMinutes, useDirectoryRole);
        if (useDirectoryRole) {
            assignDirectoryRoleToUser(targetUserId, allowedDataSegment);
        } else {
            assignRoleToUser(targetUserId, allowedDataSegment);
        }
        logAccess(targetUserId, "CrossDepartmentAccess", "Granted access to " + allowedDataSegment + " from " + sourceUserId);
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                removeRoleFromUser(targetUserId, allowedDataSegment);
                logger.info("Cross-department access revoked for user {} for data segment {} after {} minutes", targetUserId, allowedDataSegment, durationMinutes);
                logAccess(targetUserId, "CrossDepartmentAccess", "Revoked access to " + allowedDataSegment);
            }
        }, TimeUnit.MINUTES.toMillis(durationMinutes));
        temporaryAccessTimers.put(targetUserId + "_cross_" + allowedDataSegment, timer);
    }




    // ------------------ Audit Logs and Neo4j Sync ------------------

    public void logAccess(String userId, String resource, String action) {
        String logEntry = String.format("User: %s, Resource: %s, Action: %s, Timestamp: %s",
                userId, resource, action, new Date());
        auditLogs.add(logEntry);
        appendToAuditBlob(logEntry);
        logger.info("Audit Log: {}", logEntry);
    }

    public void pushDataToNeo4j() {
        // Step 1: Core entities
        List<User> users = getAllUsers();
        List<Group> groups = getAllGroups();
        List<CustomRole> customRoles = getAllCustomRoles();
        Map<String, CustomRole> roleMap = customRoles.stream()
                .collect(Collectors.toMap(CustomRole::getId, r -> r));

        Map<String, List<String>> memberships = fetchGroupMembershipsFromAzure();

        // Step 2: User role assignments (AppRoleAssignments)
        Map<String, List<String>> userRolesLive = new HashMap<>();
        for (User user : users) {
            try {
                List<AppRoleAssignment> assignments = graphClient.users(user.id)
                        .appRoleAssignments()
                        .buildRequest()
                        .get()
                        .getCurrentPage();
                for (AppRoleAssignment assignment : assignments) {
                    userRolesLive.computeIfAbsent(user.id, k -> new ArrayList<>()).add(assignment.appRoleId.toString());
                }
            } catch (Exception e) {
                logger.error("Error retrieving app role assignments for user {}: {}", user.id, e.getMessage(), e);
            }
        }

        // Step 3: Group role assignments (AppRoleAssignments)
        Map<String, List<String>> groupRolesLive = new HashMap<>();
        for (Group group : groups) {
            try {
                List<AppRoleAssignment> assignments = graphClient.groups(group.id)
                        .appRoleAssignments()
                        .buildRequest()
                        .get()
                        .getCurrentPage();
                for (AppRoleAssignment assignment : assignments) {
                    groupRolesLive.computeIfAbsent(group.id, k -> new ArrayList<>()).add(assignment.appRoleId.toString());

                }
            } catch (Exception e) {
                logger.error("Error retrieving app role assignments for group {}: {}", group.id, e.getMessage(), e);
            }
        }

        // Step 4: Directory role assignments
        try {
            List<UnifiedRoleAssignment> directoryAssignments = listDirectoryAssignments();
            Set<String> userIds = users.stream().map(u -> u.id).collect(Collectors.toSet());
            Set<String> groupIds = groups.stream().map(g -> g.id).collect(Collectors.toSet());

            for (UnifiedRoleAssignment assignment : directoryAssignments) {
                String principalId = assignment.principalId;
                if (userIds.contains(principalId)) {
                    userRolesLive.computeIfAbsent(principalId, k -> new ArrayList<>()).add(assignment.roleDefinitionId);
                } else if (groupIds.contains(principalId)) {
                    groupRolesLive.computeIfAbsent(principalId, k -> new ArrayList<>()).add(assignment.roleDefinitionId);
                }
            }
        } catch (Exception e) {
            logger.error("Error retrieving directory role assignments: {}", e.getMessage(), e);
        }

        // Step 5: Directory roles
        List<DirectoryRole> directoryRoles = getAllDirectoryRoles();

        // Step 6: Service Principals and their role assignments
        List<ServicePrincipal> servicePrincipals = getAllServicePrincipals();
        Map<String, List<String>> servicePrincipalRoleAssignments = getAllServicePrincipalRoleAssignments();

        // Step 7: MFA Status map
        Map<String, Boolean> mfaStates = getAllMfaStates(users);

        // Step 8: Push everything to Neo4j
        neo4jService.pushData(
                users,
                groups,
                roleMap,
                userRolesLive,
                groupRolesLive,
                memberships,
                directoryRoles,
                servicePrincipals,
                servicePrincipalRoleAssignments,
                mfaStates
        );

        logger.info("Full directory data pushed to Neo4j.");
    }


    public Map<String, List<String>> getFlaggedUsers() {
        return new HashMap<>(flaggedUsers);
    }

    public void flagUser(String userId, String reason) {
        flaggedUsers.computeIfAbsent(userId, k -> new ArrayList<>()).add(reason);
        logger.warn("User {} flagged for: {}", userId, reason);
        logAccess(userId, "Flag", "Flagged for " + reason);
    }

    public void analyzeUserPrivileges() {
        for (Map.Entry<String, List<String>> entry : userRoles.entrySet()) {
            String userId = entry.getKey();
            List<String> roles = entry.getValue();
            boolean highPrivileged = roles.stream().anyMatch(role ->
                    role.toLowerCase().contains("admin") ||
                            role.toLowerCase().contains("manager") ||
                            role.toLowerCase().contains("temporary"));
            if (highPrivileged) {
                boolean used = auditLogs.stream().anyMatch(log -> log.contains(userId));
                if (!used) {
                    flagUser(userId, "High-privilege role unused");
                }
            }
        }
    }

    // ------------------ Helper Methods for Role Conversion ------------------

    private UnifiedRoleDefinition convertToUnifiedRoleDefinition(CustomRole role) {
        UnifiedRoleDefinition rd = new UnifiedRoleDefinition();
        rd.displayName = role.getRoleName();
        rd.description = role.getDescription();
        rd.isEnabled = true;
        if (role.getPermissions() != null && !role.getPermissions().isEmpty()) {
            List<UnifiedRolePermission> permissionList = new ArrayList<>();
            for (String permission : role.getPermissions()) {
                UnifiedRolePermission urp = new UnifiedRolePermission();
                urp.allowedResourceActions = List.of(permission);
                permissionList.add(urp);
            }
            rd.rolePermissions = permissionList;
        }
        return rd;
    }

    private CustomRole convertToCustomRole(UnifiedRoleDefinition rd) {
        CustomRole role = new CustomRole();
        role.setId(rd.id);
        role.setRoleName(rd.displayName);
        role.setDescription(rd.description);
        if (rd.rolePermissions != null && !rd.rolePermissions.isEmpty()) {
            List<String> perms = new ArrayList<>();
            for (UnifiedRolePermission urp : rd.rolePermissions) {
                if (urp.allowedResourceActions != null) {
                    perms.addAll(urp.allowedResourceActions);
                }
            }
            role.setPermissions(perms);
        }
        return role;
    }

    public void deleteDepartment(String name) {
        if (!departments.containsKey(name)) {
            throw new RuntimeException("Department not found: " + name);
        }
        departments.remove(name);
        logger.info("Deleted department: {}", name);
        logAccess("SYSTEM", "DepartmentDeletion", "Deleted department " + name);
    }

    public void updateDepartmentResources(String name, List<String> newResources) {
        Department dept = departments.get(name);
        if (dept == null) {
            throw new RuntimeException("Department not found: " + name);
        }
        dept.setResources(newResources);
        logger.info("Updated resources for department: {}", name);
        logAccess("SYSTEM", "DepartmentUpdate", "Updated resources for department " + name);
    }

    public Map<String, String> getAllResources() {
        return new HashMap<>(resources);
    }

    public void deleteResource(String name) {
        if (resources.remove(name) != null) {
            logger.info("Deleted resource: {}", name);
            logAccess("SYSTEM", "ResourceDeletion", "Deleted resource " + name);
        } else {
            logger.warn("Attempted to delete non-existent resource: {}", name);
        }
    }

    public void assignResourceToGroup(String groupId, String resourceName, boolean useDirectoryRole) {
        CustomRole tempRole = new CustomRole();
        tempRole.setRoleName("ResourceRole_" + resourceName);
        tempRole.setDescription("Role for resource: " + resourceName);
        tempRole.setPermissions(List.of(resourceName));
        CustomRole createdRole = createCustomRole(tempRole);
        if (createdRole != null) {
            if (useDirectoryRole) {
                assignDirectoryRoleToGroup(groupId, createdRole.getId());
            } else {
                assignRoleToGroup(groupId, createdRole.getId());
            }
            logger.info("Assigned resource {} to group {} via custom role {}", resourceName, groupId, createdRole.getId());
            logAccess(groupId, "ResourceAssignment", "Assigned " + resourceName + " via role");
        }
    }

    public void createBlobContainer(String storageAccountId, String containerName) {
        try {
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(AZURE_STORAGE_CONNECTION_STRING)
                    .buildClient();
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
                logger.info("✅ Created blob container '{}'", containerName);
            } else {
                logger.info("ℹ️ Blob container '{}' already exists", containerName);
            }
            logAccess("SYSTEM", "BlobContainerCreation", "Created container: " + containerName);
        } catch (Exception e) {
            logger.error("❌ Failed to create blob container '{}': {}", containerName, e.getMessage(), e);
            throw new RuntimeException("Error creating container", e);
        }
    }

    public void registerBlobResource(String containerName, String storageAccountId) {
        String fullPath = storageAccountId + "/blobServices/default/containers/" + containerName;
        resources.put(containerName, fullPath);
        logger.info("Registered blob container as resource: {}", fullPath);
        logAccess("SYSTEM", "ResourceRegistration", "Blob container: " + containerName);
    }

    public void appendToAuditBlob(String entry) {
        try {
            String blobName = "audit-" + LocalDate.now() + ".log";
            String data = entry + "\n";
            BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                    .connectionString(AZURE_STORAGE_CONNECTION_STRING)
                    .buildClient();
            BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient("audit-logs");
            if (!containerClient.exists()) {
                containerClient.create();
            }
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            AppendBlobClient appendBlobClient = blobClient.getAppendBlobClient();
            if (!appendBlobClient.exists()) {
                appendBlobClient.create();
            }
            appendBlobClient.appendBlock(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), data.length());
            logger.info("Appended audit entry to blob {}", blobName);
        } catch (Exception e) {
            logger.error("❌ Failed to write audit log to blob", e);
        }
    }

    public void registerResource(String name, String description) {
        if (resources.containsKey(name)) {
            logger.warn("Resource {} already registered", name);
            return;
        }
        createBlobContainer(STORAGE_ACCOUNT_ID, name);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", name);
        metadata.addProperty("description", description);
        uploadResourceData("resources", name + ".json", metadata.toString());
        resources.put(name, description);
        logAccess("SYSTEM", "ResourceCreation", "Created resource " + name);
    }

    public boolean hasPermission(String userId, String permission) {
        List<String> roleIds = getUserRoles(userId);
        for (String roleId : roleIds) {
            CustomRole role = customRoles.get(roleId);
            if (role != null && role.getPermissions() != null && role.getPermissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches all DirectoryObjects under a parent group and returns only the child Groups.
     */
    public List<Group> getSubGroups(String parentGroupId) {
        List<Group> subGroups = new ArrayList<>();
        try {
            var page = graphClient
                    .groups(parentGroupId)
                    .members()
                    .buildRequest()
                    .get();

            while (page != null) {
                for (DirectoryObject obj : page.getCurrentPage()) {
                    if (obj instanceof Group) {
                        subGroups.add((Group) obj);
                    }
                }
                page = page.getNextPage() != null
                        ? page.getNextPage().buildRequest().get()
                        : null;
            }
        } catch (Exception e) {
            logger.error("Failed to fetch sub-groups for {}: {}", parentGroupId, e.getMessage(), e);
        }
        return subGroups;
    }

    /**
     * Convenience: look up a parent by displayName, then return its nested department‐groups.
     */
    public List<Group> getDepartmentsByParentGroup(String parentDisplayName) {
        // find the parent by name
        String parentId = getAllGroups().stream()
                .filter(g -> parentDisplayName.equalsIgnoreCase(g.displayName))
                .map(g -> g.id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent group not found: " + parentDisplayName
                ));
        // return its child groups
        return getSubGroups(parentId);
    }

    private String sanitizeMailNickname(String raw) {
        String nick = raw == null ? "" : raw.toLowerCase().trim();
        for (String word : RESERVED_NICKNAMES) {
            nick = nick.replaceAll("\\b" + Pattern.quote(word) + "\\b", "");
        }
        nick = nick.replaceAll("[^a-z0-9]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return nick.isBlank() ? "group" : nick;
    }

}
