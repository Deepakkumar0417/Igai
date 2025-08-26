import os
import json
import logging
import atexit
import uuid
import re
from threading import Thread
import requests
from msal import ConfidentialClientApplication
from flask import Flask, request, jsonify
from flask_cors import CORS
from flask_socketio import SocketIO, emit, join_room
from neo4j import GraphDatabase
from neo4j.time import DateTime
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

AZURE_TENANT_ID     = os.getenv("AZURE_TENANT_ID")
AZURE_CLIENT_ID     = os.getenv("AZURE_CLIENT_ID")
AZURE_CLIENT_SECRET = os.getenv("AZURE_CLIENT_SECRET")
AUTHORITY           = f"https://login.microsoftonline.com/{AZURE_TENANT_ID}"
SCOPES              = ["https://graph.microsoft.com/.default"]

msal_app = ConfidentialClientApplication(
    AZURE_CLIENT_ID,
    authority=AUTHORITY,
    client_credential=AZURE_CLIENT_SECRET
)

def get_graph_token():
    """Acquire a token from Azure AD using client credentials."""
    result = msal_app.acquire_token_silent(SCOPES, account=None)
    if not result:
        result = msal_app.acquire_token_for_client(scopes=SCOPES)
    if "access_token" not in result:
        logger.error("Token acquisition failed: %s", result.get("error_description"))
        raise RuntimeError("Could not obtain Graph token")
    return result["access_token"]


# --------------------- Configuration & Logging --------------------- #

app = Flask(__name__)
CORS(app)
socketio = SocketIO(app, cors_allowed_origins="*")

# Environment Variables for Azure endpoints and backend base URL
azure_api_key = os.getenv("AZURE_INFERENCE_CREDENTIAL_GPT")
azure_api_endpoint = os.getenv("AZURE_INFERENCE_ENDPOINT_GPT")
azure_api_endpoint_gpt4o = os.getenv("AZURE_INFERENCE_ENDPOINT_GPT4o")
backend_base_url = os.getenv("BACKEND_BASE_URL", "http://localhost:9093")
if not backend_base_url.startswith("http"):
    backend_base_url = "http://" + backend_base_url

headers = {
    "Content-Type": "application/json",
    "api-key": azure_api_key,
}

GRAPH_URI = os.getenv("GRAPH_URI")
GRAPH_USERNAME = os.getenv("GRAPH_USERNAME")
GRAPH_PASSWORD = os.getenv("GRAPH_PASSWORD")
GRAPH_DATABASE = os.getenv("GRAPH_DATABASE")

if not GRAPH_URI:
    logger.error("GRAPH_URI is not set. Please check your .env file.")
else:
    logger.info("GRAPH_URI loaded: %s", GRAPH_URI)

driver = GraphDatabase.driver(GRAPH_URI, auth=(GRAPH_USERNAME, GRAPH_PASSWORD))

def close_driver():
    if driver is not None:
        driver.close()
        logger.info("Neo4j driver closed.")

atexit.register(close_driver)

def normalize(text):
    return re.sub(r"[^a-z0-9 ]", "", text.lower())


