import os
import json
import logging
import atexit
import uuid
import re
from threading import Thread
import re
import requests


from flask import Flask, request, jsonify
from flask_cors import CORS
from flask_socketio import SocketIO, emit, join_room
import requests
from neo4j import GraphDatabase
from neo4j.time import DateTime
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

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

# --------------------- Enterprise Prompt Guidelines --------------------- #
# This prompt instructs the LLM to generate either read-only or modification (CRUD) queries.
# Modification queries (CREATE, MERGE, SET, DELETE) are allowed if the user command requires updating data.
# All queries must be returned as raw text without markdown formatting.

ENTERPRISE_PROMPTS = """
You are an enterprise-level AI assistant responsible for generating raw Cypher queries to fetch or modify data from a Graph database containing Azure AD entities and additional Azure portal data. Your output must be solely raw Cypher query text with no markdown formatting, no triple backticks, and no additional commentary.
If any Answer is Unknown, but the synonyms or words are related to database publish that data related to that word from database.
If any thing doesnt matches you can answer with the "DATA NOT FOUND".

Entity Mappings:
- Users: Nodes labeled "User" with property "displayName".
- Groups: Nodes labeled "Group" with property "displayName".
- Roles: Nodes labeled "Role". If a role property "roleName" exists, use that; otherwise, assume roles have a property "displayName".
- Policies: Nodes labeled "Policy" with property "policyName".
- Department: Nodes labeled "Department" with property "name".
- Resources: Nodes labeled "Resource" with property "resourceName".

Relationship Mappings:
- Users are linked to Groups via the relationship [:MEMBER_OF].
- Users are linked to Roles via the relationship [:HAS_ROLE].
- Department are linked to Groups via the relationship [:NESTED_UNDER].
- Some Department are linked to other Groups and Department via the relationship [:HAS_AD_GROUP].

Query Generation Guidelines:
1. For single-entity queries, generate a simple, single-line query. For example:
   list all users → MATCH (u:User) RETURN u
   list all groups → MATCH (g:Group) RETURN g
   list all department → MATCH(d:Department) RETURN d
   list all roles → MATCH(r:Roles) RETURN r 

show details for department <deptName>
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN d

show resources for department <deptName>
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN d.resources

2. For multi-entity or joined queries, generate queries that correctly join the involved nodes. For example:
   list all roles and the users who are assigned to those roles →
   MATCH (u:User)-[:HAS_ROLE]->(r:Role) RETURN r, u
   or
   MATCH (r:Role)<-[:HAS_ROLE]-(u:User) RETURN r.roleName, u.displayName

3. For queries with filters (e.g., "list all users from Physicians group" or "number of users in Physicians group"), apply filtering on the proper property. For example:
   MATCH (u:User)-[:MEMBER_OF]->(g:Group)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS 'physicians')
   RETURN u
   or
   MATCH (u:User)-[:MEMBER_OF]->(g:Group)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS 'physicians')
   RETURN count(u)

4. For multi-keyword search inputs (e.g., "Robert Davis"), split the input into individual keywords and generate separate CONTAINS conditions combined with OR. For example:
   MATCH (u:User)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS 'robert')
     OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS 'davis')
   RETURN u

5. When multiple candidate queries are necessary to ensure data is fetched or modified, output each candidate as a separate single-line query. Separate each query with a blank line and output only the raw text.

6. Generate modification queries (using CREATE, MERGE, SET, or DELETE) if the user request calls for an update, assignment, removal, or other data modification. For example, for "Assign user Deepak to Front desk group" you might generate:
   MATCH (u:User)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.displayName, ' ', '_')), '_') WHERE word CONTAINS 'deepak')
   WITH u
   MATCH (g:Group)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS 'front')
     AND ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS 'desk')
   MERGE (u)-[:MEMBER_OF]->(g)
   RETURN u, g
   
7. Multi-Entity Department↔Group Queries
list all AD groups for <deptName> department
MATCH (d:Department)-[:HAS_AD_GROUP]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN g

list all nested groups under <deptName> department
MATCH (d:Department)-[:NESTED_UNDER]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN g

list all departments with their AD groups
MATCH (d:Department)-[:HAS_AD_GROUP]->(g:Group) RETURN d, g

list all departments and their nested groups
MATCH (d:Department)-[:NESTED_UNDER]->(g:Group) RETURN d, g

Department↔User/Role Joined Queries
list all users in <deptName> department
MATCH (u:User)-[:BELONGS_TO_DEPARTMENT]->(d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN u

list all roles in <deptName> department
MATCH (r:Role)-[:ASSIGNED_TO_DEPARTMENT]->(d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN r

Count/Aggregation Queries
number of AD groups for <deptName> department
MATCH (d:Department)-[:HAS_AD_GROUP]->(g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN count(g)

count of users in <deptName> department
MATCH (u:User)-[:BELONGS_TO_DEPARTMENT]->(d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
RETURN count(u)

Department Modification Queries
create a new department named "<name>" with description "<desc>" and resources "<res>"
CREATE (d:Department {name: '<name>', description: '<desc>', resources: '<res>'})

assign AD group <groupName> to department <deptName>
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<deptKeyword>')
WITH d
MATCH (g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '')), '') WHERE word CONTAINS '<groupKeyword>')
MERGE (d)-[:HAS_AD_GROUP]->(g)
RETURN d, g

nest department <deptName> under group <groupName>
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<deptKeyword>')
WITH d
MATCH (g:Group)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '')), '') WHERE word CONTAINS '<groupKeyword>')
MERGE (d)-[:NESTED_UNDER]->(g)
RETURN d, g

update resources for department <deptName> to "<newRes>"
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
SET d.resources = '<newRes>'
RETURN d

delete department <deptName>
MATCH (d:Department)
WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
DETACH DELETE d

8. Always perform strict query validation:
   - Split multi-word inputs into individual words.
   - Do not rely on whole-word matching; check each split word with CONTAINS.
   - Ensure relationships follow the correct direction.
   - Return only raw Cypher query text, with each query on a single line and separated by a blank line.
   
    ### *🔹 Strict Query Validation Rules*
            ⿡ *Always split multi-word input into individual words strictly* to improve accuracy—not for exact match searching.
            - This applies to *all entity properties* (userName, policyName, roleResourceName, etc.).
            - **If the input contains spaces, underscores (_), or no spaces, split words and validate them separately.**

            ⿢ *Do not check whole words with "CONTAINS"*—always split words and validate each separately.
            - *Spaced* ("Robert Davis") → **Split into ["robert", "davis"]**  
            - *Underscored* ("Admin_Write_Permission") → **Split into ["admin", "write", "permission"]**  
            - *Unspaced or CamelCase* ("SystemUpdate") → **Split into ["system", "update"]**  
            - *Quoted* ('Billing_Read') → *Split before checking "CONTAINS"*  

            ⿣ *Do not use direct property matching for spaced words.*
            - ❌ *Wrong:*  
                MATCH (u:User {userName: 'Robert Davis'})-[:MEMBER_OF]->(g:Group) RETURN g.groupName, g.groupId, g.groupResourceName
            - ✅ *Correct:*  
                MATCH (u:User)-[:MEMBER_OF]->(g:Group)
                WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.userName, ' ', '_')), '_') WHERE word CONTAINS 'robert')
                  OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.userName, ' ', '_')), '_') WHERE word CONTAINS 'davis')
                RETURN g.groupName, g.groupId, g.groupResourceName

            ⿤ *Perform separate "CONTAINS" checks* for *each split word* in individual queries.
            - ❌ *Wrong:* WHERE toLower(u.userName) CONTAINS 'robert davis' 
            - ✅ *Correct:*  
                WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(u.userName, ' ', '_')), '_') WHERE word CONTAINS 'robert')
                  OR ANY(word IN SPLIT(TOLOWER(REPLACE(u.userName, ' ', '_')), '_') WHERE word CONTAINS 'davis')

            ⿥ *Ensure correct entity relationships and direction:*
            - [:MEMBER_OF] → Links User to Group   
            - [:HAS_ROLE] → Links User and Group to Role  

            ⿦ *Queries must follow correct relationship direction*
            - If the @Query defines (r:Role)-[:TRUSTED_POLICY]->(p:Policy), then Cypher queries *must* follow this structure.
            - ❌ *Wrong:* MATCH (p:Policy)-[:TRUSTED_POLICY]->(r:Role)  
            - ✅ *Correct:* MATCH (r:Role)-[:TRUSTED_POLICY]->(p:Policy)

            ⿧ *Query Formatting Rules*
            - *Ensure a blank line between multiple queries.*
            - **Do not return queries where properties directly match spaced words.**
            - **Return only the raw Cypher query text.**

9. Synonym Support
   • Before generating a query, map any user-supplied entity term to its normalized label:
     – if the prompt contains any User synonyms → treat as :User  
     – if Group synonyms → :Group  
     – if Department synonyms → :Department  

10. Department–Group Relationship Queries (showing both rel types)
    • list all AD groups for <deptName> department →
      MATCH (d:Department)-[rel:HAS_AD_GROUP|:NESTED_UNDER]->(g:Group)
      WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name, ' ', '')), '') WHERE word CONTAINS '<keyword>')
      RETURN d, type(rel) AS relation, g

    • list all departments with nested groups and AD groups →
      MATCH (d:Department)-[rel:HAS_AD_GROUP|:NESTED_UNDER]->(g:Group)
      RETURN d, type(rel) AS relation, g
      
      ### Recursive Nested Group & Department Traversal
list all groups with their nested sub-groups and parent departments
MATCH (g:Group)
OPTIONAL MATCH path=(g)-[:NESTED_UNDER*1..$depth]->(sub:Group)
OPTIONAL MATCH (d:Department)-[:NESTED_UNDER]->(g)
RETURN 
  g                          AS group, 
  collect(DISTINCT sub)      AS nestedGroups, 
  collect(DISTINCT d)        AS parentDepartments

### Shallow Nested Lookup by Name
list nested structure for group <groupName> to depth <depth>
MATCH (g:Group)
WHERE ANY(w IN SPLIT(TOLOWER(REPLACE(g.displayName,' ','_')),'_') WHERE w CONTAINS $groupKeyword)
OPTIONAL MATCH path=(g)-[:NESTED_UNDER*1..$depth]->(sub:Group)
OPTIONAL MATCH (d:Department)-[:NESTED_UNDER]->(g)
RETURN 
  g                          AS group, 
  collect(DISTINCT sub)      AS nestedGroups, 
  collect(DISTINCT d)        AS parentDepartments
  
  
  list all AD groups for <deptName> department →
   MATCH (d:Department)-[:HAS_AD_GROUP]->(g:Group)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name,' ','_')),'_') WHERE word CONTAINS '<keyword>')
   RETURN g

   list all nested groups under <deptName> department →
   MATCH (d:Department)-[:NESTED_UNDER]->(g:Group)
   WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(d.name,' ','_')),'_') WHERE word CONTAINS '<keyword>')
   RETURN g

   list all departments with nested groups and AD groups →
   MATCH (d:Department)-[rel:HAS_AD_GROUP|:NESTED_UNDER]->(g:Group)
   RETURN d, type(rel) AS relation, g

11. Combined Entity Details Query
    • When a user asks “list all users with their role, group and department details”, use OPTIONAL MATCH:
      MATCH (u:User)
      OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
      OPTIONAL MATCH (u)-[:MEMBER_OF]->(g:Group)
      OPTIONAL MATCH (d:Department)-[:HAS_AD_GROUP|:NESTED_UNDER]->(g)
      RETURN u, collect(DISTINCT r) AS roles, collect(DISTINCT g) AS groups, collect(DISTINCT d) AS departments

12. Disambiguate user-roles vs. group-roles
    • If the user asks for “users with their role details in the <groupName> group”, always treat “roles” as the roles assigned to each user:
      MATCH (u:User)-[:MEMBER_OF]->(g:Group)
      WHERE ANY(word IN SPLIT(TOLOWER(REPLACE(g.displayName, ' ', '_')), '_') WHERE word CONTAINS '<groupKeyword>')
      OPTIONAL MATCH (u)-[:HAS_ROLE]->(r:Role)
      RETURN u, collect(DISTINCT r) AS roles

13. Ambiguity Resolution
   • Entity ambiguity: map synonyms (users ↔ :User, teams ↔ :Group, depts ↔ :Department).
   • Relationship ambiguity: “members of” → [:MEMBER_OF], “assigned to” → [:HAS_ROLE].
   • Action vs retrieval: verbs “list/show/get” → MATCH+RETURN; “assign/create/delete/update” → write ops.
   • Fallback on multiple candidates: output each as separate queries.

14. Automatic Schema Discovery
- List all node labels →
  MATCH (n)
  UNWIND labels(n) AS label
  RETURN DISTINCT label

- List all relationship types →
  MATCH ()-[r]->()
  RETURN DISTINCT type(r)

- List all property keys on a label →
  CALL apoc.meta.nodeTypeProperties() YIELD nodeType, propertyName
  WHERE nodeType = '<Label>'
  RETURN DISTINCT propertyName

15. Generic Entity Mapping
• Normalize any entity term (user, group, dept, role, policy, resource, project, asset, device, location, team, division, etc.) to `:<CapitalizedLabel>`
• Coalesce multiple display properties: coalesce(n.name, n.displayName, n.title, n.label) AS displayName
• ID lookup fallback: if prompt includes “ID” → MATCH (n:<Label> {id:$id}) RETURN n

16. Synonym & Alias Support
- user/person/member/operator → User
- group/team/circle → Group
- dept/division/unit → Department
- role/perm/privilege → Role
- policy/pol → Policy
- resource/res → Resource
- project/proj → Project
- device/dev → Device
- location/loc → Location

17. Parameterized Filtering
- Single-token match → WHERE ANY(w IN SPLIT(TOLOWER(REPLACE(n.displayName,' ','_')),'_') WHERE w CONTAINS $keyword)
- Multi-token match → combine ANY(...) OR ANY(...) for each token
- Date/numeric range → WHERE n.prop >= $from AND n.prop <= $to

18. Core Query Templates
A. Single-Entity Retrieval: “list all <entity>”
B. Filtered List: “list all <entity> where <prop> contains <value>”
C. Relationship Traversal: “list <relatedEntity> for <entity> <name> via <relType>”
D. Joined Entities: “list <entityA> with their <entityB> details”
E. Count/Aggregation: “count <entityB> for <entityA> <name>”
F. Modification Patterns: CREATE, MERGE, SET, DELETE templates

19. Advanced Features
A. Pagination & Sorting: LIMIT, SKIP, ORDER BY based on prompt (“first N”, “skip M”, “sorted by <prop>”)
B. Recursive Traversal: MATCH path=(start)-[*1..$depth]-(end) RETURN path
C. Shortest Path: MATCH p=shortestPath((a:<LabelA> {id:$id1})-[*]-(b:<LabelB> {id:$id2})) RETURN p
D. Schema Introspection Wrap: if prompt “show me the schema” → run discovery queries

20. Error Handling & Fallbacks
- Return empty list if no matches.
- If ambiguous label → output both candidate queries, blank line separated.

21. Best Practices
- Always use parameter placeholders ($param).
- Always split and lowercase tokens.
- Always include LIMIT & ORDER BY when requested.
- Always return only raw Cypher, one query per line, blank line between candidates, no markdown or commentary.
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

# --------------------- Endpoints --------------------- #

@app.route('/chat-azure-action', methods=['GET','POST'])
def generate_cypher_query_and_azure_action():
    try:
        user_query    = request.args.get('user_query')
        history       = request.args.get('history','')
        model_version = request.args.get('model_version','gpt4')
        session_id    = request.args.get('session_id')
        if not user_query or not session_id:
            return jsonify({"status":"error","message":"user_query and session_id required"}),400

        # 1️⃣ Generate only mutations
        socketio.emit('progress',{'status':'Generating Cypher mutation queries...'},room=session_id)
        messages = [
            {"role":"system","content":ACTION_ENTERPRISE_PROMPTS},
            {"role":"user",  "content":user_query}
        ]
        payload = {"messages":messages, "max_tokens":2000, "temperature":0.7}
        endpoint = azure_api_endpoint if model_version=='gpt4' else azure_api_endpoint_gpt4o
        resp     = requests.post(endpoint, headers=headers, json=payload, timeout=30)
        resp.raise_for_status()
        raw = resp.json()["choices"][0]["message"]["content"].replace("```","")
        cypher_queries = [q.strip() for q in raw.split("\n\n") if q.strip()]

        # Helpers
        def is_mutation(q):
            return any(k in q.upper() for k in ("CREATE ","MERGE ","SET ","DELETE "))

        def extract_ids(rec):
            u = rec.get("u") or {}
            g = rec.get("g") or {}
            r = rec.get("r") or {}
            return u.get("id"), (g or r).get("id")

        def sync_to_azure(query, records):
            pairs = [extract_ids(r) for r in records if r]
            for uid, vid in pairs:
                if not uid or not vid:
                    continue
                # remove membership?
                if re.search(r"DELETE.*\(u:User\)-\[:MEMBER_OF\]->\(g:Group\)", query, re.IGNORECASE):
                    pr = requests.delete(f"{backend_base_url}/api/removeUserFromGroup",
                                         params={"groupId":vid,"userId":uid}, timeout=10)
                    logger.info("Azure removeUserFromGroup → %s", pr.status_code)
                # add membership?
                elif re.search(r"MERGE.*\(u:User\)-\[:MEMBER_OF\]->\(g:Group\)", query, re.IGNORECASE):
                    pr = requests.post(f"{backend_base_url}/api/addUserToGroup",
                                       params={"groupId":vid,"userId":uid}, timeout=10)
                    logger.info("Azure addUserToGroup → %s", pr.status_code)
                # assign role?
                elif re.search(r"MERGE.*\(u:User\)-\[:HAS_ROLE\]->\(r:Role\)", query, re.IGNORECASE):
                    pr = requests.post(f"{backend_base_url}/api/assignDirectoryRoleToUser",
                                       params={"userId":uid,"roleDefinitionId":vid}, timeout=10)
                    logger.info("Azure assignDirectoryRoleToUser → %s", pr.status_code)

        # 2️⃣ Execute & mirror
        socketio.emit('progress',{'status':'Executing mutations on Neo4j...'},room=session_id)
        query_results = []
        with driver.session(database=GRAPH_DATABASE) as sess:
            for cq in cypher_queries:
                try:
                    res  = sess.run(cq, timeout=30)
                    recs = [serialize_neo4j_data(r.data()) for r in res]
                    query_results.append({"query":cq,"result":recs})
                    if is_mutation(cq):
                        sync_to_azure(cq, recs)
                except Exception as e:
                    logger.error("Neo4j exec failed for '%s': %s", cq, e)
                    query_results.append({"query":cq,"error":str(e)})

        # 3️⃣ Refine AI response
        socketio.emit('progress',{'status':'Refining response...'},room=session_id)
        formatted = "\n".join(str(r) for r in query_results)
        refine_prompt = f"""
