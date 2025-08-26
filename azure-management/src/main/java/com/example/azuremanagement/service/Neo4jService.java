package com.example.azuremanagement.service;

import com.example.azuremanagement.model.CustomRole;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.graph.models.DirectoryRole;
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
import com.microsoft.graph.models.ServicePrincipal;
import com.microsoft.graph.models.AppRoleAssignment;
import static org.neo4j.driver.Values.parameters;


import javax.annotation.PreDestroy;
import java.time.*;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.Map.entry;

@Service
public class Neo4jService {
    private static final Logger logger = LoggerFactory.getLogger(Neo4jService.class);

    private final Driver driver;

    public Neo4jService(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String user,
            @Value("${neo4j.password}") String pass
    ) {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
    }

    @PreDestroy
    public void close() {
        driver.close();
    }

    /** Safely get a String or return null */
    private String safeGetString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        return o.get(key).getAsString();
    }

    /**
     * Push users, groups, custom roles and their relationships.
     */
    public void pushData(
            List<User> users,
            List<Group> groups,
            Map<String, CustomRole> customRoles,
            Map<String, List<String>> userRolesLive,
            Map<String, List<String>> groupRolesLive,
            Map<String, List<String>> memberships,
            List<DirectoryRole> directoryRoles,
            List<ServicePrincipal> servicePrincipals,
            Map<String, List<String>> spRoleAssignments,
            Map<String, Boolean> mfaStates
    ) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                // 1) Users
                for (User u : users) {
                    tx.run(
                            "MERGE (u:User {id:$id}) " +
                                    "SET u.displayName = $displayName, u.userPrincipalName = $upn",
                            Map.of(
                                    "id", u.id,
                                    "displayName", u.displayName,
                                    "upn", u.userPrincipalName
                            )
                    );
                }

                // 2) Groups
                for (Group g : groups) {
                    tx.run(
                            "MERGE (g:Group {id:$id}) " +
                                    "SET g.displayName = $name",
                            Map.of(
                                    "id", g.id,
                                    "name", g.displayName
                            )
                    );
                }

                // 3) Custom Roles
                for (CustomRole r : customRoles.values()) {
                    tx.run(
                            "MERGE (r:Role {id:$id}) " +
                                    "SET r.name = $name, r.description = $desc",
                            Map.of(
                                    "id", r.getId(),
                                    "name", r.getRoleName(),
                                    "desc", r.getDescription()
                            )
                    );
                }
                for (DirectoryRole role : directoryRoles) {
                    if (role.id == null || role.displayName == null) continue;
                    tx.run(
                            "MERGE (r:Role {id:$id}) " +
                                    "SET r.name = $name, r.isDirectoryRole = true",
                            Map.of(
                                    "id", role.id,
                                    "name", role.displayName
                            )
                    );
                }

                // 4) User → Role (with metadata: assignedAt)
                for (var entry : userRolesLive.entrySet()) {
                    String userId = entry.getKey();
                    for (String roleId : entry.getValue()) {
                        tx.run(
                                // === ADDED: assignedAt timestamp on role assignment ===
                                "MATCH (u:User {id:$uid}), (r:Role {id:$rid}) " +
                                        "MERGE (u)-[hr:HAS_ROLE]->(r) " +
                                        "SET hr.assignedAt = coalesce(hr.assignedAt, datetime()), hr.isActive = true",
                                Map.of("uid", userId, "rid", roleId)
                        );
                    }
                }

                // 5) Group → Role (with metadata)
                for (var entry : groupRolesLive.entrySet()) {
                    String groupId = entry.getKey();
                    for (String roleId : entry.getValue()) {
                        tx.run(
                                "MATCH (g:Group {id:$gid}), (r:Role {id:$rid}) " +
                                        "MERGE (g)-[gr:HAS_ROLE]->(r) " +
                                        "SET gr.assignedAt = coalesce(gr.assignedAt, datetime()), gr.isActive = true",
                                Map.of("gid", groupId, "rid", roleId)
                        );
                    }
                }

                for (ServicePrincipal sp : servicePrincipals) {
                    if (sp.id == null) continue;
                    tx.run(
                            "MERGE (sp:ServicePrincipal {id:$id}) " +
                                    "SET sp.displayName = $displayName, sp.appId = $appId",
                            Map.of(
                                    "id", sp.id,
                                    "displayName", sp.displayName,
                                    "appId", sp.appId
                            )
                    );
                }

                for (var entry : spRoleAssignments.entrySet()) {
                    String spId = entry.getKey();
                    for (String roleId : entry.getValue()) {
                        tx.run(
                                "MATCH (sp:ServicePrincipal {id:$spId}), (r:Role {id:$roleId}) " +
                                        "MERGE (sp)-[sr:HAS_ROLE]->(r) " +
                                        "SET sr.assignedAt = coalesce(sr.assignedAt, datetime()), sr.isActive = true",
                                Map.of("spId", spId, "roleId", roleId)
                        );
                    }
                }

                // 6) Memberships: User → Group
                for (var entry : memberships.entrySet()) {
                    String groupId = entry.getKey();
                    for (String userId : entry.getValue()) {
                        tx.run(
                                "MATCH (u:User {id:$uid}), (g:Group {id:$gid}) " +
                                        "MERGE (u)-[:MEMBER_OF]->(g)",
                                Map.of("uid", userId, "gid", groupId)
                        );
                    }
                }

                for (var entry : mfaStates.entrySet()) {
                    tx.run(
                            "MATCH (u:User {id:$id}) " +
                                    "SET u.hasMfa = $hasMfa",
                            Map.of(
                                    "id", entry.getKey(),
                                    "hasMfa", entry.getValue()
                            )
                    );
                }

                return null;
            });
            logger.info("✅ pushData: users, groups & roles synced");
        } catch (Exception ex) {
            logger.error("❌ Error in pushData(): {}", ex.getMessage(), ex);
        }
    }

    /**
     * Merge AuditLog nodes and link them back to :User via PERFORMED.
     */
    public void saveAuditLogs(JsonArray auditLogs) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                for (JsonElement el : auditLogs) {
                    JsonObject e = el.getAsJsonObject();

                    String id = safeGetString(e, "activityId");
                    String upn = safeGetString(e, "initiatedByUserPrincipalName");
                    String ts = safeGetString(e, "activityDateTime");
                    String action = safeGetString(e, "activityDisplayName");

                    if (id == null) {
                        String synthetic = UUID.randomUUID().toString();
                        logger.warn("No activityId present—using synthetic id {}", synthetic);
                        id = synthetic;
                    }

                    Map<String, Object> params = new HashMap<>();
                    params.put("id", id);
                    params.put("ts", ts);
                    params.put("action", action);

                    tx.run(
                            "MERGE (a:AuditLog {id: $id})\n" +
                                    "SET a.activityDateTime    = datetime($ts),\n" +
                                    "    a.activityDisplayName = $action",
                            params
                    );

                    if (upn != null) {
                        Map<String, Object> rel = Map.of(
                                "upn", upn,
                                "id", id,
                                "ts", ts
                        );
                        tx.run(
                                "MATCH (u:User {userPrincipalName:$upn}),\n" +
                                        "      (a:AuditLog {id:$id})\n" +
                                        "MERGE (u)-[:PERFORMED {at: datetime($ts)}]->(a)",
                                rel
                        );
                        // === ADDED: update lastActivity on user ===
                        tx.run(
                                "MATCH (u:User {userPrincipalName:$upn}) " +
                                        "SET u.lastActivity = datetime($ts)",
                                Map.of("upn", upn)
                        );
                    }
                }
                return null;
            });
            logger.info("✅ Saved & linked {} AuditLog entries", auditLogs.size());
        } catch (Exception ex) {
            logger.error("❌ Error saving audit logs: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Merge SignInLog nodes and link back to :User via SIGNED_IN.
     */
    public void saveSignInLogs(JsonArray signInLogs) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                for (JsonElement el : signInLogs) {
                    JsonObject e = el.getAsJsonObject();

                    String id = safeGetString(e, "id");
                    String upn = safeGetString(e, "userPrincipalName");
                    String display = safeGetString(e, "userDisplayName");
                    String ts = safeGetString(e, "createdDateTime");
                    String status = null;
                    if (e.has("status") && e.getAsJsonObject("status") != null) {
                        status = safeGetString(e.getAsJsonObject("status"), "value");
                    }
                    String ipAddress = safeGetString(e, "ipAddress");
                    String appUsed = safeGetString(e, "clientAppUsed");
                    String condAccess = safeGetString(e, "conditionalAccessStatus");

                    // === ADDED: compute outside business hours for sign-in (IST 08:00–18:00) ===
                    boolean outsideBusinessHours = false;
                    try {
                        ZonedDateTime eventTimeUtc = OffsetDateTime.parse(ts).toZonedDateTime();
                        ZonedDateTime ist = eventTimeUtc.withZoneSameInstant(ZoneId.of("Asia/Kolkata"));
                        int hour = ist.getHour();
                        outsideBusinessHours = !(hour >= 8 && hour < 18);
                    } catch (Exception ex) {
                        logger.warn("Failed to parse signIn timestamp for business hours calc: {}", ts);
                    }

                    Map<String, Object> params = new HashMap<>();
                    params.put("id", id);
                    params.put("ts", ts);
                    params.put("status", status);
                    params.put("ip", ipAddress);
                    params.put("app", appUsed);
                    params.put("cas", condAccess);
                    params.put("display", display);
                    params.put("obh", outsideBusinessHours);

                    tx.run(
                            "MERGE (s:SignInLog {id:$id})\n" +
                                    "SET s.createdDateTime = datetime($ts),\n" +
                                    "    s.status = $status,\n" +
                                    "    s.ipAddress = $ip,\n" +
                                    "    s.clientAppUsed = $app,\n" +
                                    "    s.conditionalAccessStatus = $cas,\n" +
                                    "    s.userDisplayName = $display,\n" +
                                    "    s.outsideBusinessHours = $obh\n",
                            params
                    );

                    if (upn != null) {
                        Map<String, Object> rel = Map.of(
                                "upn", upn,
                                "id", id,
                                "ts", ts,
                                "display", display
                        );
                        tx.run(
                                "MERGE (u:User {userPrincipalName:$upn})\n" +
                                        "SET u.displayName = $display\n" +
                                        "MERGE (s:SignInLog {id:$id})\n" +
                                        "MERGE (u)-[r:SIGNED_IN]->(s)\n" +
                                        "SET r.at = datetime($ts), u.lastActivity = datetime($ts)\n",
                                rel
                        );
                    }
                }
                return null;
            });
            logger.info("✅ Saved & linked {} SignInLog entries", signInLogs.size());
        } catch (Exception ex) {
            logger.error("❌ Error saving sign-in logs: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Merge ActivityLog nodes (all props), create Resource node, mark outside-business-hours,
     * handle first-time critical access, and link back to :User via PERFORMED.
     */
    public void saveActivityLogs(JsonArray activityLogs) {

        if (activityLogs == null || activityLogs.size() == 0) {
            logger.info("⚠️  No ActivityLog records supplied – nothing to do.");
            return;
        }

        try (Session session = driver.session()) {

            session.writeTransaction(tx -> {

                int inserted = 0;

                for (JsonElement el : activityLogs) {
                    if (!el.isJsonObject()) continue;

                    JsonObject e = el.getAsJsonObject();

                    // 1) Extract fields
                    String id = safeGetString(e, "correlationId");
                    if (id == null) continue;

                    String opName = safeGetString(e.getAsJsonObject("operationName"), "localizedValue");
                    if (opName == null) opName = safeGetString(e, "operationName");

                    String status = safeGetString(e.getAsJsonObject("status"), "value");
                    String category = safeGetString(e.getAsJsonObject("category"), "value");
                    String level = safeGetString(e, "level");
                    String time = safeGetString(e, "eventTimestamp"); // ISO-8601 with Z ideally

                    String subId = safeGetString(e, "subscriptionId");
                    String caller = safeGetString(e, "caller");

                    String resType = safeGetString(e.getAsJsonObject("resourceType"), "value");
                    String rgName = safeGetString(e, "resourceGroupName");
                    String resName = safeGetString(e, "resourceId");

                    // === ADDED: compute outside-business-hours based on IST timezone ===
                    boolean outsideBusinessHours = false;
                    try {
                        ZonedDateTime eventTimeUtc = OffsetDateTime.parse(time).toZonedDateTime();
                        ZonedDateTime ist = eventTimeUtc.withZoneSameInstant(ZoneId.of("Asia/Kolkata"));
                        int hour = ist.getHour();
                        outsideBusinessHours = !(hour >= 8 && hour < 18);
                    } catch (Exception ex) {
                        logger.warn("Failed to parse activity timestamp for business hours calc: {}", time);
                    }

                    // 2) Create / Update ActivityLog with a mutable map to avoid Map.of overload issues
                    Map<String, Object> activityParams = new HashMap<>();
                    activityParams.put("id", id);
                    activityParams.put("opName", opName);
                    activityParams.put("status", status);
                    activityParams.put("category", category);
                    activityParams.put("level", level);
                    activityParams.put("time", time);
                    activityParams.put("subId", subId);
                    activityParams.put("resType", resType);
                    activityParams.put("rgName", rgName);
                    activityParams.put("resName", resName);
                    activityParams.put("obh", outsideBusinessHours);

                    tx.run(
                            "MERGE (a:ActivityLog {id:$id}) " +
                                    "SET  a.operationName = $opName, " +
                                    "     a.status        = $status, " +
                                    "     a.category      = $category, " +
                                    "     a.level         = $level, " +
                                    "     a.time          = datetime($time), " +
                                    "     a.subscription  = $subId, " +
                                    "     a.resourceType  = $resType, " +
                                    "     a.resourceGroup = $rgName, " +
                                    "     a.resourceName  = $resName, " +
                                    "     a.outsideBusinessHours = $obh",
                            activityParams
                    );

                    // 3) Link ActivityLog → Resource (and create resource)
                    tx.run(
                            "MERGE (res:Resource {id:$resName}) " +
                                    "SET res.resourceType = $resType, res.resourceGroup = $rgName " +
                                    "MERGE (a:ActivityLog {id:$id}) " +
                                    "MERGE (a)-[:TARGETS]->(res)",
                            Map.of("resName", resName, "resType", resType, "rgName", rgName, "id", id)
                    );

                    // 4) Link ActivityLog → User if caller present
                    if (caller != null) {
                        tx.run(
                                "MATCH (u:User {userPrincipalName:$upn}), " +
                                        "      (a:ActivityLog {id:$id}) " +
                                        "MERGE (u)-[:PERFORMED {at: datetime($time)}]->(a) " +
                                        // === ADDED: update user.lastActivity with this access ===
                                        "SET u.lastActivity = datetime($time)",
                                Map.of("upn", caller, "id", id, "time", time)
                        );

                        // 5) FIRST-TIME ACCESS for critical resources
                        tx.run(
                                "MATCH (u:User {userPrincipalName:$upn}), (a:ActivityLog {id:$id})-[:TARGETS]->(res:Resource {id:$resName}) " +
                                        "WHERE res.critical = true " +
                                        "OPTIONAL MATCH (u)-[:PERFORMED]->(other:ActivityLog)-[:TARGETS]->(res) " +
                                        "WHERE other.id <> $id " +
                                        "WITH u, res, count(other) AS priorCount " +
                                        "WHERE priorCount = 0 " +
                                        "MERGE (u)-[f:FIRST_TIME_ACCESSED {at: datetime($time)}]->(res)",
                                Map.of("upn", caller, "resName", resName, "id", id, "time", time)
                        );
                    } else {
                        logger.warn("⚠️  ActivityLog {} has no caller – not linked to a user", id);
                    }

                    inserted++;
                }

                logger.info("✅ {} ActivityLog records persisted (and linked where possible)", inserted);
                return null;
            });

        } catch (Exception ex) {
            logger.error("❌ Error saving activity logs: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Ingest Resource metadata separately (tags: critical, restricted, sensitivity, owner, team).
     */
    public void saveResourceMetadata(JsonArray resources) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                for (JsonElement el : resources) {
                    if (!el.isJsonObject()) continue;
                    JsonObject r = el.getAsJsonObject();
                    String id = safeGetString(r, "id");
                    if (id == null) continue;
                    String name = safeGetString(r, "name");
                    String type = safeGetString(r, "type");
                    String resourceGroup = safeGetString(r, "resourceGroup");

                    JsonObject tags = r.has("tags") ? r.getAsJsonObject("tags") : new JsonObject();
                    String sensitivity = safeGetString(tags, "sensitivity");
                    String critical = safeGetString(tags, "critical");
                    String restricted = safeGetString(tags, "restricted");
                    String owner = safeGetString(tags, "owner");
                    String team = safeGetString(tags, "team");

                    Map<String, Object> params = new HashMap<>();
                    params.put("id", id);
                    params.put("name", name);
                    params.put("type", type);
                    params.put("rg", resourceGroup);
                    params.put("sensitivity", sensitivity);
                    params.put("critical", critical != null && critical.equalsIgnoreCase("true"));
                    params.put("restricted", restricted != null && restricted.equalsIgnoreCase("true"));
                    params.put("owner", owner);
                    params.put("team", team);

                    tx.run(
                            "MERGE (res:Resource {id:$id}) " +
                                    "SET res.name = $name, res.type = $type, res.resourceGroup = $rg, " +
                                    "res.sensitivity = $sensitivity, res.critical = $critical, res.restricted = $restricted, " +
                                    "res.owner = $owner, res.team = $team",
                            params
                    );
                }
                return null;
            });
            logger.info("✅ Saved & enriched {} resources with metadata", resources.size());
        } catch (Exception ex) {
            logger.error("❌ Error saving resource metadata: {}", ex.getMessage(), ex);
        }
    }

    public void createServicePrincipalRelationships(ServicePrincipal sp, List<AppRoleAssignment> assignments) {
        try (Session session = driver.session()) {
            for (AppRoleAssignment assignment : assignments) {
                String appRoleId = assignment.appRoleId != null ? assignment.appRoleId.toString() : null;
                String targetId = assignment.resourceId != null ? assignment.resourceId.toString() : null;

                // Relationship: (sp)-[:TARGETS]->(Resource)
                if (targetId != null) {
                    session.run("MATCH (sp:ServicePrincipal {id: $spId}), (res:Resource {id: $targetId}) " +
                                    "MERGE (sp)-[:TARGETS]->(res)",
                            parameters("spId", sp.id, "targetId", targetId));
                }

                // Relationship: (sp)-[:USES_ROLE]->(Role)
                if (appRoleId != null) {
                    session.run("MATCH (sp:ServicePrincipal {id: $spId}), (role:Role {id: $roleId}) " +
                                    "MERGE (sp)-[:USES_ROLE]->(role)",
                            parameters("spId", sp.id, "roleId", appRoleId));
                }
            }
        }
    }

    public void createRelationshipBetweenServicePrincipalAndRole(String servicePrincipalId, String roleId) {
        try (Session session = driver.session()) {
            session.run("MATCH (sp:ServicePrincipal {id: $spId}), (r:Role {id: $roleId}) " +
                            "MERGE (sp)-[:USES_ROLE]->(r)",
                    parameters("spId", servicePrincipalId, "roleId", roleId));
        }
    }

    public void createRelationshipBetweenServicePrincipalAndResource(String servicePrincipalId, String resourceId) {
        try (Session session = driver.session()) {
            session.run("MATCH (sp:ServicePrincipal {id: $spId}), (res:Resource {id: $resId}) " +
                            "MERGE (sp)-[:TARGETS]->(res)",
                    parameters("spId", servicePrincipalId, "resId", resourceId));
        }
    }



    /**
     * Ingest justifications and link to access events (ActivityLog).
     * Expected JSON per item: { ticketId, reason, approvedBy, approvedAt, status, accessEventId }
     */
    public void saveJustifications(JsonArray justifications) {
        try (Session session = driver.session()) {
            session.writeTransaction(tx -> {
                for (JsonElement el : justifications) {
                    if (!el.isJsonObject()) continue;
                    JsonObject j = el.getAsJsonObject();
                    String ticketId = safeGetString(j, "ticketId");
                    String reason = safeGetString(j, "reason");
                    String approvedBy = safeGetString(j, "approvedBy");
                    String approvedAt = safeGetString(j, "approvedAt");
                    String status = safeGetString(j, "status");
                    String eventId = safeGetString(j, "accessEventId"); // can be ActivityLog.id

                    if (ticketId == null) continue;

                    Map<String, Object> params = new HashMap<>();
                    params.put("ticketId", ticketId);
                    params.put("reason", reason);
                    params.put("approvedBy", approvedBy);
                    params.put("approvedAt", approvedAt);
                    params.put("status", status);

                    tx.run(
                            "MERGE (just:Justification {ticketId:$ticketId}) " +
                                    "SET just.reason=$reason, just.approvedBy=$approvedBy, just.approvedAt=datetime($approvedAt), just.status=$status",
                            params
                    );

                    if (eventId != null) {
                        tx.run(
                                "MATCH (just:Justification {ticketId:$ticketId}), (a:ActivityLog {id:$eventId}) " +
                                        "MERGE (a)-[:JUSTIFIED_BY]->(just)",
                                Map.of("ticketId", ticketId, "eventId", eventId)
                        );
                    }
                }
                return null;
            });
            logger.info("✅ Saved {} justifications", justifications.size());
        } catch (Exception ex) {
            logger.error("❌ Error saving justifications: {}", ex.getMessage(), ex);
        }
    }

}