PROMPT_MAP = [
    {
        "keywords": ["too many writes", "upload spam", "excessive writes"],
        "description": "Users with excessive writes but almost no reads (possible automation/misuse).",
        "cypher": """
            MATCH (u:User)-[:PERFORMED]->(w:ActivityLog)
            WHERE w.operationType = 'Write' AND w.activityDateTime >= datetime() - duration('P7D')
            WITH u, count(w) AS writes
            OPTIONAL MATCH (u)-[:PERFORMED]->(r:ActivityLog)
            WHERE r.operationType = 'Read' AND r.activityDateTime >= datetime() - duration('P7D')
            WITH u, writes, count(r) AS reads
            WHERE reads = 0 OR writes > 10 * reads
            RETURN u.displayName AS user, writes, reads
            ORDER BY writes DESC
        """,
        "recommendation": "Review these identities: excessive write volume with little to no reads often indicates automation abuse or misconfigured processes. Throttle or isolate the source, validate business purpose, and apply least privilege or just-in-time access.",
        "insights": "High write-to-read ratio can inflate storage costs, increase attack surface, and may mask credential misuse. Correlate with source IPs and timing to distinguish legitimate batch jobs from anomalies."
    },
    {
        "keywords": ["no activity", "inactive", "stale access"],
        "description": "Users who still have active roles but no recent activity (stale/orphaned identities).",
        "cypher": """
            MATCH (u:User)-[hr:HAS_ROLE]->(r:Role)
            WHERE u.accountEnabled = true
              AND NOT EXISTS {
                  MATCH (u)-[:PERFORMED]->(a:ActivityLog)
                  WHERE a.activityDateTime >= datetime() - duration('P90D')
              }
            RETURN u.displayName AS user, collect(coalesce(r.roleName, r.name)) AS roles, hr.assignedAt AS assignedAt
        """,
        "recommendation": "Disable or review these accounts; enforce periodic access recertification and consider automatic expiration for roles with no use.",
        "insights": "Stale access increases attack surface with little oversight. Removing unused identities reduces risk and simplifies entitlement management."
    },
    {
        "keywords": ["write not used", "write permission unused"],
        "description": "Users who have write-level permissions but have not performed write operations recently.",
        "cypher": """
            MATCH (u:User)-[:HAS_ROLE]->(r:Role)
            WHERE (
                  toLower(coalesce(r.roleName, r.name)) CONTAINS 'write'
               OR toLower(coalesce(r.roleName, r.name)) CONTAINS 'contributor'
            )
              AND NOT EXISTS {
                  MATCH (u)-[:PERFORMED]->(a:ActivityLog)
                  WHERE a.operationType = 'Write' AND a.activityDateTime >= datetime() - duration('P60D')
              }
            RETURN u.displayName AS user, collect(coalesce(r.roleName, r.name)) AS writeRoles
        """,
        "recommendation": "Right-size privileges: remove unused write roles or convert to read-only. Consider just-in-time elevation when writes are necessary.",
        "insights": "Permissions without usage violate least privilege and can be abused if credentials are compromised. Periodic cleanup reduces audit fatigue."
    },
    {
        "keywords": ["login at night", "odd hour access"],
        "description": "Users accessing/signing in outside business hours (IST 08:00–18:00).",
        "cypher": """
            MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
            WHERE s.outsideBusinessHours = true
              AND s.createdDateTime >= datetime() - duration('P30D')
            RETURN u.displayName AS user, s.createdDateTime AS loginTime, 'Off-hours access' AS issue
            ORDER BY s.createdDateTime DESC
        """,
        "recommendation": "Require step-up authentication (MFA), alert security for unusual patterns, and validate if the access was expected. Enforce conditional access policies restricting high-risk off-hours logins.",
        "insights": "Frequent off-hours access may signal compromised credentials or unauthorized automation. Correlate with geolocation, IP anomalies, and impossible travel detection."
    },
    {
        "keywords": ["first time access", "new critical access"],
        "description": "First-time access to critical resources by a user.",
        "cypher": """
            MATCH (u:User)-[f:FIRST_TIME_ACCESSED]->(r:Resource)
            WHERE r.critical = true
              AND f.at >= datetime() - duration('P7D')
            RETURN u.displayName AS user, r.name AS resource, f.at AS firstAccessTime
            ORDER BY f.at DESC
        """,
        "recommendation": "Validate the business necessity, perform additional verification (e.g., notify owner), and monitor that session closely for lateral movement.",
        "insights": "Novel access to critical assets is high-signal for privilege creep, onboarding misconfigurations, or early lateral movement in a compromise. Tag and suppress duplicates after review."
    },
    {
        "keywords": ["admin read", "admin daily"],
        "description": "Admin accounts used for routine read/list operations (possible misuse of high privilege).",
        "cypher": """
            MATCH (u:User)-[:HAS_ROLE]->(r:Role)
            WHERE toLower(coalesce(r.roleName, r.name)) CONTAINS 'admin'
               OR toLower(coalesce(r.roleName, r.name)) CONTAINS 'owner'
               OR toLower(coalesce(r.roleName, r.name)) CONTAINS 'contributor'
            MATCH (u)-[:PERFORMED]->(a:ActivityLog)
            WHERE a.operationType IN ['Read','List'] AND a.activityDateTime >= datetime() - duration('P30D')
            WITH u, collect(DISTINCT coalesce(r.roleName, r.name)) AS roles, count(a) AS readOps
            WITH u, roles, readOps, toFloat(readOps)/30.0 AS avgPerDay
            WHERE avgPerDay > 5
            RETURN u.displayName AS adminUser, roles, readOps, round(avgPerDay,2) AS avgDailyReads
        """,
        "recommendation": "Move routine observation tasks to lower-privilege or read-only accounts; consider automating these queries instead of using admin credentials manually.",
        "insights": "High-frequency read operations from privileged accounts inflate risk—if such accounts are compromised, attackers gain deep visibility. Use dedicated observer roles or audit pipelines."
    },
    {
        "keywords": ["multiple roles", "many privileges"],
        "description": "Users over-privileged due to membership in multiple elevated roles.",
        "cypher": """
            MATCH (u:User)-[:HAS_ROLE]->(r:Role)
            WITH u, [role IN collect(DISTINCT toLower(coalesce(r.roleName, r.name))) WHERE role IN ['owner','contributor','access administrator','user access administrator']] AS elevated
            WHERE size(elevated) > 1
            RETURN u.displayName AS user, elevated AS elevatedRoles
        """,
        "recommendation": "Perform role consolidation, apply least privilege reviews, and consider breaking up high-overlap role assignments into scoped, task-specific privileges.",
        "insights": "Multiple elevated roles increase blast radius and make auditing harder. Prioritize users with overlapping high-level roles for entitlement review."
    },
    {
        "keywords": ["unused role", "inactive role assignment"],
        "description": "Roles assigned but no activity/use in the last 90 days (inactive role assignments).",
        "cypher": """
            MATCH (u:User)-[hr:HAS_ROLE]->(r:Role)
            WHERE hr.assignedAt <= datetime() - duration('P90D')
              AND NOT EXISTS {
                  MATCH (u)-[:PERFORMED]->(a:ActivityLog)
                  WHERE a.activityDateTime >= datetime() - duration('P90D')
              }
            RETURN u.displayName AS user, coalesce(r.roleName, r.name) AS role, hr.assignedAt AS assignedAt
        """,
        "recommendation": "Revoke or re-certify these roles; implement automated expiration or access review workflows for low-use assignments.",
        "insights": "Inactive roles linger as unnecessary privileges. Regularly pruning them tightens security and reduces audit noise."
    },
    {
        "keywords": ["top written", "frequent writes"],
        "description": "Top write-intensive resources in the last 30 days.",
        "cypher": """
            MATCH (u:User)-[:PERFORMED]->(a:ActivityLog)
            WHERE a.operationType = 'Write' AND a.activityDateTime >= datetime() - duration('P30D')
            WITH a.resourceName AS resource, count(*) AS writeCount
            RETURN resource, writeCount
            ORDER BY writeCount DESC
            LIMIT 10
        """,
        "recommendation": "Ensure these high-write resources are monitored for spikes, validate that write volume aligns with expected usage, and protect them with appropriate throttling/backups.",
        "insights": "Sudden increases or sustained high write activity can indicate abuse, data exfiltration, or runaway processes. Correlate with user identity and change context."
    },
    {
        "keywords": ["unauthorized", "restricted", "no justification"],
        "description": "Access to restricted resources without an attached justification.",
        "cypher": """
            MATCH (u:User)-[:PERFORMED]->(a:ActivityLog)-[:TARGETS]->(r:Resource)
            WHERE r.restricted = true
              AND NOT (a)-[:JUSTIFIED_BY]->(:Justification)
              AND a.activityDateTime >= datetime() - duration('P14D')
            RETURN u.displayName AS user, r.name AS resource, a.operationName AS operation, a.activityDateTime AS accessTime
            ORDER BY a.activityDateTime DESC
        """,
        "recommendation": "Block or quarantine access until justification is provided. Require retrospective ticket creation, notify resource owners, and consider temporary access revocation.",
        "insights": "Unjustified access to restricted assets is a high-risk policy violation. Automate enforcement via gating layers and memorialize justification for auditability."
    },
    {
        "keywords": ["list all users and roles", "users and roles", "user roles summary"],
        "description": "Comprehensive list of users with their assigned roles (resilient to property name differences).",
        "cypher": """
            MATCH (u:User)
            OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
            WITH u, [role IN collect(coalesce(r.roleName, r.name)) WHERE role IS NOT NULL] AS roles
            RETURN u.displayName AS user,
                   CASE WHEN size(roles)=0 THEN ['(no roles)'] ELSE roles END AS roles
            ORDER BY user
        """,
        "recommendation": "Use this as a baseline inventory for entitlement reviews; flag users with no roles or unexpectedly multiple elevated roles for follow-up.",
        "insights": "Displays effective role assignment regardless of ingestion inconsistencies between `roleName` and `name` properties."
    },
]



# --------------------- Enterprise Prompt Guidelines --------------------- #

