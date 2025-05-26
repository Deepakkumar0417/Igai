package com.example.azuremanagement.model;

import java.util.List;

public class CustomRole {
    private String id;
    private String roleName;
    private String description;
    private List<String> permissions;

    public CustomRole() {
    }

    public CustomRole(String roleName, String description, List<String> permissions) {
        this.roleName = roleName;
        this.description = description;
        this.permissions = permissions;
    }

    // Getters and setters for id, roleName, description, permissions

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }
}
