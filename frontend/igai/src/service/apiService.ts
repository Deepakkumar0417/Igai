import axios from 'axios';

// Axios instance for Azure AI endpoints
const apiAIClient = axios.create({
  baseURL: import.meta.env.VITE_AI_API_URL, // e.g., http://localhost:5550
  headers: {
    'Content-Type': 'application/json',
  },
});

// Axios instance for ServiceNow endpoints
const apiServiceNowClient = axios.create({
  baseURL: import.meta.env.VITE_SERVICENOW_API_URL, // e.g., http://localhost:XXXX
  headers: {
    'Content-Type': 'application/json',
  },
});

// ✅ Fetch Azure users from Neo4j using the chat-azure-qa endpoint
export const fetchAzureUsers = async () => {
  try {
    const response = await apiAIClient.get(`/chat-azure-qa`, {
      params: {
        user_query: 'list all users',
        history: '',
        model_version: 'gpt4',
        session_id: 'frontend-session-fetch-users',
      },
    });

    const result = response.data.neo4j_results?.[0]?.result;
    return Array.isArray(result) ? result : [];
  } catch (error) {
    console.error('Error fetching Azure users from Neo4j:', error);
    throw new Error('Failed to fetch users');
  }
};

// ✅ Fetch details of a specific Azure user
export const fetchAzureUserDetails = async (userName: string) => {
  try {
    const response = await apiAIClient.get(`/chat-azure-qa`, {
      params: {
        user_query: `Get complete details of user ${userName}`,
        history: '',
        model_version: 'gpt4',
        session_id: 'frontend-session-user-details',
      },
    });

    const result = response.data.neo4j_results?.[0]?.result?.[0];
    return result || {};
  } catch (error) {
    console.error('Error fetching user details:', error);
    throw new Error('Failed to fetch user details');
  }
};

// ❗ Optional: If using a dedicated last-access endpoint directly (not via AI)
export const fetchAzureUserLastAccessData = async (userId: string) => {
  try {
    const response = await apiAIClient.get(`/getlastaccessed/list`, {
      params: { userId },
    });
    return response.data;
  } catch (error) {
    console.error('Error fetching user last accessed data:', error);
    throw new Error('Failed to fetch user last access data');
  }
};

// 🔁 Handles natural language QA queries for Azure actions
export const chatWithQAAI = async (
  query: string,
  history: any,
  model: string,
  session_id: string
) => {
  return apiAIClient.post(`/chat-azure-qa`, {
    user_query: query,
    history: JSON.stringify(history),
    model_version: model,
    session_id,
  });
};

// Similarly update chatWithActionAI:
export const chatWithActionAI = async (
  query: string,
  history: any,
  model: string,
  session_id: string
) => {
  return apiAIClient.post(`/chat-azure-action`, {
    user_query: query,
    history: JSON.stringify(history),
    model_version: model,
    session_id,
  });
};

// 🔄 For POSTing any generated API URL (to add/remove user/role/group)
export const handleAddRemoveWithAzureService = async (url: string) => {
  try {
    return axios.post(url, {}, {
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    console.error('Error handling add/remove request:', error);
    throw new Error('Failed to perform operation');
  }
};

// ServiceNow user details endpoint
export const fetchServiceNowUserDetails = async (userId: string) => {
  try {
    const response = await apiServiceNowClient.get(`/get/user/info`, {
      params: { userId },
    });
    return response.data;
  } catch (error) {
    console.error('Error fetching ServiceNow user details:', error);
    throw new Error('Error fetching ServiceNow user details');
  }
};

// If needed, you can also create a function for fetching ServiceNow users.
// Example (similar to Azure users):
export const fetchServiceNowUsers = async () => {
  try {
    const response = await apiServiceNowClient.get(`/get/users`);
    // Adjust the data parsing as per your ServiceNow API response format.
    return response.data;
  } catch (error) {
    console.error('Error fetching ServiceNow users:', error);
    throw new Error('Failed to fetch ServiceNow users');
  }
};