Based on these mutations and results:
{formatted}

Confirm you’ve executed: {user_query}
Previous history: {history}
"""
        final_payload = {
            "messages":[
                {"role":"system","content":
                    "You are an AI assistant specialized in Azure administration. "
                    "Provide a clear confirmation of the write operations performed."
                 },
                {"role":"user","content":refine_prompt}
            ],
            "max_tokens":1500,"temperature":0.7
        }
        fresp = requests.post(azure_api_endpoint, headers=headers, json=final_payload, timeout=30)
        fresp.raise_for_status()
        ai_final = fresp.json()["choices"][0]["message"]["content"]

        socketio.emit('progress',{'status':'Completed'},room=session_id)
        return jsonify({
            "cypher_queries":   cypher_queries,
            "neo4j_results":    query_results,
            "ai_final_response": ai_final,
            "history":          summarize_history(history + " " + ai_final)
        })
    except Exception as e:
        logger.error("Error in /chat-azure-action: %s", e)
        return jsonify({"status":"error","message":str(e)}),500





@app.route('/chat-azure-qa', methods=['GET', 'POST'])
def generate_cypher_query_and_azure_qa():
    """
    Endpoint to generate and validate a Cypher query, execute it on GraphDB,
    and then refine the AI response for Azure-related queries.
    """
    try:
        data = request.get_json()
        user_query = data.get('user_query')
        history = data.get('history', '')
        model_version = data.get('model_version', 'gpt4')
        session_id = data.get('session_id')
        if not user_query or not session_id:
            return jsonify({"status": "error", "message": "user_query and session_id required"}), 400

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
                    query = cypher_query.replace("\n", " ").strip()
                    logger.info("Executing validated Cypher query: %s", query)
                    result = session.run(query, timeout=30)
                    result_data = [serialize_neo4j_data(record.data()) for record in result]
                    if not result_data:
                        logger.warning("No data returned for validated query: %s", query)
                    query_results.append({
                        "query": cypher_query,
                        "result": result_data
                    })
                except Exception as e:
                    logger.error("Error executing query %s: %s", cypher_query, str(e))
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

# --------------------- Server Startup --------------------- #

if __name__ == '__main__':
    socketio.run(app, host="0.0.0.0", port=5550, debug=True, use_reloader=False)
