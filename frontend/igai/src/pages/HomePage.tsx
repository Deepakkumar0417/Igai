import React, { useEffect, useRef, useState } from "react";
import "./HomePage.css";
import "react-tooltip/dist/react-tooltip.css";
import { assets } from "../assets/assets";
import { AIContext } from "../context/AIContext";
import io from "socket.io-client";

const socket = io(import.meta.env.VITE_AI_SOCKET_URL);

const HomePage: React.FC = () => {
  const context = React.useContext(AIContext);
  const [sessionId, setSessionId] = useState("");
  const [status, setStatus] = useState("");
  const messagesEndRef = useRef<HTMLDivElement>(null);

  if (!context) {
    return <div>Error: AIContext is not available</div>;
  }

  const {
    onQASent,
    onActionSent,
    prevPrompts,
    showResult,
    loading,
    input,
    setInput,
    handleAddRemove,
  } = context;

  useEffect(() => {
    // Listen for session and progress events from Socket.io
    socket.on("session_id", (data) => {
      setSessionId(data.session_id);
      console.log("Session ID:", data.session_id);
    });
    socket.on("progress", (data) => {
      console.log("Progress:", data);
      if (data.status === "Completed") {
        setStatus("");
      } else {
        setStatus(data.status);
      }
    });
  }, []);

  // Auto-scroll when new messages arrive or loading state changes
  useEffect(() => {
    if (messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [prevPrompts, loading]);

  return (
    <div style={{ display: "flex", minHeight: "100vh" }}>
      <div className="main">
        <div className="main-container">
          <div className="nav">
            <div className="nav-logo">
              <p>iGai Agent</p>
            </div>
            <div className="services">
              <a target="_blank" rel="noopener noreferrer" href="/azure">
                <button>Azure</button>
              </a>
              <a target="_blank" rel="noopener noreferrer" href="/servicenow">
                <button>ServiceNow</button>
              </a>
            </div>
          </div>
          {/* Show greeting cards if no result is to be shown */}
          {!showResult ? (
            <>
              <div className="greet">
                <p>
                  <span>How can I help you today?</span>
                </p>
              </div>
              <div className="cards">
                <div
                  className="card"
                  onClick={() =>
                    setInput(
                      " Can you provide the recent activity logs for all users, including timestamps and key actions performed?"
                    )
                  }
                >
                  <p>
                    Can you provide the recent activity logs for all users,
                    including timestamps and key actions performed?
                  </p>
                  <img src={assets.time_icon} alt="Time Icon" />
                </div>
                <div
                  className="card"
                  onClick={() =>
                    setInput(
                      "List all user roles along with the last accessed time for each role across the system."
                    )
                  }
                >
                  <p>
                    List all user roles along with the last accessed time for
                    each role across the system.
                  </p>
                  <img src={assets.users_icon} alt="Users Icon" />
                </div>
                <div
                  className="card"
                  onClick={() =>
                    setInput(
                      "Provide a summary of recent activities, including logins, role accesses, and updates, for all users."
                    )
                  }
                >
                  <p>
                    Provide a summary of recent activities, including logins,
                    role accesses, and updates, for all users.
                  </p>
                  <img src={assets.search_icon} alt="Search Icon" />
                </div>
                <div
                  className="card"
                  onClick={() =>
                    setInput(
                      "Fetch detailed user information, such as usernames, roles, and last login times."
                    )
                  }
                >
                  <p>
                    Fetch detailed user information, such as usernames, roles,
                    and last login times.
                  </p>
                  <img src={assets.graph_icon} alt="Graph Icon" />
                </div>
              </div>
            </>
          ) : (
            <div className="result">
              {prevPrompts.map((msg, index) => {
                const isLastMessage = index === prevPrompts.length - 1;
                return (
                  <div key={index}>
                    <div className={`message ${msg.sender}`}>
                      <div className="message-icon">
                        <img
                          src={
                            msg.sender === "user"
                              ? assets.user_icon
                              : loading && msg.message === ""
                              ? assets.ai_gif
                              : assets.ai_icon
                          }
                          alt={
                            msg.sender === "user" ? "User Icon" : "AI Icon"
                          }
                        />
                      </div>
                      <div className="message-content" />
                      {loading && msg.sender === "ai" && msg.message === "" && (
                        <div className="loader">
                          <hr />
                          <p>{status}</p>
                          <hr />
                        </div>
                      )}
                      <p
                        dangerouslySetInnerHTML={{ __html: msg.message }}
                      ></p>
                      <div ref={messagesEndRef} />
                    </div>
                    {/* Render policy/group action buttons if present */}
                    {(msg.addPoliciesToUser ||
                      msg.addTemporaryPoliciesToUser ||
                      msg.reovePoliciesFromUser ||
                      msg.addPoliciesToRole ||
                      msg.addTemporaryPoliciesToRole ||
                      msg.reovePoliciesFromRole ||
                      msg.addPoliciesToGroup ||
                      msg.addTemporaryPoliciesToGroup ||
                      msg.reovePoliciesFromGroup ||
                      msg.addUserToGroup) && (
                      <div className="policy-actions-container">
                        {msg.addPoliciesToUser?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Attach policy <strong>{value.name}</strong> to user{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addTemporaryPoliciesToUser?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Temporarily attach policy{" "}
                            <strong>{value.name}</strong> to user{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.reovePoliciesFromUser?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Remove policy <strong>{value.name}</strong> from user{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addPoliciesToRole?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Attach policy <strong>{value.name}</strong> to role{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addTemporaryPoliciesToRole?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Temporarily attach policy{" "}
                            <strong>{value.name}</strong> to role{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.reovePoliciesFromRole?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Remove policy <strong>{value.name}</strong> from role{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addPoliciesToGroup?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Attach policy <strong>{value.name}</strong> to group{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addTemporaryPoliciesToGroup?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Temporarily attach policy{" "}
                            <strong>{value.name}</strong> to group{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.reovePoliciesFromGroup?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Remove policy <strong>{value.name}</strong> from group{" "}
                            <strong>{value.username}</strong>
                          </button>
                        ))}
                        {msg.addUserToGroup?.map((value, idx) => (
                          <button
                            key={idx}
                            className={`policy-button ${
                              !isLastMessage ? "disabled-button" : ""
                            }`}
                            onClick={() =>
                              isLastMessage && handleAddRemove(value.url)
                            }
                            disabled={!isLastMessage}
                          >
                            Add user <strong>{value.username}</strong> to group{" "}
                            <strong>{value.name}</strong>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
          <div className="main-bottom">
            <div className="search-box">
              <input
                onKeyDown={(e) => {
                  if (input && e.key === "Enter") {
                    if (input.length > 0) {
                      onQASent("gpt4", sessionId);
                    } else {
                      alert("Your prompt cannot be empty");
                    }
                  }
                }}
                onChange={(e) => setInput(e.target.value)}
                autoFocus
                value={input}
                type="text"
                placeholder="Enter a prompt here"
              />
              <div className="search-button">
                <label
                  onClick={() => {
                    if (input.length > 0) {
                      onQASent("gpt4", sessionId);
                    } else {
                      alert("Your prompt cannot be empty");
                    }
                  }}
                >
                  Data - gpt4
                </label>
                <label
                  onClick={() => {
                    if (input.length > 0) {
                      onQASent("gpt4o", sessionId);
                    } else {
                      alert("Your prompt cannot be empty");
                    }
                  }}
                >
                  Data - gpt4o
                </label>
                <label
                  onClick={() => {
                    if (input.length > 0) {
                      onActionSent("gpt4", sessionId);
                    } else {
                      alert("Your prompt cannot be empty");
                    }
                  }}
                >
                  Management - gpt4
                </label>
                <label
                  onClick={() => {
                    if (input.length > 0) {
                      onActionSent("gpt4o", sessionId);
                    } else {
                      alert("Your prompt cannot be empty");
                    }
                  }}
                >
                  Management - gpt4o
                </label>
              </div>
            </div>
            <p className="bottom-info">
              iGai may display inaccurate info, including about people, so double-check its responses.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
};

export default HomePage;