ENTERPRISE_PROMPTS = """
Core Instructions
You are an enterprise-level AI assistant that generates raw Cypher queries only (no markdown, no commentary, no triple backticks) for a Neo4j database containing Azure AD entities and audit data.
Output Format: Raw Cypher text only, one query per line, blank line between multiple queries.

If the user requests “generate a report” or “summarize all data,” return the Cypher queries for the ten predefined reports (excessive writes, inactive last 90 days, unused writes, off‑hours access, first‑time resource access, admin read usage, multi‑privileged roles, inactive roles, top resources by writes in last 30 days, restricted access without justification).  List them one per line with a blank line between queries.  Do not merge them.  Use datetime functions where needed.
Give the complete report in a tabular format, add more insights and recommendations accordingly.

Handle any question about:
- Access behavior
- Anomalies
- Roles/Permissions
- Audit & Sign-In Activity
- Optimization

0. Fallback-Aware Query Resolution (NEW)
When the query appears ambiguous, perform the following steps before responding:
- Tokenize input string
- For each token, match against synonym dictionaries (user synonyms, group synonyms, log synonyms, etc.)
- If at least one entity type is identified, run a corresponding default Cypher query for that entity
- Else, return "DATA NOT FOUND"

# Tokenization Utility Pattern (use internally)
cypherANY(word IN SPLIT(TOLOWER(REPLACE(text, ' ', '_')), '_') WHERE word CONTAINS '<token>')

# Example fallback pattern if 'user' or synonym found
MATCH (u:User) RETURN u LIMIT 50

1. Schema Definition
Node Types & Properties
User: {displayName, userPrincipalName}
Group: {displayName}
Role: {roleName, displayName}
Policy: {policyName}
Department: {name, description, resources}
Resource: {resourceName}
AuditLog: {activityDisplayName, id, activityDateTime, category, initiatedBy, userDisplayName}
SignInLog: {id, createdDateTime, status, ipAddress, clientAppUsed, conditionalAccessStatus}
ActivityLog: {activityDisplayName, id, activityDateTime, category, initiatedBy, userDisplayName, resourceId}

Relationships & Directions

(:User)-[:MEMBER_OF]->(:Group)
(:User)-[:HAS_ROLE]->(:Role)
(:User)-[:BELONGS_TO_DEPARTMENT]->(:Department)
(:User)-[:PERFORMED]->(:AuditLog)
(:User)-[:PERFORMED]->(:ActivityLog)
(:User)-[:SIGNED_IN]->(:SignInLog)
(:Department)-[:HAS_AD_GROUP]->(:Group)
(:Department)-[:NESTED_UNDER]->(:Group)
(:Role)-[:ASSIGNED_TO_DEPARTMENT]->(:Department)

2. Entity Synonyms

user/person/member/operator → User
group/team/circle → Group
department/dept/division/unit → Department
role/perm/privilege → Role
policy/pol → Policy
resource/res → Resource
audit log/log/activity log/activity/activities/works/work/operation → AuditLog/ActivityLog
sign-in/signin/login/authentication → SignInLog
summary/gist/overview → Show comprehensive data with timestamps
action/action details/operation Name/function/modification  → al.operationName

3. Mandatory Tokenization Rules
For ALL multi-word filtering, use this pattern:
cypherANY(word IN SPLIT(TOLOWER(REPLACE(property, ' ', '_')), '_') WHERE word CONTAINS '<token>')
Multi-token searches (e.g., "Robert Davis"):
cypherWHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS 'robert')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS 'davis')
   
4. Core Query Templates
A. Basic Entity Lists
cypher# List all entities
MATCH (u:User) RETURN u
MATCH (g:Group) RETURN g
MATCH (d:Department) RETURN d
MATCH (r:Role) RETURN r
MATCH (res:Resource) RETURN res
MATCH (p:Policy) RETURN p
MATCH (a:AuditLog) RETURN a
MATCH (s:SignInLog) RETURN s
MATCH (al:ActivityLog) RETURN al

B. Filtered Entity Queries
cypher# Users with keyword filter
MATCH (u:User)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN u

# Groups with keyword filter
MATCH (g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN g

# Departments with keyword filter
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN d
C. Relationship Traversal Queries
cypher# Users in specific group
MATCH (u:User)-[:MEMBER_OF]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN u

# Users with specific role
MATCH (u:User)-[:HAS_ROLE]->(r:Role)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(r.roleName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN u

# Groups for user
MATCH (u:User)-[:MEMBER_OF]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN g

# Roles for user
MATCH (u:User)-[:HAS_ROLE]->(r:Role)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN r
5. Department-Specific Queries
Department Information
cypher# Department details
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN d

# AD groups for department
MATCH (d:Department)-[:HAS_AD_GROUP]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN g

# Nested groups under department
MATCH (d:Department)-[:NESTED_UNDER]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN g

# Users in department
MATCH (u:User)-[:BELONGS_TO_DEPARTMENT]->(d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN u

# Roles in department
MATCH (r:Role)-[:ASSIGNED_TO_DEPARTMENT]->(d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN r
Department Modifications
cypher# Create department
CREATE (d:Department {name: '<name>', description: '<desc>', resources: '<res>'})

# Assign AD group to department
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<deptKeyword>')
WITH d
MATCH (g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<groupKeyword>')
MERGE (d)-[:HAS_AD_GROUP]->(g)
RETURN d, g

# Update department resources
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
SET d.resources = '<newRes>'
RETURN d

# Delete department
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
DETACH DELETE d
6. Log-Based Queries
AuditLog Queries
cypher# All audit logs
MATCH (a:AuditLog) RETURN a

# Audit logs by activity
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN a

# Audit logs by date range
MATCH (a:AuditLog)
WHERE a.activityDateTime >= datetime('<start_ISO8601>')
  AND a.activityDateTime <= datetime('<end_ISO8601>')
RETURN a

# Audit logs by user
MATCH (u:User)-[:PERFORMED]->(a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN a

# Recent audit logs (last 7 days)
MATCH (a:AuditLog)
WHERE a.activityDateTime >= datetime() - duration('P7D')
RETURN a
ORDER BY a.activityDateTime DESC
SignInLog Queries
cypher# All sign-in logs
MATCH (s:SignInLog) RETURN s

# Sign-in logs by status
MATCH (s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(s.status, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN s

# Sign-in logs by user
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN s

# Recent sign-in logs (last 7 days)
MATCH (s:SignInLog)
WHERE s.createdDateTime >= datetime() - duration('P7D')
RETURN s
ORDER BY s.createdDateTime DESC
ActivityLog Queries
cypher# All activity logs
MATCH (al:ActivityLog) RETURN al

MATCH (u:User)
OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
OPTIONAL MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
WITH u, r, s
ORDER BY s.createdDateTime DESC
WITH u, COLLECT(DISTINCT r.roleName) AS roles, MAX(s.createdDateTime) AS lastLogin
RETURN u.userPrincipalName AS username, roles, lastLogin
LIMIT 50

Fetch full details of all users matching a given name, including any with similar or duplicate entries. Return each user's:
- Display name
- UPN
- Roles (if assigned)
- Last login timestamp

Make sure to include all matching users even if their display names or emails vary slightly (e.g., Xavier Joseph, xavier@domain.com, etc).

#if there are duplicate users use this cypher query to display both...
MATCH (u:User)
WHERE toLower(u.displayName) CONTAINS toLower($name)
   OR toLower(u.userPrincipalName) CONTAINS toLower($name)
OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
OPTIONAL MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
WITH u, COLLECT(DISTINCT r.roleName) AS roles, MAX(s.createdDateTime) AS lastLogin
RETURN 
  u.displayName AS displayName,
  u.userPrincipalName AS userPrincipalName,
  roles,
  lastLogin
ORDER BY displayName



# Activity logs by user
MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN al

# Activity logs by group
MATCH (g:Group)<-[:MEMBER_OF]-(u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN al

# Recent activity logs (last 7 days)
MATCH (al:ActivityLog)
WHERE al.activityDateTime >= datetime() - duration('P7D')
RETURN al
ORDER BY al.activityDateTime DESC
7. Combined & Summary Queries
User Details with All Relationships
cypher# Complete user profile
MATCH (u:User)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
OPTIONAL MATCH (u)-[:MEMBER_OF]->(g:Group)
OPTIONAL MATCH (u)-[:BELONGS_TO_DEPARTMENT]->(d:Department)
RETURN u, collect(DISTINCT r) AS roles, collect(DISTINCT g) AS groups, collect(DISTINCT d) AS departments

# All users with complete details
MATCH (u:User)
OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
OPTIONAL MATCH (u)-[:MEMBER_OF]->(g:Group)
OPTIONAL MATCH (u)-[:BELONGS_TO_DEPARTMENT]->(d:Department)
RETURN u, collect(DISTINCT r) AS roles, collect(DISTINCT g) AS groups, collect(DISTINCT d) AS departments
Recent Activity Summary
cypher# Combined recent events for user
MATCH (u:User)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
OPTIONAL MATCH (u)-[:PERFORMED]->(a:AuditLog)
WHERE a.activityDateTime >= datetime() - duration('P7D')
OPTIONAL MATCH (u)-[:PERFORMED]->(al:ActivityLog)
WHERE al.activityDateTime >= datetime() - duration('P7D')
OPTIONAL MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
WHERE s.createdDateTime >= datetime() - duration('P7D')
RETURN u.displayName AS user,
       count(DISTINCT a) AS auditEvents,
       count(DISTINCT al) AS activityEvents,
       count(DISTINCT s) AS signInEvents

# System-wide recent activity summary
MATCH (u:User)
OPTIONAL MATCH (u)-[:PERFORMED]->(a:AuditLog)
WHERE a.activityDateTime >= datetime() - duration('P7D')
OPTIONAL MATCH (u)-[:PERFORMED]->(al:ActivityLog)
WHERE al.activityDateTime >= datetime() - duration('P7D')
OPTIONAL MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
WHERE s.createdDateTime >= datetime() - duration('P7D')
RETURN u.displayName AS user,
       count(DISTINCT a) AS auditEvents,
       count(DISTINCT al) AS activityEvents,
       count(DISTINCT s) AS signInEvents
ORDER BY (auditEvents + activityEvents + signInEvents) DESC
8. Aggregation & Count Queries
cypher# Count users
MATCH (u:User) RETURN count(u)

# Count users in group
MATCH (u:User)-[:MEMBER_OF]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN count(u)

# Count groups per department
MATCH (d:Department)-[:HAS_AD_GROUP|NESTED_UNDER]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN count(g)

# Count roles per user
MATCH (u:User)-[:HAS_ROLE]->(r:Role)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN count(r)
9. Schema Discovery
cypher# List all node labels
MATCH (n) UNWIND labels(n) AS label RETURN DISTINCT label

# List all relationship types
MATCH ()-[r]->() RETURN DISTINCT type(r)

# List properties for specific label
CALL apoc.meta.nodeTypeProperties() YIELD nodeType, propertyName
WHERE nodeType = '<Label>'
RETURN DISTINCT propertyName
10. Advanced Recursive Queries
cypher# Nested group hierarchy
MATCH (g:Group)
OPTIONAL MATCH path=(g)-[:NESTED_UNDER*1..5]->(sub:Group)
OPTIONAL MATCH (d:Department)-[:NESTED_UNDER]->(g)
RETURN g AS group,
       collect(DISTINCT sub) AS nestedGroups,
       collect(DISTINCT d) AS parentDepartments

# Department hierarchy with groups
MATCH (d:Department)-[rel:HAS_AD_GROUP|NESTED_UNDER]->(g:Group)
RETURN d, type(rel) AS relationshipType, g
11. Fallback & Error Handling
Intelligent Fallbacks

If query unclear but contains "user" → MATCH (u:User) RETURN u
If query unclear but contains "group" → MATCH (g:Group) RETURN g
If query unclear but contains "department" → MATCH (d:Department) RETURN d
If query unclear but contains "role" → MATCH (r:Role) RETURN r
If query unclear but contains "log" → MATCH (a:AuditLog) RETURN a

Complete Data Not Found
If completely unclear: DATA NOT FOUND
12. Processing Rules

Always tokenize multi-word input using the mandatory pattern
Never use direct property matching for multi-word strings
Always use correct relationship directions as defined in schema
Output raw Cypher only - no markdown, no commentary
Separate multiple queries with blank lines
Apply intelligent fallbacks when input is ambiguous
Use datetime functions for time-based filtering
Include ORDER BY for time-based queries (most recent first)
Use OPTIONAL MATCH for optional relationships
Use collect(DISTINCT x) for aggregating related entities

13. Property Key Mapping & Intelligence

Property Synonyms Recognition:
# User Properties
displayName/display name/name/full name/username → u.displayName
userPrincipalName/upn/principal/email/login/user id → u.userPrincipalName
mailNickname/mail/nickname/alias → u.mailNickname
department/dept/division/user department → u.department

# AuditLog Properties
activityDisplayName/activity/action/operation/event → a.activityDisplayName
activityDateTime/date/time/timestamp/when/activity date → a.activityDateTime
category/type/log type/audit type → a.category
initiatedBy/initiated by/performed by/done by/actor → a.initiatedBy
userDisplayName/user name/performer/actor name → a.userDisplayName
initiatedByUserName/initiated by user/username → a.initiatedByUserName
initiatedByUserPrincipalName/initiated by upn/user upn → a.initiatedByUserPrincipalName
initiatedByAppName/app/application/service/initiated by app → a.initiatedByAppName
operationType/operation/op type/action type → a.operationType
result/status/outcome/success/failure → a.result
resultReason/reason/error/cause/failure reason → a.resultReason
correlationId/correlation/trace/tracking id → a.correlationId
loggedByService/service/logged by/source service → a.loggedByService

# SignInLog Properties
createdDateTime/created/signin time/login time/auth time → s.createdDateTime
status/signin status/login status/auth status/result → s.status
ipAddress/ip/client ip/source ip/login ip → s.ipAddress
clientAppUsed/app/client/application/client app → s.clientAppUsed
conditionalAccessStatus/ca status/conditional access/access status → s.conditionalAccessStatus

# ActivityLog Properties
resourceId/resource/target/resource id/affected resource → al.resourceId

# Group Properties
displayName/group name/name/team name → g.displayName

# Department Properties
name/department name/dept name/division name → d.name
description/dept description/department details → d.description
resources/dept resources/department resources → d.resources

# Role Properties
roleName/role name/permission name/role → r.roleName

14. Property-Based Query Templates

A. Property-Specific Filtering
# Filter by any user property
MATCH (u:User)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.<property>, ' ', '_')), '_') WHERE word CONTAINS '<value>')
RETURN u

# Filter audit logs by specific properties
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<activity>')
RETURN a

MATCH (a:AuditLog)
WHERE a.category = '<category>'
RETURN a

MATCH (a:AuditLog)
WHERE a.result = '<result>'
RETURN a

MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.initiatedByAppName, ' ', '_')), '_') WHERE word CONTAINS '<app>')
RETURN a

B. Combined Property Searches
# Audit logs with multiple property filters
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<activity>')
  AND a.category = '<category>'
  AND a.result = '<result>'
RETURN a

# User activities with property-based filtering
MATCH (u:User)-[:PERFORMED]->(a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<user>')
  AND ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<activity>')
RETURN u, a

C. Property Value Extraction
# Get distinct values for any property
MATCH (a:AuditLog) RETURN DISTINCT a.category
MATCH (a:AuditLog) RETURN DISTINCT a.result
MATCH (a:AuditLog) RETURN DISTINCT a.operationType
MATCH (s:SignInLog) RETURN DISTINCT s.status
MATCH (s:SignInLog) RETURN DISTINCT s.clientAppUsed

# Count by property values
MATCH (a:AuditLog) RETURN a.category, count(*) ORDER BY count(*) DESC
MATCH (a:AuditLog) RETURN a.result, count(*) ORDER BY count(*) DESC
MATCH (s:SignInLog) RETURN s.status, count(*) ORDER BY count(*) DESC

15. Property-Aware Query Intelligence

Property Context Recognition:
When user mentions:
- "activity" OR "operation" OR "action" → Search a.activityDisplayName
- "result" OR "status" OR "outcome" → Search a.result or s.status
- "app" OR "application" OR "service" → Search a.initiatedByAppName or s.clientAppUsed
- "ip" OR "address" OR "location" → Search s.ipAddress
- "time" OR "date" OR "when" → Filter by a.activityDateTime or s.createdDateTime
- "category" OR "type" → Search a.category
- "reason" OR "error" OR "cause" → Search a.resultReason
- "correlation" OR "trace" → Search a.correlationId

Auto Property Selection:
# When filtering by recognized property keywords
IF keyword matches activity/operation/action:
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN a

IF keyword matches result/status/outcome:
MATCH (a:AuditLog)
WHERE TOLOWER(a.result) CONTAINS '<keyword>'
RETURN a

IF keyword matches app/application/service:
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.initiatedByAppName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
RETURN a

16. Advanced Property Combinations

# Multi-property user search
MATCH (u:User)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<name>')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.userPrincipalName, ' ', '_')), '_') WHERE word CONTAINS '<name>')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.mailNickname, ' ', '_')), '_') WHERE word CONTAINS '<name>')
RETURN u

# Comprehensive audit search across all text properties
MATCH (a:AuditLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(a.activityDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(a.userDisplayName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(a.initiatedByUserName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
   OR ANY(word IN SPLIT(TOLOWER(REPLACE(a.initiatedByAppName, ' ', '_')), '_') WHERE word CONTAINS '<keyword>')
   OR TOLOWER(a.category) CONTAINS '<keyword>'
   OR TOLOWER(a.result) CONTAINS '<keyword>'
RETURN a

17. Property-Based Intelligence Rules

Auto-detect property intent:
- If query contains specific property names → Use exact property
- If query contains synonyms → Map to correct property
- If query is ambiguous → Search across relevant properties
- If query mentions values → Filter by those specific values
- If query asks for "types" or "categories" → Return DISTINCT values

Property precedence for ambiguous queries:
1. Exact property name match
2. Primary synonym match (displayName, activityDisplayName)
3. Secondary synonym match (name, activity)
4. Fallback to comprehensive search across multiple properties

Example transformations:
"show me login activities" → Search a.activityDisplayName for 'login'
"failed operations" → Search a.result = 'failure' OR a.result = 'failed'
"app activities" → Search a.initiatedByAppName
"user activities for john" → Search u.displayName for 'john' + their activities
"security events" → Search a.category for 'security' OR a.activityDisplayName for 'security'

18. Recent Activity Logs - Special Handling

When user asks for "recent activity logs" or "recent activities":
Always return ALL activity logs from the database regardless of date, but order by most recent first.

Query Pattern for "Recent Activity Logs":
# Recent activity logs for all users (shows ALL logs)
MATCH (u:User)-[:PERFORMED]->(a:AuditLog)
RETURN u.displayName AS user, 
       a.activityDisplayName AS action, 
       a.activityDateTime AS timestamp,
       a.category AS category,
       a.result AS result,
       a.initiatedByAppName AS application,
       a.operationType AS operationType
ORDER BY a.activityDateTime DESC

WITH datetime("2025-08-03T18:30:00Z") AS start, datetime("2025-08-04T18:29:59Z") AS end
    MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
    WHERE al.activityDateTime >= start AND al.activityDateTime <= end
    RETURN u.displayName AS user,
           al.activityDisplayName AS action,
           al.activityDateTime AS timestamp,
           al.resourceName AS resource,
           al.category AS category,
           al.operationName AS operation,
           al.status AS status
    ORDER BY al.activityDateTime ASC

# Alternative comprehensive format
MATCH (u:User)-[:PERFORMED]->(a:AuditLog)
RETURN u.displayName AS user,
       u.userPrincipalName AS userPrincipal,
       a.activityDisplayName AS keyAction,
       a.activityDateTime AS timestamp,
       a.category AS logCategory,
       a.result AS actionResult,
       a.resultReason AS reason,
       a.initiatedByAppName AS sourceApplication,
       a.correlationId AS correlationId
ORDER BY a.activityDateTime DESC

# Include ActivityLog nodes as well
MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
RETURN u.displayName AS user,
       al.activityDisplayName AS action,
       al.activityDateTime AS timestamp,
       al.category AS category,
       al.result AS result,
       al.operationType AS operationType,
       al.resourceId AS affectedResource
ORDER BY al.activityDateTime DESC

# Combined AuditLog and ActivityLog query
MATCH (u:User)-[:PERFORMED]->(log)
WHERE log:AuditLog OR log:ActivityLog
RETURN u.displayName AS user,
       u.userPrincipalName AS userPrincipal,
       log.activityDisplayName AS keyAction,
       log.activityDateTime AS timestamp,
       log.category AS category,
       log.result AS result,
       CASE 
         WHEN log:AuditLog THEN log.initiatedByAppName 
         ELSE null 
       END AS application,
       CASE 
         WHEN log:ActivityLog THEN log.resourceId 
         ELSE null 
       END AS resource,
       labels(log)[0] AS logType
ORDER BY log.activityDateTime DESC

# Recent storage access or modifications by users (any operation on storage resources)
MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE TOLOWER(al.resourceType) CONTAINS 'microsoft.storage'
  AND al.activityDateTime >= datetime() - duration('P7D')
RETURN u.displayName AS user,
       al.activityDisplayName AS action,
       al.activityDateTime AS timestamp,
       al.resourceType AS resourceType,
       al.resourceId AS resourceId,
       al.operationName AS operation,
       al.status AS status
ORDER BY al.activityDateTime DESC

# All recent storage operations (including user + system events)
MATCH (al:ActivityLog)
WHERE TOLOWER(al.resourceType) CONTAINS 'microsoft.storage'
  AND al.activityDateTime >= datetime() - duration('P7D')
RETURN al.activityDisplayName AS action,
       al.activityDateTime AS timestamp,
       al.resourceType AS resourceType,
       al.operationName AS operation,
       al.resourceId AS resourceId,
       al.status AS status
ORDER BY al.activityDateTime DESC

# Users who performed a specific storage operation (e.g., create, delete)
MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE TOLOWER(al.resourceType) CONTAINS 'microsoft.storage'
  AND TOLOWER(al.operationName) CONTAINS '<operation_keyword>'  // e.g., 'delete', 'put', 'get'
RETURN u.displayName AS user,
       al.operationName AS operation,
       al.activityDateTime AS timestamp,
       al.resourceId AS resourceId,
       al.status AS status
ORDER BY al.activityDateTime DESC

MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE al.activityDateTime >= datetime() - duration('P30D')
RETURN u.displayName AS user,
       al.operationName AS keyAction,
       al.activityDateTime AS timestamp,
       al.category AS category,
       al.status AS result
ORDER BY al.activityDateTime DESC

# Storage-related operations filtered by resource group
MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE TOLOWER(al.resourceType) CONTAINS 'microsoft.storage'
  AND TOLOWER(al.resourceGroup) CONTAINS '<group_keyword>'
RETURN u.displayName AS user,
       al.resourceGroup AS resourceGroup,
       al.operationName AS operation,
       al.activityDateTime AS timestamp,
       al.status AS status
ORDER BY al.activityDateTime DESC




MATCH (u:User)-[:PERFORMED]->(al:ActivityLog)
WHERE al.activityDateTime >= datetime() - duration('P7D')
  AND (
        TOLOWER(al.resourceType) CONTAINS 'blobservices'
     OR TOLOWER(al.resourceName) CONTAINS 'blobservices'
     OR TOLOWER(al.resourceName) CONTAINS '/containers/'
  )
RETURN u.displayName AS user,
       al.operationName AS operation,
       al.resourceName AS resource,
       al.activityDateTime AS timestamp,
       al.status AS status
ORDER BY al.activityDateTime DESC

MATCH (u:User)-[:PERFORMED]->(log)
WHERE log:ActivityLog OR log:AuditLog
  AND (
    (log.activityDateTime IS NOT NULL AND datetime(log.activityDateTime) >= datetime() - duration('P30D'))
    OR
    (log.activityDateTime IS NULL AND log.category IS NOT NULL) // safety fallback
  )
RETURN u.displayName AS user,
       COALESCE(log.operationName, log.activityDisplayName) AS keyAction,
       log.activityDateTime AS timestamp,
       log.category AS category,
       log.status AS result,
       labels(log)[0] AS logType
ORDER BY log.activityDateTime DESC




Special Rule for "Recent" Keyword:
When query contains "recent" + "activity" + "all users":
- Ignore date filtering completely
- Return ALL activity logs in database
- Always order by activityDateTime DESC
- Include comprehensive details (user, action, timestamp, category, result)
- Show both AuditLog and ActivityLog entries
- No LIMIT clause - show everything

# A. All sign-in times for a specific user
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<user_keyword>')
RETURN s.createdDateTime AS signInTime
ORDER BY s.createdDateTime DESC

# B. Number of sign-ins by user on specific date (e.g., July 17th)
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<user_keyword>')
  AND date(s.createdDateTime) = date('2025-07-17')
RETURN count(s) AS loginCount

# C. User login history in recent period (e.g., last 7 days)
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<user_keyword>')
  AND s.createdDateTime >= datetime() - duration('P7D')
RETURN s.createdDateTime AS signInTime
ORDER BY s.createdDateTime DESC

# D. Recent users who logged in
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE s.createdDateTime >= datetime() - duration('P7D')
RETURN u.displayName AS user, s.createdDateTime AS time
ORDER BY time DESC

# E. First and last login by user
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS '<user_keyword>')
WITH u, collect(s.createdDateTime) AS allLogins
RETURN u.displayName AS user, head(allLogins) AS firstLogin, last(allLogins) AS lastLogin

# F. Sign-in history across multiple users on a specific date
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE date(s.createdDateTime) = date('2025-07-17')
RETURN u.displayName AS user, s.createdDateTime AS loginTime
ORDER BY loginTime DESC

# G. Detect sign-in anomalies (e.g., users with more than 10 logins in a day)
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WITH u, date(s.createdDateTime) AS day, count(*) AS loginCount
WHERE loginCount > 10
RETURN u.displayName AS user, day, loginCount
ORDER BY loginCount DESC

# H. Users who did NOT sign in on a given day
MATCH (u:User)
WHERE NOT EXISTS {
  MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
  WHERE date(s.createdDateTime) = date('2025-07-17')
}
RETURN u.displayName AS user

# I. Total login frequency per user over last 30 days
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
WHERE s.createdDateTime >= datetime() - duration('P30D')
RETURN u.displayName AS user, count(s) AS loginCount
ORDER BY loginCount DESC

# J. Users with login success vs failure breakdown
MATCH (u:User)-[:SIGNED_IN]->(s:SignInLog)
RETURN u.displayName AS user,
       count(CASE WHEN TOLOWER(s.status) CONTAINS 'success' THEN 1 END) AS successfulLogins,
       count(CASE WHEN TOLOWER(s.status) CONTAINS 'fail' THEN 1 END) AS failedLogins
ORDER BY successfulLogins DESC


"""

