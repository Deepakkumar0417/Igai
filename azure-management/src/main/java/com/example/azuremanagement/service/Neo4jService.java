package com.example.azuremanagement.service;

import com.example.azuremanagement.model.CustomRole;
import com.example.azuremanagement.model.Department;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.graph.models.Group;
import com.microsoft.graph.models.User;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

@Service
public class Neo4jService {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jService.class);
    private final Driver driver;
    private final AzureGraphService azureGraphService;

    public Neo4jService(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password,
            AzureGraphService azureGraphService
    ) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        this.azureGraphService = azureGraphService;
    }

    /**
     * Push users, groups, custom roles, departments, and their relationships to Neo4j.
     */
    public void pushData(
            List<User> users,
            List<Group> groups,
            Map<String, CustomRole> customRoles,
            Map<String, List<String>> userRoles,
            Map<String, List<String>> groupRoles,
            Map<String, List<String>> groupMemberships
    ) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                // ── Users ───────────────────────────────────────────────────────
                users.forEach(u ->
                        tx.run(
                                "MERGE (u:User {id:$id}) " +
                                        "SET u.displayName = $displayName, u.department = $department",
                                Map.of(
                                        "id",           u.id,
                                        "displayName",  u.displayName,
                                        "department",   u.department != null ? u.department : ""
                                )
                        )
                );

                // ── Groups ──────────────────────────────────────────────────────
                groups.forEach(g ->
                        tx.run(
                                "MERGE (g:Group {id:$id}) SET g.displayName = $displayName",
                                Map.of("id", g.id, "displayName", g.displayName)
                        )
                );

                // ── Custom Roles ───────────────────────────────────────────────
                customRoles.values().forEach(r ->
                        tx.run(
                                "MERGE (r:Role {id:$id}) " +
                                        "SET r.roleName = $roleName, r.description = $description",
                                Map.of(
                                        "id",          r.getId(),
                                        "roleName",    r.getRoleName(),
                                        "description", r.getDescription()
                                )
                        )
                );

                // ── User HAS_ROLE Role ────────────────────────────────────────
                userRoles.forEach((userId, rolesList) ->
                        rolesList.forEach(roleId ->
                                tx.run(
                                        "MATCH (u:User {id:$userId}), (r:Role {id:$roleId}) " +
                                                "MERGE (u)-[:HAS_ROLE]->(r)",
                                        Map.of("userId", userId, "roleId", roleId)
                                )
                        )
                );

                // ── Group HAS_ROLE Role ───────────────────────────────────────
                groupRoles.forEach((groupId, rolesList) ->
                        rolesList.forEach(roleId ->
                                tx.run(
                                        "MATCH (g:Group {id:$groupId}), (r:Role {id:$roleId}) " +
                                                "MERGE (g)-[:HAS_ROLE]->(r)",
                                        Map.of("groupId", groupId, "roleId", roleId)
                                )
                        )
                );

                // ── User MEMBER_OF Group ──────────────────────────────────────
                groupMemberships.forEach((groupId, members) ->
                        members.forEach(userId ->
                                tx.run(
                                        "MATCH (u:User {id:$userId}), (g:Group {id:$groupId}) " +
                                                "MERGE (u)-[:MEMBER_OF]->(g)",
                                        Map.of("userId", userId, "groupId", groupId)
                                )
                        )
                );

                // ── Departments ────────────────────────────────────────────────
                List<Department> depts = azureGraphService.getAllDepartments();
                depts.forEach(dept -> {
                    String deptName = dept.getName().trim();
                    tx.run(
                            "MERGE (d:Department {name:$name}) " +
                                    "  SET d.description = $description, d.resources = $resources",
                            Map.of(
                                    "name",        deptName,
                                    "description", dept.getDescription(),
                                    "resources",   String.join(",", dept.getResources())
                            )
                    );
                });

                // ── User BELONGS_TO Department ────────────────────────────────
                users.stream()
                        .filter(u -> u.department != null && !u.department.isBlank())
                        .forEach(u -> {
                            String deptName = u.department.trim();
                            tx.run(
                                    "MATCH (u:User {id:$userId}), (d:Department {name:$deptName}) " +
                                            "MERGE (u)-[:BELONGS_TO]->(d)",
                                    Map.of(
                                            "userId",   u.id,
                                            "deptName", deptName
                                    )
                            );
                        });

                // ── Department HAS_AD_GROUP ───────────────────────────────────
                depts.forEach(dept -> {
                    String deptName = dept.getName().trim();
                    tx.run(
                            "MATCH (d:Department {name:$deptName}), (g:Group {displayName:$deptName}) " +
                                    "MERGE (d)-[:HAS_AD_GROUP]->(g)",
                            Map.of("deptName", deptName)
                    );
                });

                // ── Department NESTED_UNDER Parent Group ──────────────────────
                depts.forEach(dept -> {
                    String deptName    = dept.getName().trim();
                    String parentGroup = dept.getGroup();
                    if (parentGroup != null && !parentGroup.isBlank()) {
                        tx.run(
                                "MATCH (d:Department {name:$deptName}), (pg:Group {displayName:$parent}) " +
                                        "MERGE (d)-[:NESTED_UNDER]->(pg)",
                                Map.of(
                                        "deptName", deptName,
                                        "parent",   parentGroup.trim()
                                )
                        );
                    }
                });

                return null;
            });
            logger.info("Data successfully pushed to Neo4j.");
        } catch (Exception e) {
            logger.error("Failed to push data to Neo4j: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Save audit logs to Neo4j database.
     */
    public void saveAuditLogs(JsonArray auditLogs) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                auditLogs.forEach(elem -> {
                    JsonObject entry = elem.getAsJsonObject();
                    tx.run(
                            "MERGE (log:AuditLog {id:$id}) " +
                                    "SET log.activityDisplayName = $activityDisplayName, " +
                                    "    log.activityDateTime    = datetime($activityDateTime), " +
                                    "    log.initiatedBy         = $initiatedBy",
                            Map.of(
                                    "id",                  entry.get("id").getAsString(),
                                    "activityDisplayName", entry.get("activityDisplayName").getAsString(),
                                    "activityDateTime",    entry.get("activityDateTime").getAsString(),
                                    "initiatedBy",         entry.get("initiatedBy").toString()
                            )
                    );
                });
                return null;
            });
            logger.info("Audit logs successfully saved to Neo4j.");
        } catch (Exception e) {
            logger.error("Error saving audit logs to Neo4j: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void closeDriver() {
        if (driver != null) {
            driver.close();
            logger.info("Neo4j driver closed successfully.");
        }
    }
}
