import React, { useState, useEffect } from 'react';
import {
  Box,
  TextField,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Button,
  CircularProgress,
  Avatar,
  Tabs,
  Tab,
} from '@mui/material';
import {
  fetchAzureUsers,
  fetchAzureUserDetails,
  chatWithQAAI,
  chatWithActionAI,
  handleAddRemoveWithAzureService,
} from '../../service/apiService';
import PersonIcon from '@mui/icons-material/Person';
import GroupIcon from '@mui/icons-material/Group';
import EventIcon from '@mui/icons-material/Event';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import { UserData } from '../../data/types';
import { marked } from 'marked';

const AzureUserManagement: React.FC = () => {
  const [searchTerm, setSearchTerm] = useState('');
  const [userData, setUserData] = useState<UserData[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserData | null>(null);
  const [userDetails, setUserDetails] = useState<any>(null);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [errorUsers, setErrorUsers] = useState<string | null>(null);
  const [errorDetails, setErrorDetails] = useState<string | null>(null);
  const [tabIndex, setTabIndex] = useState(0);

  // Chat states
  const [chatQuery, setChatQuery] = useState('');
  const [chatResponse, setChatResponse] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const [chatError, setChatError] = useState<string | null>(null);

  // Displays AI response word-by-word
  const delayPara = async (response: string, updateAIMessage: (updatedMessage: string) => void) => {
    const words = response.split(' ');
    let currentMessage = '';
    words.forEach((word, index) => {
      setTimeout(() => {
        currentMessage += word + ' ';
        updateAIMessage(currentMessage.trim());
      }, 75 * index);
    });
  };

  // Parses Markdown responses
  const Markdown = async (markdownData: string): Promise<string> => {
    return marked.parse(markdownData);
  };

  const newChat = (): void => {
    setChatLoading(false);
    setChatResponse('');
    setUserDetails(null);
    setSelectedUser(null);
    setSearchTerm('');
  };

  // Load Azure users on mount
  useEffect(() => {
    const loadData = async () => {
      setLoadingUsers(true);
      setErrorUsers(null);
      try {
        const data = await fetchAzureUsers();
        setUserData(data);
      } catch (err) {
        setErrorUsers('Failed to fetch user data. Please try again.');
      } finally {
        setLoadingUsers(false);
      }
    };
    loadData();
  }, []);

  // Count events with AccessDenied error
  const calculateAccessDeniedCount = (events: any[]) => {
    if (!events || events.length === 0) return 0;
    return events.filter((event) => event.errorCode === 'AccessDenied').length;
  };

  // Render compliance status icon
  const renderCompilationStatus = (events: any[]) => {
    const accessDeniedCount = calculateAccessDeniedCount(events);
    if (accessDeniedCount === 0) {
      return (
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'green',
          }}
        >
          <CheckCircleIcon sx={{ fontSize: 40, color: 'green' }} />
          <Typography variant="caption">No Compliance</Typography>
        </Box>
      );
    }
    return (
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'red',
        }}
      >
        <ErrorIcon sx={{ fontSize: 40, color: 'red' }} />
        <Typography variant="h6">{accessDeniedCount} Compliance</Typography>
      </Box>
    );
  };

  // Fetch user details when selected
  const fetchDetails = async (user: UserData) => {
    setSelectedUser(user);
    setLoadingDetails(true);
    setErrorDetails(null);
    try {
      const userDetailsData = await fetchAzureUserDetails(user.userResourceName);
      setUserDetails(userDetailsData);
    } catch (err) {
      setErrorDetails('Failed to fetch user details. Please try again.');
    } finally {
      setLoadingDetails(false);
    }
  };

  // QA Chat (read-only)
  const onQASent = async (model?: string, session?: string): Promise<void> => {
    if (chatLoading) return;
    const session_id = session ? session : 'session';
    const model_name = model ? model : 'gpt4';
    const userMessage = chatQuery;
    setChatQuery('');
    setChatLoading(true);
    try {
      const response = await chatWithQAAI(userMessage, '', model_name, session_id);
      const parsedResponse = await Markdown(response.data.ai_final_response);
      setChatResponse(parsedResponse);
    } catch (error: any) {
      setChatError(error.response?.data?.message || 'Failed to fetch chat response.');
    } finally {
      setChatLoading(false);
    }
  };

  // Action Chat (write/mutation)
  const onActionSent = async (model?: string, session?: string): Promise<void> => {
    if (chatLoading) return;
    const model_name = model ? model : 'management_gpt4';
    const session_id = session ? session : 'session';
    const userMessage = chatQuery;
    setChatQuery('');
    setChatLoading(true);
    try {
      const response = await chatWithActionAI(userMessage, '', model_name, session_id);
      const parsedResponse = await Markdown(response.data.ai_final_response);
      setChatResponse(parsedResponse);
    } catch (error: any) {
      setChatError(error.response?.data?.message || 'Failed to fetch chat response.');
    } finally {
      setChatLoading(false);
    }
  };

  // Filter users by search term
  const filteredUsers = userData.filter((user) =>
    user.userName.toLowerCase().includes(searchTerm.toLowerCase())
  );

  // Filter events for AccessDenied
  const filterAccessDeniedEvents = (events: any[]) => {
    if (!events) return [];
    return events.filter((event) => event.errorCode === 'AccessDenied' && event.errorMessage !== '');
  };

  // Filter other events
  const filterOtherEvents = (events: any[]) => {
    if (!events) return [];
    return events.filter((event) => event.errorCode !== 'AccessDenied');
  };

  // Render a generic table from data
  const renderTable = (data: any[], columns: string[]) => (
    <TableContainer component={Paper} sx={{ marginTop: 2 }}>
      <Table>
        <TableHead>
          <TableRow>
            {columns.map((col, index) => (
              <TableCell key={index} sx={{ fontWeight: 'bold', color: '#fff' }}>
                {col}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {data.map((row, index) => (
            <TableRow key={index}>
              {columns.map((col, idx) => (
                <TableCell key={idx} sx={{ color: '#fff' }}>
                  {row[col]}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );

  return (
    <Box sx={{ display: 'flex', height: '100vh', backgroundColor: '#121212', color: '#fff' }}>
      {/* Left Side – User Search & Chat */}
      <Box sx={{ flex: 1, overflowY: 'auto', borderRight: '1px solid #333', padding: 2 }}>
        <Typography variant="h4" sx={{ marginBottom: 2 }}>
          Azure User Search
        </Typography>
        {loadingUsers ? (
          <CircularProgress />
        ) : errorUsers ? (
          <Typography color="error">{errorUsers}</Typography>
        ) : (
          <>
            <TextField
              fullWidth
              variant="outlined"
              label="Search by Username"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              sx={{ marginBottom: 3, backgroundColor: '#1e1e1e', color: '#fff' }}
              InputProps={{ style: { color: '#fff' } }}
            />
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ color: '#fff' }}>Username</TableCell>
                    <TableCell sx={{ color: '#fff' }}>Create Date</TableCell>
                    <TableCell sx={{ color: '#fff' }}></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {filteredUsers.map((user, index) => (
                    <TableRow key={index}>
                      <TableCell sx={{ color: '#fff' }}>{user.userName}</TableCell>
                      <TableCell sx={{ color: '#fff' }}>{user.createdDate}</TableCell>
                      <TableCell>
                        <Button variant="outlined" color="success" onClick={() => fetchDetails(user)}>
                          Show More...
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                  {filteredUsers.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={3} align="center" sx={{ color: '#fff' }}>
                        No users found.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}
        {/* Chat Section */}
        <Box sx={{ marginTop: 4 }}>
          <Typography variant="h5" sx={{ marginBottom: 2 }}>
            Chat with Azure
          </Typography>
          <TextField
            fullWidth
            variant="outlined"
            label="Enter your query..."
            value={chatQuery}
            onChange={(e) => setChatQuery(e.target.value)}
            sx={{ marginBottom: 2, backgroundColor: '#1e1e1e', color: '#fff' }}
            InputProps={{ style: { color: '#fff' } }}
          />
          <Button variant="contained" color="primary" onClick={onQASent} disabled={chatLoading}>
            {chatLoading ? <CircularProgress size={24} /> : 'Send'}
          </Button>
          {chatError && (
            <Typography color="error" sx={{ marginTop: 2 }}>
              {chatError}
            </Typography>
          )}
          {chatResponse && (
            <Box sx={{ marginTop: 2, padding: 2, backgroundColor: '#1e1e1e', borderRadius: 2 }}>
              <Typography variant="body1">{chatResponse}</Typography>
            </Box>
          )}
          <Box sx={{ marginTop: 2 }}>
            <Button variant="contained" color="secondary" onClick={onActionSent} disabled={chatLoading}>
              Action
            </Button>
          </Box>
        </Box>
      </Box>

      {/* Right Side – User Details */}
      <Box sx={{ flex: 2, overflowY: 'auto', padding: 2 }}>
        {loadingDetails ? (
          <CircularProgress />
        ) : errorDetails ? (
          <Typography color="error">{errorDetails}</Typography>
        ) : selectedUser && userDetails ? (
          <Box>
            <Box
              sx={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                backgroundColor: '#1e1e1e',
                padding: 2,
                borderRadius: 2,
                boxShadow: 3,
              }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center' }}>
                <Avatar sx={{ marginRight: 2 }}>
                  <PersonIcon />
                </Avatar>
                <Box>
                  <Typography variant="h5" sx={{ fontWeight: 'bold' }}>
                    {selectedUser.userName}
                  </Typography>
                  <Typography variant="body2" color="gray">
                    {selectedUser.userResourceName}
                  </Typography>
                  <Typography variant="body2" color="gray">
                    Created: {selectedUser.createdDate}
                  </Typography>
                </Box>
              </Box>
              <Box>{renderCompilationStatus(userDetails?.events)}</Box>
            </Box>
            <Tabs
              value={tabIndex}
              onChange={(_, newValue) => setTabIndex(newValue)}
              sx={{
                marginTop: 3,
                backgroundColor: '#1e1e1e',
                borderRadius: 2,
                boxShadow: 3,
                '& .MuiTab-root': { color: '#fff', flexGrow: 1 },
                '& .MuiTabs-flexContainer': { display: 'flex' },
              }}
            >
              <Tab icon={<GroupIcon />} label="Groups" />
              <Tab icon={<EventIcon />} label="Events" />
              <Tab icon={<AccessTimeIcon />} label="Last Access" />
              <Tab icon={<ErrorIcon />} label="Unauthorized Access" />
            </Tabs>
            {tabIndex === 0 && renderTable(userDetails.groups, ['groupName'])}
            {tabIndex === 1 && (
              <Box>
                {renderTable(
                  filterOtherEvents(userDetails.events),
                  ['eventSource', 'eventName', 'eventTime', 'region', 'sourceIPAddress']
                )}
              </Box>
            )}
            {tabIndex === 2 &&
              renderTable(userDetails.accessData, [
                'serviceName',
                'lastAuthenticatedRegion',
                'lastAuthenticated',
              ])}
            {tabIndex === 3 && (
              <Box>
                {renderTable(filterAccessDeniedEvents(userDetails.events), [
                  'eventSource',
                  'errorMessage',
                  'eventTime',
                  'region',
                  'sourceIPAddress',
                ])}
              </Box>
            )}
          </Box>
        ) : (
          <Typography>No user selected.</Typography>
        )}
      </Box>
    </Box>
  );
};

export default AzureUserManagement;
