import React, { createContext, ReactNode, useState } from "react";
import { marked } from "marked";
// Update the imported service functions to reflect Azure endpoints
import { chatWithActionAI, chatWithQAAI, handleAddRemoveWithAzureService } from "../service/apiService";

// Define the shape of the context value
interface Message {
  sender: "user" | "ai";
  message: string;
  // Updated keys to reflect role and group operations
  assignRoleToUser?: { name: string; url: string; userId: string; enable: boolean }[];
  assignRoleToGroup?: { name: string; url: string; groupId: string; enable: boolean }[];
  addUserToGroup?: { name: string; url: string; userId: string; enable: boolean }[];
}

interface ContextValue {
  prevPrompts: Message[];
  setPrevPrompts: React.Dispatch<React.SetStateAction<Message[]>>;
  onQASent: (prompt?: string, session_id?: string) => Promise<void>;
  onActionSent: (prompt?: string, session_id?: string) => Promise<void>;
  input: string;
  setInput: React.Dispatch<React.SetStateAction<string>>;
  showResult: boolean;
  loading: boolean;
  newChat: () => void;
  handleAddRemove: (url?: string) => Promise<void>;
}

// Create the context
export const AIContext = createContext<ContextValue | undefined>(undefined);

interface ContextProviderProps {
  children: ReactNode;
}