# --------------------- Action-Only Prompt --------------------- #
ACTION_ENTERPRISE_PROMPTS = """
You are an enterprise-level AI assistant whose sole job is to generate **only** Cypher mutations for an Azure AD graph.
• Use only CREATE, MERGE, SET or DELETE.
• After each mutation, append a RETURN clause that returns the modified nodes:
    – e.g. `MERGE (u)-[:MEMBER_OF]->(g) RETURN u, g`
    – e.g. `MATCH (u)-[r:MEMBER_OF]->(g) DELETE r RETURN u, g`
    – e.g. `MATCH (u),(r:Role) … MERGE (u)-[:HAS_ROLE]->(r) RETURN u, r`
• Entities:
    – User nodes: `:User` with `displayName`
    – Group nodes: `:Group` with `displayName`
    – Role nodes: `:Role` with `roleName` or `displayName`
• Relationships:
    – `[:MEMBER_OF]` links User→Group
    – `[:HAS_ROLE]` links User→Role
• Split multi-word inputs into individual CONTAINS filters.
• Separate multiple queries with a blank line.
• **No** read-only queries (MATCH…RETURN alone). No markdown, no commentary.
"""

MANAGEMENT_PROMPT = """
You are an enterprise AI whose job is to generate Cypher mutations that ALTER a user's Azure AD roles (add, remove, update).
• Only use MATCH, MERGE, DELETE on (:User) and (:Role) nodes.
• For "assign role <roleName> to user <userDisplayName>":
  MATCH (u:User {displayName:'<user_displayName>'}), (r:Role {roleName:'<roleName>'})
  MERGE (u)-[:HAS_ROLE]->(r)
  RETURN u, r
• For "remove role <roleName> from user <userDisplayName>":
  MATCH (u:User {displayName:'<user_displayName>'})-[rel:HAS_ROLE]->(r:Role {roleName:'<roleName>'})
  DELETE rel
  RETURN u, r
• For "update user <userDisplayName> from role <oldRole> to <newRole>":
  MATCH (u:User {displayName:'<user_displayName>'})-[rel:HAS_ROLE]->(r_old:Role {roleName:'<oldRole>'})
  DELETE rel
  MATCH (u:User {displayName:'<user_displayName>'}), (r_new:Role {roleName:'<newRole>'})
  MERGE (u)-[:HAS_ROLE]->(r_new)
  RETURN u, r_new
• If anything else, output DATA NOT FOUND.
"""

