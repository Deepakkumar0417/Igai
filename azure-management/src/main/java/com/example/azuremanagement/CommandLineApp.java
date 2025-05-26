package com.example.azuremanagement;

import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.service.AzureGraphService;
import com.example.azuremanagement.service.AuditLogService;
import com.example.azuremanagement.service.DataImportService;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.PasswordProfile;
import com.microsoft.graph.models.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.google.gson.JsonPrimitive;

import java.time.LocalTime;
import java.util.List;
import java.util.Scanner;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandLineApp implements CommandLineRunner {

    @Autowired
    private AzureGraphService azureGraphService;

    @Autowired
    private DataImportService dataImportService;

    @Autowired
    private AuditLogService auditLogService;

    private final Scanner scanner = new Scanner(System.in);

    // Start continuous sync thread upon application startup
    private void startContinuousSync() {
        Thread syncThread = new Thread(() -> {
            while (true) {
                try {
                    azureGraphService.pushDataToNeo4j();
                    System.out.println("Continuous sync to Neo4j completed.");
                } catch (Exception e) {
                    System.out.println("Error during continuous sync: " + e.getMessage());
                }
                try {
                    // Sleep for 5 minutes (adjust interval as needed)
                    Thread.sleep(5 * 60 * 1000);
                } catch (InterruptedException ex) {
                    System.out.println("Sync thread interrupted.");
                    break;
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
    }

    @Override
    public void run(String... args) {
        // Start the continuous sync process in the background.
        startContinuousSync();

        while (true) {
            System.out.println("\nChoose an operation:");
            System.out.println("1. Create User");
            System.out.println("2. Create Group");
            System.out.println("3. List all Users & Groups");
            System.out.println("4. Add User to Group");
            System.out.println("5. Remove User from Group");
            System.out.println("6. Delete User");
            System.out.println("7. Delete Group");
            System.out.println("8. Create Custom Role");
            System.out.println("9. Assign Role to User");
            System.out.println("10. Assign Role to Group");
            System.out.println("11. List Roles for User");
            // Removed push data option from the menu, since sync is continuous.
            System.out.println("12. Download Audit Logs Now");
            System.out.println("13. Verify MFA");
            System.out.println("14. Grant Temporary Access");
            System.out.println("15. Delegate Access");
            System.out.println("16. Emergency Role Activation");
            System.out.println("17. Enforce Time-Based Access");
            System.out.println("18. Grant Cross-Department Access");
            System.out.println("19. Update User Role");
            System.out.println("20. Auto-Provision User");
            System.out.println("21. Analyze User Privileges");
            System.out.println("22. Show Flagged Users");
            System.out.println("23. Assign Directory Role to User");
            System.out.println("24. Assign Directory Role to Group");
            System.out.println("25. Import Demo Data from JSON");
            System.out.println("26. Exit");
            System.out.print("Enter choice: ");

            String input = scanner.nextLine().trim();
            int choice;
            try {
                choice = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("Invalid choice. Please try again.");
                continue;
            }

            switch (choice) {
                case 1:
                    createUser();
                    break;
                case 2:
                    createGroup();
                    break;
                case 3:
                    listAllUsersAndGroups();
                    break;
                case 4:
                    addUserToGroup();
                    break;
                case 5:
                    removeUserFromGroup();
                    break;
                case 6:
                    deleteUser();
                    break;
                case 7:
                    deleteGroup();
                    break;
                case 8:
                    createCustomRole();
                    break;
                case 9:
                    assignRoleToUser();
                    break;
                case 10:
                    assignRoleToGroup();
                    break;
                case 11:
                    listRolesForUser();
                    break;
                case 12:
                    downloadAuditLogs();
                    break;
                case 13:
                    verifyMFA();
                    break;
                case 14:
                    grantTemporaryAccess();
                    break;
                case 15:
                    delegateAccess();
                    break;
                case 16:
                    emergencyRoleActivation();
                    break;
                case 17:
                    enforceTimeBasedAccess();
                    break;
                case 18:
                    grantCrossDepartmentAccess();
                    break;
                case 19:
                    updateUserRole();
                    break;
                case 20:
                    autoProvisionUser();
                    break;
                case 21:
                    analyzeUserPrivileges();
                    break;
                case 22:
                    showFlaggedUsers();
                    break;
                case 23:
                    assignDirectoryRoleToUser();
                    break;
                case 24:
                    assignDirectoryRoleToGroup();
                    break;
                case 25:
                    System.out.print("Enter the file path for demo data JSON: ");
                    String filePath = scanner.nextLine();
                    dataImportService.importDemoData(filePath);
                    break;
                case 26:
                    System.out.println("Exiting...");
                    System.exit(0);
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    private void createUser() {
        try {
            System.out.print("Enter display name: ");
            String displayName = scanner.nextLine();
            System.out.print("Enter mail nickname: ");
            String mailNickname = scanner.nextLine();
            System.out.print("Enter user principal name (e.g., user@tenant.onmicrosoft.com): ");
            String userPrincipalName = scanner.nextLine();
            System.out.print("Enter password: ");
            String password = scanner.nextLine();

            User user = new User();
            user.accountEnabled = true;
            user.displayName = displayName;
            user.mailNickname = mailNickname;
            user.userPrincipalName = userPrincipalName;

            PasswordProfile passwordProfile = new PasswordProfile();
            passwordProfile.password = password;
            passwordProfile.forceChangePasswordNextSignIn = false;
            user.passwordProfile = passwordProfile;

            User createdUser = azureGraphService.createUser(user);
            System.out.println("User created successfully with id: " + createdUser.id);
        } catch (Exception e) {
            System.out.println("Error creating user: " + e.getMessage());
        }
    }

    private void createGroup() {
        try {
            System.out.print("Enter group display name: ");
            String displayName = scanner.nextLine();
            System.out.print("Enter mail nickname for group: ");
            String mailNickname = scanner.nextLine();

            Group group = new Group();
            group.displayName = displayName;
            group.mailNickname = mailNickname;
            group.mailEnabled = false;
            group.securityEnabled = true;

            System.out.print("Enable directory role assignment for this group? (yes/no): ");
            String assignableInput = scanner.nextLine().trim();
            if (assignableInput.equalsIgnoreCase("yes") || assignableInput.equalsIgnoreCase("y")) {
                group.additionalDataManager().put("isAssignableToRole", new JsonPrimitive(true));
            }

            Group createdGroup = azureGraphService.createGroup(group);
            System.out.println("Group created successfully with id: " + createdGroup.id);
        } catch (Exception e) {
            System.out.println("Error creating group: " + e.getMessage());
        }
    }

    private void listAllUsersAndGroups() {
        try {
            List<User> users = azureGraphService.getAllUsers();
            List<Group> groups = azureGraphService.getAllGroups();

            System.out.println("\nUsers:");
            if (users.isEmpty()) {
                System.out.println("  No users found.");
            } else {
                for (User u : users) {
                    System.out.println("  " + u.displayName + " (id: " + u.id + ")");
                }
            }

            System.out.println("\nGroups:");
            if (groups.isEmpty()) {
                System.out.println("  No groups found.");
            } else {
                for (Group g : groups) {
                    System.out.println("  " + g.displayName + " (id: " + g.id + ")");
                }
            }
        } catch (Exception e) {
            System.out.println("Error fetching relations: " + e.getMessage());
        }
    }

    private void addUserToGroup() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            String groupId = selectGroup();
            if (groupId == null) return;
            azureGraphService.addUserToGroup(groupId, userId);
            System.out.println("User added to group successfully!");
        } catch (Exception e) {
            System.out.println("Error adding user to group: " + e.getMessage());
        }
    }

    private void removeUserFromGroup() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            String groupId = selectGroup();
            if (groupId == null) return;
            azureGraphService.removeUserFromGroup(groupId, userId);
            System.out.println("User removed from group successfully!");
        } catch (Exception e) {
            System.out.println("Error removing user from group: " + e.getMessage());
        }
    }

    private void deleteUser() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            azureGraphService.deleteUser(userId);
            System.out.println("User deleted successfully!");
        } catch (Exception e) {
            System.out.println("Error deleting user: " + e.getMessage());
        }
    }

    private void deleteGroup() {
        try {
            String groupId = selectGroup();
            if (groupId == null) return;
            azureGraphService.deleteGroup(groupId);
            System.out.println("Group deleted successfully!");
        } catch (Exception e) {
            System.out.println("Error deleting group: " + e.getMessage());
        }
    }

    private void createCustomRole() {
        try {
            System.out.print("Enter custom role name: ");
            String roleName = scanner.nextLine();
            System.out.print("Enter role description: ");
            String description = scanner.nextLine();
            System.out.print("Enter permissions (comma separated): ");
            String perms = scanner.nextLine();
            String[] permissionsArray = perms.split("\\s*,\\s*");

            CustomRole role = new CustomRole();
            role.setRoleName(roleName);
            role.setDescription(description);
            role.setPermissions(List.of(permissionsArray));

            CustomRole createdRole = azureGraphService.createCustomRole(role);
            System.out.println("Custom role created successfully with id: " + createdRole.getId());
        } catch (Exception e) {
            System.out.println("Error creating custom role: " + e.getMessage());
        }
    }

    private void assignRoleToUser() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter role id to assign (custom or built-in): ");
            String roleId = scanner.nextLine();
            azureGraphService.assignRoleToUser(userId, roleId);
            System.out.println("Role assigned to user successfully!");
        } catch (Exception e) {
            System.out.println("Error assigning role to user: " + e.getMessage());
        }
    }

    private void assignRoleToGroup() {
        try {
            String groupId = selectGroup();
            if (groupId == null) return;
            System.out.print("Enter role id to assign to group: ");
            String roleId = scanner.nextLine();
            azureGraphService.assignRoleToGroup(groupId, roleId);
            System.out.println("Role assigned to group successfully!");
        } catch (Exception e) {
            System.out.println("Error assigning role to group: " + e.getMessage());
        }
    }

    private void listRolesForUser() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            List<String> roles = azureGraphService.getUserRoles(userId);
            System.out.println("Roles for user:");
            if (roles.isEmpty()) {
                System.out.println("  No roles assigned.");
            } else {
                for (String role : roles) {
                    System.out.println("  " + role);
                }
            }
        } catch (Exception e) {
            System.out.println("Error listing roles for user: " + e.getMessage());
        }
    }

    private void pushDataToNeo4j() {
        // Continuous sync is running in background; no manual push needed.
        System.out.println("Continuous sync to Neo4j is active in the background.");
    }

    private void downloadAuditLogs() {
        try {
            auditLogService.downloadAuditLogs();
            System.out.println("Audit logs downloaded successfully!");
        } catch (Exception e) {
            System.out.println("Error downloading audit logs: " + e.getMessage());
        }
    }

    private void verifyMFA() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter the action for MFA verification: ");
            String action = scanner.nextLine();
            boolean result = azureGraphService.verifyMFA(userId, action);
            System.out.println("MFA verification " + (result ? "succeeded" : "failed") + " for user " + userId);
        } catch (Exception e) {
            System.out.println("Error verifying MFA: " + e.getMessage());
        }
    }

    // Option 15: Grant Temporary Access (with directory role flag)
    private void grantTemporaryAccess() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter duration (in minutes): ");
            int duration = Integer.parseInt(scanner.nextLine());
            System.out.print("Enter permissions (comma separated, valid GUIDs for directory roles): ");
            String perms = scanner.nextLine();
            List<String> permissions = List.of(perms.split("\\s*,\\s*"));
            // Change the flag to 'true' if you want to use directory roles.
            azureGraphService.grantTemporaryAccess(userId, permissions, duration, true);
            System.out.println("Temporary access granted for user " + userId);
        } catch (Exception e) {
            System.out.println("Error granting temporary access: " + e.getMessage());
        }
    }

    // Option 16: Delegate Access (with directory role flag)
    private void delegateAccess() {
        try {
            System.out.println("Select the user to delegate FROM:");
            String fromUserId = selectUser();
            if (fromUserId == null) return;
            System.out.println("Select the user to delegate TO:");
            String toUserId = selectUser();
            if (toUserId == null) return;
            System.out.print("Enter duration (in minutes): ");
            int duration = Integer.parseInt(scanner.nextLine());
            System.out.print("Enter allowed permissions (comma separated, valid GUIDs): ");
            String perms = scanner.nextLine();
            List<String> allowedPermissions = List.of(perms.split("\\s*,\\s*"));
            // Set flag to 'true' to use directory roles.
            azureGraphService.delegateTemporaryAccess(fromUserId, toUserId, allowedPermissions, duration, true);
            System.out.println("Delegated access granted from " + fromUserId + " to " + toUserId);
        } catch (Exception e) {
            System.out.println("Error delegating access: " + e.getMessage());
        }
    }

    // Option 17: Emergency Role Activation (with directory role flag)
    private void emergencyRoleActivation() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter duration (in minutes): ");
            int duration = Integer.parseInt(scanner.nextLine());
            System.out.print("Enter permissions (comma separated, valid GUIDs): ");
            String perms = scanner.nextLine();
            List<String> permissions = List.of(perms.split("\\s*,\\s*"));
            // Set flag to 'true' to use directory roles.
            azureGraphService.emergencyRoleActivation(userId, permissions, duration, true);
            System.out.println("Emergency role activated for user " + userId);
        } catch (Exception e) {
            System.out.println("Error activating emergency role: " + e.getMessage());
        }
    }

    // Option 18: Enforce Time-Based Access (unchanged)
    private void enforceTimeBasedAccess() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter allowed start time (HH:mm): ");
            String start = scanner.nextLine();
            System.out.print("Enter allowed end time (HH:mm): ");
            String end = scanner.nextLine();
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            boolean accessGranted = azureGraphService.enforceTimeBasedAccess(userId, startTime, endTime);
            System.out.println("Time-based access " + (accessGranted ? "granted" : "denied") + " for user " + userId);
        } catch (Exception e) {
            System.out.println("Error enforcing time-based access: " + e.getMessage());
        }
    }

    // Option 19: Grant Cross-Department Access (with directory role flag)
    private void grantCrossDepartmentAccess() {
        try {
            System.out.println("Select the source user:");
            String sourceUserId = selectUser();
            if (sourceUserId == null) return;
            System.out.println("Select the target user:");
            String targetUserId = selectUser();
            if (targetUserId == null) return;
            System.out.print("Enter allowed data segment (must be a valid directory role GUID): ");
            String allowedDataSegment = scanner.nextLine();
            System.out.print("Enter duration (in minutes): ");
            int duration = Integer.parseInt(scanner.nextLine());
            // Set flag to 'true' to use directory roles.
            azureGraphService.grantCrossDepartmentAccess(sourceUserId, targetUserId, allowedDataSegment, duration, true);
            System.out.println("Cross-department access granted from " + sourceUserId + " to " + targetUserId);
        } catch (Exception e) {
            System.out.println("Error granting cross-department access: " + e.getMessage());
        }
    }

    // Option 20: Update User Role (with directory role flag)
    private void updateUserRole() {
        try {
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter new role id: ");
            String newRoleId = scanner.nextLine();
            // Pass false to use app roles; set to true to use directory roles.
            azureGraphService.updateUserRole(userId, newRoleId, true);
            System.out.println("User role updated for user " + userId);
        } catch (Exception e) {
            System.out.println("Error updating user role: " + e.getMessage());
        }
    }

    // Option 21: Auto-Provision User (with directory role flag)
    private void autoProvisionUser() {
        try {
            System.out.print("Enter display name: ");
            String displayName = scanner.nextLine();
            System.out.print("Enter mail nickname: ");
            String mailNickname = scanner.nextLine();
            System.out.print("Enter user principal name (e.g., user@tenant.onmicrosoft.com): ");
            String userPrincipalName = scanner.nextLine();
            System.out.print("Enter password: ");
            String password = scanner.nextLine();
            System.out.print("Enter default role id: ");
            String defaultRoleId = scanner.nextLine();

            User user = new User();
            user.accountEnabled = true;
            user.displayName = displayName;
            user.mailNickname = mailNickname;
            user.userPrincipalName = userPrincipalName;

            PasswordProfile passwordProfile = new PasswordProfile();
            passwordProfile.password = password;
            passwordProfile.forceChangePasswordNextSignIn = false;
            user.passwordProfile = passwordProfile;

            // Pass false to use app roles; set to true to use directory roles.
            azureGraphService.autoProvisionUser(user, defaultRoleId, true);
            System.out.println("User auto-provisioned with default role " + defaultRoleId);
        } catch (Exception e) {
            System.out.println("Error auto-provisioning user: " + e.getMessage());
        }
    }

    // Option 22: Analyze User Privileges (unchanged)
    private void analyzeUserPrivileges() {
        try {
            azureGraphService.analyzeUserPrivileges();
            System.out.println("User privileges analyzed successfully.");
        } catch (Exception e) {
            System.out.println("Error analyzing user privileges: " + e.getMessage());
        }
    }

    // Option 23: Show Flagged Users (unchanged)
    private void showFlaggedUsers() {
        try {
            Map<String, List<String>> flagged = azureGraphService.getFlaggedUsers();
            if (flagged.isEmpty()) {
                System.out.println("No flagged users found.");
            } else {
                System.out.println("Flagged Users:");
                flagged.forEach((userId, reasons) -> {
                    System.out.println("User ID: " + userId);
                    for (String reason : reasons) {
                        System.out.println("  Reason: " + reason);
                    }
                });
            }
        } catch (Exception e) {
            System.out.println("Error retrieving flagged users: " + e.getMessage());
        }
    }

    private String selectUser() {
        List<User> users = azureGraphService.getAllUsers();
        if (users.isEmpty()) {
            System.out.println("No users available.");
            return null;
        }

        System.out.println("\nSelect a User:");
        for (int i = 0; i < users.size(); i++) {
            System.out.println((i + 1) + ". " + users.get(i).displayName + " (id: " + users.get(i).id + ")");
        }
        System.out.print("Enter user number (or 0 to cancel): ");
        String input = scanner.nextLine().trim();
        int index;
        try {
            index = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            System.out.println("Invalid number.");
            return null;
        }

        if (index == 0 || index < 1 || index > users.size()) {
            System.out.println("Invalid selection.");
            return null;
        }

        return users.get(index - 1).id;
    }

    private String selectGroup() {
        List<Group> groups = azureGraphService.getAllGroups();
        if (groups.isEmpty()) {
            System.out.println("No groups available.");
            return null;
        }

        System.out.println("\nSelect a Group:");
        for (int i = 0; i < groups.size(); i++) {
            System.out.println((i + 1) + ". " + groups.get(i).displayName + " (id: " + groups.get(i).id + ")");
        }
        System.out.print("Enter group number (or 0 to cancel): ");
        String input = scanner.nextLine().trim();
        int index;
        try {
            index = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            System.out.println("Invalid number.");
            return null;
        }

        if (index == 0 || index < 1 || index > groups.size()) {
            System.out.println("Invalid selection.");
            return null;
        }

        return groups.get(index - 1).id;
    }

    private void assignDirectoryRoleToUser() {
        try {
            System.out.println("Select the user for directory role assignment:");
            String userId = selectUser();
            if (userId == null) return;
            System.out.print("Enter directory role id to assign: ");
            String roleId = scanner.nextLine();
            azureGraphService.assignDirectoryRoleToUser(userId, roleId);
            System.out.println("Directory role assigned to user successfully!");
        } catch (Exception e) {
            System.out.println("Error assigning directory role to user: " + e.getMessage());
        }
    }

    private void assignDirectoryRoleToGroup() {
        try {
            System.out.println("Select the group for directory role assignment:");
            String groupId = selectGroup();
            if (groupId == null) return;
            System.out.print("Enter directory role id to assign: ");
            String roleId = scanner.nextLine();
            azureGraphService.assignDirectoryRoleToGroup(groupId, roleId);
            System.out.println("Directory role assigned to group successfully!");
        } catch (Exception e) {
            System.out.println("Error assigning directory role to group: " + e.getMessage());
        }
    }
}