const AIContextProvider: React.FC<ContextProviderProps> = ({ children }) => {
  const [input, setInput] = useState<string>("");
  const [prevPrompts, setPrevPrompts] = useState<Message[]>([]);
  const [showResult, setShowResult] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);
  const [history, setHistory] = useState<string>("");

  // Animates the display of text word-by-word
  const delayPara = async (response: string, updateAIMessage: (updatedMessage: string) => void) => {
    const words = response.split(" ");
    let currentMessage = "";
  
    words.forEach((word, index) => {
      setTimeout(() => {
        currentMessage += word + " ";
        updateAIMessage(currentMessage.trim());
      }, 75 * index);
    });
  };

  const Markdown = async (markdownData: string): Promise<string> => {
    const parsedData = await marked.parse(markdownData);
    return parsedData;
  };

  const newChat = (): void => {
    setLoading(false);
    setShowResult(false);
    setPrevPrompts([]);
  };

  // Handles QA-style messages using the updated Azure endpoints
  const onQASent = async (model?: string, session?: string): Promise<void> => {
    if (loading) return;
    const session_id = session ? session : "session";
    const model_name = model ? model : "gpt4";
    const userMessage = input;
    setInput("");
    setLoading(true);
    setShowResult(true);
    const userEntry: Message = { sender: "user", message: userMessage };
    setPrevPrompts((prev) => [...prev, userEntry]);
    const aiEntry: Message = { sender: "ai", message: "" };
    setPrevPrompts((prev) => [...prev, aiEntry]);
  
    chatWithQAAI(userMessage, history, model_name, session_id)
      .then(async (response) => {
        setLoading(false);
        // Parse the response using Markdown
        const parsedResponse = await Markdown(
          `<h5>Answer based on ${model_name}</h5><br/>` + response.data.ai_final_response
        );
        setHistory(response.data.history);
        delayPara(parsedResponse, (updatedMessage) => {
          setPrevPrompts((prev) =>
            prev.map((msg, idx) =>
              idx === prev.length - 1 && msg.sender === "ai"
                ? { ...msg, message: updatedMessage }
                : msg
            )
          );
        });
      })
      .catch((error) => {
        console.log(error);
        setLoading(false);
        const errorMessage =
          error.response.data.message || error.response.statusText;
        delayPara(
          errorMessage.startsWith("429")
            ? "⚠️ Rate Limit Reached: Please try again after a minute. 🚀"
            : errorMessage,
          (updatedMessage) => {
            setPrevPrompts((prev) =>
              prev.map((msg, idx) =>
                idx === prev.length - 1 && msg.sender === "ai"
                  ? { ...msg, message: updatedMessage }
                  : msg
              )
            );
          }
        );
      });
  };

  // Handles action-style messages using the updated Azure endpoints
  const onActionSent = async (model?: string, session?: string): Promise<void> => {
    if (loading) return;
    const model_name = model ? model : "gpt4";
    const session_id = session ? session : "session";
    const userMessage = input;
    setInput("");
    setLoading(true);
    setShowResult(true);
    const userEntry: Message = { sender: "user", message: userMessage };
    setPrevPrompts((prev) => [...prev, userEntry]);
    const aiEntry: Message = { sender: "ai", message: "" };
    setPrevPrompts((prev) => [...prev, aiEntry]);
  
    chatWithActionAI(userMessage, history, model_name, session_id)
      .then(async (response) => {
        setLoading(false);
        const parsedResponse = await Markdown(
          `<h5>Answer based on ${model_name}</h5><br/>` + response.data.ai_final_response
        );
        setHistory(response.data.history);
        if (response.data.action_urls) {
          // Extract data based on Azure endpoint patterns
          const extractedData = {
            assignRoleToUser: [] as { name: string; url: string; userId: string; enable: boolean }[],
            assignRoleToGroup: [] as { name: string; url: string; groupId: string; enable: boolean }[],
            addUserToGroup: [] as { name: string; url: string; userId: string; enable: boolean }[],
          };
      
          response.data.action_urls.forEach((url: string) => {
            const urlParams = new URL(url);
            const params = new URLSearchParams(urlParams.search);
            // Update parameter extraction to match Azure endpoint patterns
            if (url.includes("/api/azure/user/attachcustomrole?")) {
              const userId = params.get("userId") || "";
              const customRoleId = params.get("customRoleId") || "";
              extractedData.assignRoleToUser.push({ name: customRoleId, url, userId, enable: true });
            } else if (url.includes("/api/azure/role/attachcustomrole?")) {
              // For directory role assignment; here we treat roleName as group identifier for simplicity
              const roleName = params.get("roleName") || "";
              const customRoleId = params.get("customRoleId") || "";
              extractedData.assignRoleToGroup.push({ name: customRoleId, url, groupId: roleName, enable: true });
            } else if (url.includes("/api/azure/group/attachcustomrole?")) {
              const groupName = params.get("groupName") || "";
              const customRoleId = params.get("customRoleId") || "";
              extractedData.assignRoleToGroup.push({ name: customRoleId, url, groupId: groupName, enable: true });
            } else if (url.includes("/api/azure/user/add/group?")) {
              const userId = params.get("userId") || "";
              const groupName = params.get("groupName") || "";
              extractedData.addUserToGroup.push({ name: groupName, url, userId, enable: true });
            }
          });
      
          setPrevPrompts((prev) =>
            prev.map((msg, idx) =>
              idx === prev.length - 1 && msg.sender === "ai"
                ? { ...msg, ...extractedData, message: "Manage Roles, Groups, and User Assignments" }
                : msg
            )
          );
        } else {
          delayPara(parsedResponse, (updatedMessage) => {
            setPrevPrompts((prev) =>
              prev.map((msg, idx) =>
                idx === prev.length - 1 && msg.sender === "ai"
                  ? { ...msg, message: updatedMessage }
                  : msg
              )
            );
          });
        }
      })
      .catch((error) => {
        console.log(error);
        setLoading(false);
        const errorMessage =
          error.response.data.message || error.response.statusText;
        delayPara(
          errorMessage.startsWith("429")
            ? "⚠️ Rate Limit Reached: Please try again after a minute. 🚀"
            : errorMessage,
          (updatedMessage) => {
            setPrevPrompts((prev) =>
              prev.map((msg, idx) =>
                idx === prev.length - 1 && msg.sender === "ai"
                  ? { ...msg, message: updatedMessage }
                  : msg
              )
            );
          }
        );
      });
  };

  const handleAddRemove = async (url?: string) => {
    const posturl = url || "";
    // Use the updated Azure service function name
    handleAddRemoveWithAzureService(posturl)
      .then((res) => {
        console.log(res.data);
        setPrevPrompts((prev) =>
          prev.map((msg, idx) =>
            idx === prev.length - 1 && msg.sender === "ai"
              ? {
                  ...msg,
                  message:
                    msg.message === ""
                      ? res.data
                      : msg.message + "<br/><br/>" + res.data,
                }
              : msg
          )
        );
      })
      .catch((error) => {
        console.log(error.response.data);
        setPrevPrompts((prev) =>
          prev.map((msg, idx) =>
            idx === prev.length - 1 && msg.sender === "ai"
              ? {
                  ...msg,
                  message:
                    msg.message === ""
                      ? error.response.data
                      : msg.message + "<br/><br/>" + error.response.data,
                }
              : msg
          )
        );
      });
  };

  const contextValue: ContextValue = {
    prevPrompts,
    setPrevPrompts,
    onQASent,
    input,
    setInput,
    showResult,
    loading,
    newChat,
    onActionSent,
    handleAddRemove,
  };

  return <AIContext.Provider value={contextValue}>{children}</AIContext.Provider>;
};

export default AIContextProvider;