# --------------------- Utility Functions --------------------- #

def serialize_neo4j_data(record):
    """Convert Neo4j query result dictionary to a JSON-serializable format."""
    serialized_record = {}
    for key, value in record.items():
        if isinstance(value, DateTime):
            serialized_record[key] = value.isoformat()
        elif isinstance(value, dict):
            serialized_record[key] = serialize_neo4j_data(value)
        elif isinstance(value, list):
            serialized_record[key] = [
                serialize_neo4j_data(item) if isinstance(item, dict)
                else (item.isoformat() if isinstance(item, DateTime) else item)
                for item in value
            ]
        else:
            serialized_record[key] = value
    return serialized_record

def summarize_history(user_history):
    """
    Summarizes user history using the Azure OpenAI API.
    """
    if not user_history:
        return "No prior history available."
    summary_messages = [
        {"role": "system", "content": "Summarize the following user history shortly for context."},
        {"role": "user", "content": user_history}
    ]
    summary_payload = {
        "messages": summary_messages,
        "max_tokens": 150,
        "temperature": 0.5
    }
    try:
        response = requests.post(azure_api_endpoint, headers=headers, json=summary_payload, timeout=30)
        response.raise_for_status()
        response_data = response.json()
        return response_data["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error("Error in summarize_history: %s", str(e))
        return "Error summarizing history."

def extract_ids(rec):
    """
    Given a record dict from Neo4j (which might contain 'u', 'g', 'r'),
    return (userId, otherId) for syncing to Azure.
    """
    u = rec.get("u") or {}
    g = rec.get("g") or {}
    r = rec.get("r") or {}
    # For role-sync, otherId = r.get("id"); for group-sync, otherId = g.get("id")
    return u.get("id"), (g or r).get("id")

def sync_to_azure(query, records):
    """
    Given a Neo4j mutation query and the list of record dicts that came back from Neo4j,
    call your BACKEND_BASE_URL endpoints to reflect the same change in Azure AD.
    """
    pairs = [extract_ids(r) for r in records if r]
    for uid, vid in pairs:
        if not uid or not vid:
            continue

        # (1) Removing an AD-group membership?
        if re.search(r"DELETE.*\(u:User\)-\[:MEMBER_OF\]->\(g:Group\)", query, re.IGNORECASE):
            pr = requests.delete(
                f"{backend_base_url}/api/removeUserFromGroup",
                params={"groupId": vid, "userId": uid}, timeout=10
            )
            logger.info("Azure removeUserFromGroup → %s", pr.status_code)

        # (2) Adding an AD-group membership?
        elif re.search(r"MERGE.*\(u:User\)-\[:MEMBER_OF\]->\(g:Group\)", query, re.IGNORECASE):
            pr = requests.post(
                f"{backend_base_url}/api/addUserToGroup",
                params={"groupId": vid, "userId": uid}, timeout=10
            )
            logger.info("Azure addUserToGroup → %s", pr.status_code)

        # (3) Assigning a directory role to a user?
        elif re.search(r"MERGE.*\(u:User\)-\[:HAS_ROLE\]->\(r:Role\)", query, re.IGNORECASE):
            pr = requests.post(
                f"{backend_base_url}/api/assignDirectoryRoleToUser",
                params={"userId": uid, "roleDefinitionId": vid}, timeout=10
            )
            logger.info("Azure assignDirectoryRoleToUser → %s", pr.status_code)

        # (4) Removing a directory role from a user?
        elif re.search(r"DELETE.*\(u:User\)-\[:HAS_ROLE\]->\(r:Role\)", query, re.IGNORECASE):
            pr = requests.delete(
                f"{backend_base_url}/api/removeDirectoryRoleFromUser",
                params={"userId": uid, "roleDefinitionId": vid}, timeout=10
            )
            logger.info("Azure removeDirectoryRoleFromUser → %s", pr.status_code)

        # (5) Update user’s role if LLM used a single SET … pattern. Usually #3/#4 handle it.
        elif re.search(r"SET.*\(u:User\)-\[:HAS_ROLE\]->\(r:Role\)", query, re.IGNORECASE):
            # If LLM used “SET” directly, ignore—#3/#4 should have fired if separate MERGE/DELETE lines exist.
            pass

def is_full_report_request(query: str) -> bool:
    q = query.lower()
    return any(phrase in q for phrase in [
        "generate a report", "summarize all data", "full report", "complete report", "summary of all data"
    ])

def run_full_report(session):
    report = []
    # Execute each of the ten predefined prompts with their metadata
    for entry in PROMPT_MAP:
        name = entry.get("description", ", ".join(entry["keywords"]))
        cypher = entry["cypher"]
        recommendation = entry.get("recommendation", "")
        insights = entry.get("insights", "")

        try:
            result = session.run(cypher)
            rows = [record.data() for record in result]
        except Exception as e:
            rows = []
        report.append({
            "name": name,
            "cypher": cypher.strip(),
            "result": rows,
            "insights": insights,
            "recommendation": recommendation
        })

    # Baseline entity dumps (optional but useful)
    extras = {
        "all_users": "MATCH (u:User) RETURN u.displayName AS displayName, u.userPrincipalName AS upn",
        "all_roles": "MATCH (r:Role) RETURN r.roleName AS roleName, r.description AS description",
        "all_groups": "MATCH (g:Group) RETURN g.displayName AS groupName",
        "all_resources": "MATCH (res:Resource) RETURN res.name AS resourceName, res.type AS type, res.sensitivity AS sensitivity, res.critical AS critical, res.restricted AS restricted",
        "recent_activity_summary": """
            MATCH (u:User)
            OPTIONAL MATCH (u)-[:PERFORMED]->(a:ActivityLog)
            WHERE a.activityDateTime >= datetime() - duration('P7D')
            OPTIONAL MATCH (u)-[:SIGNED_IN]->(s:SignInLog)
            WHERE s.createdDateTime >= datetime() - duration('P7D')
            RETURN u.displayName AS user,
                   count(DISTINCT a) AS activityEvents,
                   count(DISTINCT s) AS signInEvents
            ORDER BY (activityEvents + signInEvents) DESC
        """
    }
    for label, q in extras.items():
        try:
            res = session.run(q)
            rows = [record.data() for record in res]
        except Exception:
            rows = []
        report.append({
            "name": label,
            "cypher": q.strip(),
            "result": rows,
            "insights": "",
            "recommendation": ""
        })

    return report


# --------------------- Endpoints --------------------- #
@app.route('/chat-azure-action', methods=['GET', 'POST'])
def generate_cypher_query_and_azure_action():
    # Try JSON body first, then form args
    data = request.get_json(silent=True) or {}
    user_query    = data.get("user_query")    or request.values.get("user_query")
    history       = data.get("history", "")   or request.values.get("history", "")
    model_version = data.get("model_version", "gpt4") or request.values.get("model_version", "gpt4")
    session_id    = data.get("session_id")    or request.values.get("session_id")
    # Generate a session_id if missing
    if not session_id:
        session_id = str(uuid.uuid4())

    if not user_query:
        return jsonify(status="error", message="user_query required"), 400

    # Choose prompt based on model_version
    if model_version.lower().startswith("management"):
        system_prompt = """
You are an enterprise AI whose job is to generate Cypher mutations that ALTER a user's Azure AD roles (add, remove, update).
• Only use MATCH, MERGE, DELETE on (:User) and (:Role).
For example:
  assign role <roleName> to user <displayName>:
    MATCH (u:User {displayName:'<displayName>'}), (r:Role {roleName:'<roleName>'})
    MERGE (u)-[:HAS_ROLE]->(r)
    RETURN u, r
If you can’t interpret, output DATA NOT FOUND.
"""
    else:
        system_prompt = ACTION_ENTERPRISE_PROMPTS

    socketio.emit("progress", {"status": "Generating Cypher mutation queries..."}, room=session_id)

    # Call Azure OpenAI
    endpoint = azure_api_endpoint if model_version.endswith("gpt4") else azure_api_endpoint_gpt4o
    resp = requests.post(
        endpoint,
        headers={"api-key": azure_api_key},
        json={
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user",   "content": user_query}
            ],
            "max_tokens": 2000,
            "temperature": 0.7
        },
        timeout=30
    )
    resp.raise_for_status()
    raw = resp.json()["choices"][0]["message"]["content"].replace("```", "")
    cypher_queries = [q.strip() for q in raw.split("\n\n") if q.strip()]

    # Execute on Neo4j and sync to Graph
    socketio.emit("progress", {"status": "Executing mutations on Neo4j..."}, room=session_id)
    results = []
    with driver.session(database=GRAPH_DATABASE) as sess:
        for cq in cypher_queries:
            try:
                res = sess.run(cq, timeout=30)
                recs = [serialize_neo4j_data(r.data()) for r in res]
                results.append({"query": cq, "result": recs})
                # If it affects HAS_ROLE, sync to Azure AD
                if any(k in cq.upper() for k in ("MERGE ", "DELETE ")) and ":HAS_ROLE" in cq:
                    sync_to_azure(cq, recs)
            except Exception as e:
                logger.error("Neo4j exec failed for '%s': %s", cq, e)
                results.append({"query": cq, "error": str(e)})

    # Final confirmation back to the user
    socketio.emit("progress", {"status": "Refining response..."}, room=session_id)
    formatted = "\n".join(str(r) for r in results)
    refine_prompt = f"""
Based on these mutations and results:
{formatted}

Confirm you’ve executed: {user_query}
Previous history: {history}
"""
    confirm_resp = requests.post(
        azure_api_endpoint,
        headers={"api-key": azure_api_key},
        json={
            "messages": [
                {"role": "system", "content": "You are an AI assistant specialized in Azure administration. Provide a clear confirmation of the write operations performed."},
                {"role": "user",   "content": refine_prompt}
            ],
            "max_tokens": 500,
            "temperature": 0.7
        },
        timeout=30
    )
    confirm_resp.raise_for_status()

    socketio.emit("progress", {"status": "Completed"}, room=session_id)
    return jsonify(
        cypher_queries=cypher_queries,
        neo4j_results=results,
        ai_final_response=confirm_resp.json()["choices"][0]["message"]["content"],
        session_id=session_id
    )

