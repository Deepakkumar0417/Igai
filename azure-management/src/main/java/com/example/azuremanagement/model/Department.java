// src/main/java/com/example/azuremanagement/model/Department.java
package com.example.azuremanagement.model;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class Department {

    /** A unique identifier for the department. */
    private String id;

    /** The department name (e.g. "Cardiology"). */
    @NotBlank(message = "Department name must not be blank")
    private String name;

    /** A brief description of the department. */
    private String description;

    /**
     * The list of resource permission names associated with this department
     * (e.g. ["EHR_READ", "MRI_READ"]).
     */
    @NotNull(message = "Resources list must not be null")
    private List<@NotBlank(message = "Resource name must not be blank") String> resources;

    /**
     * The name of the Azure Blob Storage container where this
     * department's resource files live.
     */
    @NotBlank(message = "Container name must not be blank")
    private String containerName;

    /**
     * The Azure AD parent group under which this department
     * should be nested (e.g. "Physicians", "Nurses", etc.).
     * This maps to your existing JSON field `"group"`.
     */
    @NotBlank(message = "Group must not be blank")
    private String group;

    /** Default constructor auto-generates a unique ID. */
    public Department() {
        this.id = UUID.randomUUID().toString();
    }

    /**
     * Full constructor.
     *
     * @param name           Department name.
     * @param description    Department description.
     * @param resources      List of resource permission names.
     * @param containerName  Blob container for department resources.
     * @param group          Parent Azure AD group name.
     */
    public Department(String name,
                      String description,
                      List<String> resources,
                      String containerName,
                      String group) {
        this.id            = UUID.randomUUID().toString();
        this.name          = name;
        this.description   = description;
        this.resources     = resources;
        this.containerName = containerName;
        this.group         = group;
    }

    // ─── Getters & Setters ─────────────────────────────────────

    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getResources() {
        return resources;
    }
    public void setResources(List<String> resources) {
        this.resources = resources;
    }

    public String getContainerName() {
        return containerName;
    }
    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getGroup() {
        return group;
    }
    public void setGroup(String group) {
        this.group = group;
    }

    @Override
    public String toString() {
        return String.format(
                "Department{id='%s', name='%s', description='%s', resources=%s, containerName='%s', group='%s'}",
                id, name, description, resources, containerName, group
        );
    }
}
