package com.example.azuremanagement.controller;

import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.model.Department;
import com.example.azuremanagement.service.AzureGraphService;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@CrossOrigin
@RestController
@RequestMapping("/api")
public class AzureController {

    private static final Logger logger = LoggerFactory.getLogger(AzureController.class);
    private final AzureGraphService azureGraphService;

    public AzureController(AzureGraphService azureGraphService) {
        this.azureGraphService = azureGraphService;
    }

    // ------------------ Basic User Endpoints ------------------

    @PostMapping("/user")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        try {
            User createdUser = azureGraphService.createUser(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
        } catch (Exception e) {
            logger.error("Failed to create user", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/user/details")
    public ResponseEntity<?> getUserDetails(@RequestParam String userId) {
        try {
            User user = azureGraphService.getUserDetails(userId);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            logger.error("Failed to fetch user details", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/user/roles")
    public ResponseEntity<?> getUserRoles(@RequestParam String userId) {
        try {
            List<String> roles = azureGraphService.getUserRoles(userId);
            return ResponseEntity.ok(roles);
        } catch (Exception e) {
            logger.error("Failed to fetch user roles", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/user")
    public ResponseEntity<?> deleteUser(@RequestParam String userId) {
        try {
            azureGraphService.deleteUser(userId);
            return ResponseEntity.ok("User deleted successfully.");
        } catch (Exception e) {
            logger.error("Failed to delete user", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Basic Group Endpoints ------------------

    @PostMapping("/group")
    public ResponseEntity<?> createGroup(@RequestBody Group group) {
        try {
            Group createdGroup = azureGraphService.createGroup(group);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdGroup);
        } catch (Exception e) {
            logger.error("Failed to create group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/group/details")
    public ResponseEntity<?> getGroupDetails(@RequestParam String groupId) {
        try {
            Group group = azureGraphService.getGroupDetails(groupId);
            return ResponseEntity.ok(group);
        } catch (Exception e) {
            logger.error("Failed to fetch group details", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/relations")
    public ResponseEntity<?> getRelations() {
        try {
            List<User> users = azureGraphService.getAllUsers();
            List<Group> groups = azureGraphService.getAllGroups();
            Map<String, Object> resp = new HashMap<>();
            resp.put("allUsers", users);
            resp.put("allGroups", groups);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            logger.error("Failed to fetch relations", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/addUserToGroup")
    public ResponseEntity<?> addUserToGroup(@RequestParam String groupId, @RequestParam String userId) {
        try {
            azureGraphService.addUserToGroup(groupId, userId);
            return ResponseEntity.ok("User added to group successfully.");
        } catch (Exception e) {
            logger.error("Failed to add user to group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/removeUserFromGroup")
    public ResponseEntity<?> removeUserFromGroup(@RequestParam String groupId, @RequestParam String userId) {
        try {
            azureGraphService.removeUserFromGroup(groupId, userId);
            return ResponseEntity.ok("User removed from group successfully.");
        } catch (Exception e) {
            logger.error("Failed to remove user from group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/group")
    public ResponseEntity<?> deleteGroup(@RequestParam String groupId) {
        try {
            azureGraphService.deleteGroup(groupId);
            return ResponseEntity.ok("Group deleted successfully.");
        } catch (Exception e) {
            logger.error("Failed to delete group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Custom Role Endpoints ------------------

    @PostMapping("/customRole")
    public ResponseEntity<?> createCustomRole(@RequestBody CustomRole role) {
        try {
            CustomRole created = azureGraphService.createCustomRole(role);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            logger.error("Failed to create custom role", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/customRole/{roleId}")
    public ResponseEntity<?> getCustomRole(@PathVariable String roleId) {
        try {
            CustomRole role = azureGraphService.getCustomRole(roleId);
            return ResponseEntity.ok(role);
        } catch (Exception e) {
            logger.error("Failed to fetch custom role", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/customRoles")
    public ResponseEntity<?> getAllCustomRoles() {
        try {
            List<CustomRole> roles = azureGraphService.getAllCustomRoles();
            return ResponseEntity.ok(roles);
        } catch (Exception e) {
            logger.error("Failed to fetch all custom roles", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/customRole")
    public ResponseEntity<?> deleteCustomRole(@RequestParam String roleId) {
        try {
            azureGraphService.deleteCustomRole(roleId);
            return ResponseEntity.ok("Custom role deleted successfully.");
        } catch (Exception e) {
            logger.error("Failed to delete custom role", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Role Assignment Endpoints ------------------

    @PostMapping("/assignDirectoryRoleToUser")
    public ResponseEntity<?> assignDirectoryRoleToUser(@RequestParam String userId,
                                                       @RequestParam String roleDefinitionId) {
        try {
            azureGraphService.assignDirectoryRoleToUser(userId, roleDefinitionId);
            return ResponseEntity.ok("Directory role assigned to user successfully.");
        } catch (Exception e) {
            logger.error("Failed to assign directory role to user", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/assignDirectoryRoleToGroup")
    public ResponseEntity<?> assignDirectoryRoleToGroup(@RequestParam String groupId,
                                                        @RequestParam String roleDefinitionId) {
        try {
            azureGraphService.assignDirectoryRoleToGroup(groupId, roleDefinitionId);
            return ResponseEntity.ok("Directory role assigned to group successfully.");
        } catch (Exception e) {
            logger.error("Failed to assign directory role to group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/removeRoleFromUser")
    public ResponseEntity<?> removeRoleFromUser(@RequestParam String userId, @RequestParam String roleId) {
        try {
            azureGraphService.removeRoleFromUser(userId, roleId);
            return ResponseEntity.ok("Role removed from user successfully.");
        } catch (Exception e) {
            logger.error("Failed to remove role from user", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/assignDirectoryRoleToGroupAsRole")
    public ResponseEntity<?> assignRoleToGroup(@RequestParam String groupId, @RequestParam String roleId) {
        try {
            azureGraphService.assignDirectoryRoleToGroup(groupId, roleId);
            return ResponseEntity.ok("Directory role assigned to group successfully.");
        } catch (Exception e) {
            logger.error("Failed to assign role to group", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Neo4j Sync Endpoint ------------------

    @PostMapping("/pushToNeo4j")
    public ResponseEntity<?> pushToNeo4j() {
        try {
            azureGraphService.pushDataToNeo4j();
            return ResponseEntity.ok("Data pushed to Neo4j successfully.");
        } catch (Exception e) {
            logger.error("Failed to push data to Neo4j", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ MFA Endpoint ------------------

    @PostMapping("/mfa/verify")
    public ResponseEntity<?> verifyMFA(@RequestParam String userId, @RequestParam String action) {
        try {
            boolean ok = azureGraphService.verifyMFA(userId, action);
            return ResponseEntity.ok("MFA " + (ok ? "succeeded" : "failed") + " for user " + userId);
        } catch (Exception e) {
            logger.error("Failed to verify MFA", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Advanced Access Endpoints ------------------

    @PostMapping("/access/temporary")
    public ResponseEntity<?> grantTemporaryAccess(@RequestParam String userId,
                                                  @RequestParam int durationMinutes,
                                                  @RequestBody List<String> permissions) {
        try {
            azureGraphService.grantTemporaryAccess(userId, permissions, durationMinutes, true);
            return ResponseEntity.ok("Temporary access granted.");
        } catch (Exception e) {
            logger.error("Failed to grant temporary access", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/access/delegate")
    public ResponseEntity<?> delegateAccess(@RequestParam String fromUserId,
                                            @RequestParam String toUserId,
                                            @RequestParam int durationMinutes,
                                            @RequestBody List<String> allowedPermissions) {
        try {
            azureGraphService.delegateTemporaryAccess(fromUserId, toUserId, allowedPermissions, durationMinutes, true);
            return ResponseEntity.ok("Delegated access granted.");
        } catch (Exception e) {
            logger.error("Failed to delegate access", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/access/emergency")
    public ResponseEntity<?> emergencyRoleActivation(@RequestParam String userId,
                                                     @RequestParam int durationMinutes,
                                                     @RequestBody List<String> permissions) {
        try {
            azureGraphService.emergencyRoleActivation(userId, permissions, durationMinutes, true);
            return ResponseEntity.ok("Emergency role activated.");
        } catch (Exception e) {
            logger.error("Failed to activate emergency role", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/access/time/enforce")
    public ResponseEntity<?> enforceTimeBasedAccess(@RequestParam String userId,
                                                    @RequestParam String start,
                                                    @RequestParam String end) {
        try {
            LocalTime s = LocalTime.parse(start);
            LocalTime e = LocalTime.parse(end);
            boolean ok = azureGraphService.enforceTimeBasedAccess(userId, s, e);
            return ResponseEntity.ok("Time-based access " + (ok ? "granted" : "denied") + ".");
        } catch (Exception e) {
            logger.error("Failed to enforce time-based access", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/access/crossDepartment")
    public ResponseEntity<?> grantCrossDepartmentAccess(@RequestParam String sourceUserId,
                                                        @RequestParam String targetUserId,
                                                        @RequestParam String allowedDataSegment,
                                                        @RequestParam int durationMinutes) {
        try {
            azureGraphService.grantCrossDepartmentAccess(sourceUserId, targetUserId, allowedDataSegment, durationMinutes, true);
            return ResponseEntity.ok("Cross-department access granted.");
        } catch (Exception e) {
            logger.error("Failed to grant cross-department access", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/user/updateRole")
    public ResponseEntity<?> updateUserRole(@RequestParam String userId, @RequestParam String newRoleId) {
        try {
            azureGraphService.updateUserRole(userId, newRoleId, true);
            return ResponseEntity.ok("User role updated.");
        } catch (Exception e) {
            logger.error("Failed to update user role", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/user/autoProvision")
    public ResponseEntity<?> autoProvisionUser(@RequestBody User user,
                                               @RequestParam String defaultRoleId) {
        try {
            azureGraphService.autoProvisionUser(user, defaultRoleId, true);
            return ResponseEntity.ok("User auto-provisioned.");
        } catch (Exception e) {
            logger.error("Failed to auto-provision user", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/access/analyzePrivileges")
    public ResponseEntity<?> analyzePrivileges() {
        try {
            azureGraphService.analyzeUserPrivileges();
            return ResponseEntity.ok("User privileges analyzed.");
        } catch (Exception e) {
            logger.error("Failed to analyze user privileges", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/flaggedUsers")
    public ResponseEntity<?> getFlaggedUsers() {
        try {
            return ResponseEntity.ok(azureGraphService.getFlaggedUsers());
        } catch (Exception e) {
            logger.error("Failed to fetch flagged users", e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ------------------ Department Endpoints ------------------

    @PostMapping("/departments")
    public ResponseEntity<String> createDepartment(@RequestBody Department dept) {
        String parent = dept.getGroup();
        if (parent == null || parent.isBlank()) {
            return ResponseEntity
                    .badRequest()
                    .body("JSON payload must include a non-blank 'group' field");
        }
        azureGraphService.createDepartment(
                dept.getName(),
                dept.getDescription(),
                dept.getResources(),
                parent
        );
        return ResponseEntity.ok(
                "Department '" + dept.getName() + "' nested under '" + parent + "'."
        );
    }

    @PostMapping("/departments/createGroup")
    public ResponseEntity<Group> createDepartmentGroup(@RequestParam String deptName,
                                                       @RequestParam String description) {
        Group g = azureGraphService.createDepartmentGroup(deptName, description);
        return ResponseEntity.ok(g);
    }

    @GetMapping("/departments")
    public ResponseEntity<List<Department>> getAllDepartments() {
        return ResponseEntity.ok(azureGraphService.getAllDepartments());
    }

    @GetMapping("/departments/{name}")
    public ResponseEntity<Department> getDepartment(@PathVariable String name) {
        return ResponseEntity.ok(azureGraphService.getDepartment(name));
    }

    @GetMapping("/departments/{name}/users")
    public ResponseEntity<List<User>> getUsersByDepartment(@PathVariable String name) {
        return ResponseEntity.ok(azureGraphService.getUsersByDepartment(name));
    }

    @GetMapping("/departments/{name}/resources")
    public ResponseEntity<List<String>> getResourcesForDepartment(@PathVariable String name) {
        return ResponseEntity.ok(azureGraphService.getResourcesForDepartment(name));
    }

    @PostMapping("/departments/assignAccess")
    public ResponseEntity<String> assignBlobAccess(@RequestParam String departmentName,
                                                   @RequestParam String groupId,
                                                   @RequestParam String storageAccountId) {
        azureGraphService.assignDepartmentBlobAccess(departmentName, groupId, storageAccountId);
        return ResponseEntity.ok("Blob access assigned to department resources.");
    }

    // ------------------ Sub-Group / Nested Department Endpoints ------------------

    @GetMapping("/groups/{groupId}/subGroups")
    public ResponseEntity<List<Group>> listSubGroups(@PathVariable String groupId) {
        List<Group> subs = azureGraphService.getSubGroups(groupId);
        return ResponseEntity.ok(subs);
    }

    @GetMapping("/groups/{parentName}/departments")
    public ResponseEntity<List<Department>> listDepartmentsByGroup(
            @PathVariable String parentName) {
        List<Group> groups = azureGraphService.getDepartmentsByParentGroup(parentName);
        List<Department> depts = groups.stream()
                .map(g -> azureGraphService.getDepartment(g.displayName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return ResponseEntity.ok(depts);
    }
}