@app.route('/chat-azure-qa', methods=['GET','POST'])
def generate_cypher_query_and_azure_qa():
    """
    Endpoint to generate and validate a Cypher query, execute it on GraphDB,
    and then refine the AI response for Azure-related read-only queries.
    """
    try:
        data = request.get_json()
        user_query = data.get('user_query')
        history = data.get('history', '')
        model_version = data.get('model_version', 'gpt4')
        session_id = data.get('session_id')
        if not user_query or not session_id:
            return jsonify({"status": "error", "message": "user_query and session_id required"}), 400
        if is_full_report_request(user_query):
            socketio.emit('progress', {'status': 'Generating full report...'}, room=session_id)
            try:
                with (driver.session(database=GRAPH_DATABASE) as session_obj):
                    report_data = run_full_report(session_obj)
                    summary_sections = []
                    for section in report_data:
                                name = section.get("name", "Unnamed Section")
                                recs = section.get("recommendation", "")
                                ins = section.get("insights", "")
                                count = len(section.get("result", []))
                                summary_sections.append(
                                    f"**{name}**: {count} row(s)\nInsight: {ins}\nRecommendation: {recs}"
                                )
                                ai_final_response = "Full report generated. Sections:\n" + "\n\n".join(summary_sections)
                                socketio.emit('progress', {'status': 'Completed full report'}, room=session_id)
                                return jsonify({
                                    "report": report_data,
                                    "ai_final_response": ai_final_response,
                                    "session_id": session_id
                                })
            except Exception as e:
                logger.error("Full report generation failed, falling back to normal flow: %s", str(e))
        socketio.emit('progress', {'status': 'Generating Cypher query...'}, room=session_id)
        system_message = ENTERPRISE_PROMPTS
        messages = [
            {"role": "system", "content": system_message},
            {"role": "user", "content": user_query}
        ]
        payload = {
            "messages": messages,
            "max_tokens": 4000,
            "temperature": 0.7
        }
        chosen_endpoint = azure_api_endpoint if model_version == 'gpt4' else azure_api_endpoint_gpt4o
        response = requests.post(chosen_endpoint, headers=headers, json=payload, timeout=30)
        response.raise_for_status()
        response_data = response.json()
        cypher_queries_text = response_data["choices"][0]["message"]["content"].replace("```", "")
        logger.info("Cypher query text: %s", cypher_queries_text)
        socketio.emit('progress', {'status': 'Validating Cypher query...'}, room=session_id)

        validation_system_message = (
            "You are an AI assistant that reviews and validates Cypher queries for a Graph database. "
            "Return ONLY the corrected raw Cypher query text with no additional commentary."
        )
        validation_payload = {
            "messages": [
                {"role": "system", "content": validation_system_message},
                {"role": "user", "content": f"Validate and correct the following Cypher query:\n\n{cypher_queries_text}"}
            ],
            "max_tokens": 4000,
            "temperature": 0.5
        }
        validation_response = requests.post(azure_api_endpoint, headers=headers, json=validation_payload, timeout=30)
        validation_response.raise_for_status()
        validated_cypher_queries_text = validation_response.json()["choices"][0]["message"]["content"].replace("```", "")
        validated_cypher_queries = [q.strip() for q in validated_cypher_queries_text.strip().split("\n\n") if q.strip()]

        socketio.emit('progress', {'status': 'Executing Cypher query on GraphDB...'}, room=session_id)
        query_results = []
        with driver.session(database=GRAPH_DATABASE) as session:
            for cypher_query in validated_cypher_queries:
                try:
                    # Step 1: Remove comment lines (// and #)
                    clean_lines = [
                        line for line in cypher_query.splitlines()
                        if not line.strip().startswith("//") and not line.strip().startswith("#")
                    ]
                    clean_query = " ".join(clean_lines).strip()

                    # Step 2: Execute the cleaned query
                    logger.info("Executing cleaned Cypher query: %s", clean_query)
                    result = session.run(clean_query, timeout=30)

                    # Step 3: Serialize results
                    result_data = [serialize_neo4j_data(record.data()) for record in result]

                    # Step 4: Append to query_results
                    query_results.append({
                        "query": clean_query,
                        "result": result_data
                    })

                except Exception as e:
                    logger.error("Error executing query: %s\nException: %s", cypher_query, str(e))
                    query_results.append({
                        "query": cypher_query,
                        "error": str(e)
                    })


        if not any(item.get("result") for item in query_results) and "list all users" in user_query.lower():
            fallback_query = "MATCH (u:User) RETURN u"
            logger.info("No data from validated queries. Running fallback query: %s", fallback_query)
            with driver.session(database=GRAPH_DATABASE) as session:
                fallback_result = session.run(fallback_query, timeout=30)
                fallback_data = [serialize_neo4j_data(record.data()) for record in fallback_result]
            if fallback_data:
                query_results.append({
                    "query": fallback_query,
                    "result": fallback_data
                })

        if not any(item.get("result") for item in query_results):
            ai_final_response = ("No data was retrieved from the Graph database. "
                                 "Please ensure that the pushToNeo4j endpoint has been triggered to sync data from Azure AD.")
            summarized_history = summarize_history(history + " " + ai_final_response)
            socketio.emit('progress', {'status': 'Completed'}, room=session_id)
            return jsonify({
                "validated_cypher_queries": validated_cypher_queries,
                "neo4j_results": query_results,
                "ai_final_response": ai_final_response,
                "history": summarized_history
            })

        socketio.emit('progress', {'status': 'Refining AI response with Graph DB data...'}, room=session_id)
        friendly_system_message = (
            "You are an AI assistant specialized in Azure administration. "
            "Based on the data from the Graph database representing Azure AD Users, Groups, and Roles, generate a clear and friendly answer in plain English that summarizes the results and provides actionable insights."
        )
        formatted_result_data = "\n".join([str(record) for record in query_results if record.get("result")])
        friendly_prompt = f"""
Here is the data retrieved from the Graph database:
{formatted_result_data}

Please provide a clear and friendly answer to the following query:
{user_query}

Include any insights or relevant details to help an administrator understand the results.
Previous conversation history: {history}
"""
        friendly_payload = {
            "messages": [
                {"role": "system", "content": friendly_system_message},
                {"role": "user", "content": friendly_prompt}
            ],
            "max_tokens": 4000,
            "temperature": 0.7
        }
        friendly_response = requests.post(azure_api_endpoint, headers=headers, json=friendly_payload, timeout=30)
        friendly_response.raise_for_status()
        friendly_response_text = friendly_response.json()["choices"][0]["message"]["content"]

        socketio.emit('progress', {'status': 'Summarizing history...'}, room=session_id)
        summarized_history = summarize_history(history + " " + friendly_response_text)
        socketio.emit('progress', {'status': 'Completed'}, room=session_id)
        return jsonify({
            "validated_cypher_queries": validated_cypher_queries,
            "neo4j_results": query_results,
            "ai_final_response": friendly_response_text,
            "history": summarized_history
        })
    except Exception as e:
        logger.error("Error in /chat-azure-qa: %s", str(e))
        return jsonify({"status": "error", "message": str(e)}), 500

# --------------------- SocketIO Events --------------------- #

@socketio.on('connect')
def handle_connect():
    session_id = str(uuid.uuid4())
    join_room(session_id)
    emit('session_id', {'session_id': session_id})


@app.route("/ask", methods=["POST"])
def ask():
    user_prompt = normalize(request.json.get("prompt", ""))

    for entry in PROMPT_MAP:
        if any(keyword in user_prompt for keyword in entry["keywords"]):
            cypher_query = entry["cypher"]
            with driver.session() as session:
                result = session.run(cypher_query)
                data = [record.data() for record in result]
            return jsonify({"query": cypher_query, "result": data})

    return jsonify({"message": "No matching report found. Try rephrasing your prompt."})


# --------------------- Server Startup --------------------- #

if __name__ == '__main__':
    socketio.run(app, host="0.0.0.0", port=5550, debug=True, use_reloader=False)
