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
import PersonIcon from '@mui/icons-material/Person';
import GroupIcon from '@mui/icons-material/Group';
import EventIcon from '@mui/icons-material/Event';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import { UserData } from '../../data/types';
import { fetchServiceNowUsers, fetchServiceNowUserDetails } from '../../service/apiService';

const ServiceNowUserManagement: React.FC = () => {
  const [searchTerm, setSearchTerm] = useState('');
  const [userData, setUserData] = useState<UserData[]>([]);
  const [selectedUser, setSelectedUser] = useState<UserData | null>(null);
  const [userDetails, setUserDetails] = useState<any>(null);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [errorUsers, setErrorUsers] = useState<string | null>(null);
  const [errorDetails, setErrorDetails] = useState<string | null>(null);
  const [tabIndex, setTabIndex] = useState(0);

  // Load users from ServiceNow on component mount
  useEffect(() => {
    const loadData = async () => {
      setLoadingUsers(true);
      setErrorUsers(null);
      try {
        const data = await fetchServiceNowUsers();
        setUserData(data);
      } catch (err) {
        setErrorUsers('Failed to fetch user data. Please try again.');
      } finally {
        setLoadingUsers(false);
      }
    };
    loadData();
  }, []);

  // For ServiceNow, assume error events use errorCode === "error"
  const calculateErrorCount = (events: any[]) => {
    if (!events || events.length === 0) return 0;
    return events.filter((event) => event.errorCode === "error").length;
  };

  const renderCompilationStatus = (events: any[]) => {
    const errorCount = calculateErrorCount(events);
    if (errorCount === 0) {
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
        <Typography variant="h6">{errorCount} Compliance</Typography>
      </Box>
    );
  };

  const fetchDetails = async (user: UserData) => {
    setSelectedUser(user);
    setLoadingDetails(true);
    setErrorDetails(null);
    try {
      const userDetailsData = await fetchServiceNowUserDetails(user.userId);
      setUserDetails(userDetailsData);
    } catch (err) {
      setErrorDetails('Failed to fetch user details. Please try again.');
    } finally {
      setLoadingDetails(false);
    }
  };

  const filteredUsers = userData.filter((user) =>
    user.userName.toLowerCase().includes(searchTerm.toLowerCase())
  );

  // Filtering events based on errorCode "error"
  const filterAccessDeniedEvents = (events: any[]) => {
    return events.filter((event) => event.errorCode === "error");
  };

  const filterOtherEvents = (events: any[]) => {
    return events.filter((event) => event.errorCode !== "error");
  };

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
    <Box
      sx={{
        display: 'flex',
        height: '100vh',
        backgroundColor: '#121212',
        color: '#fff',
      }}
    >
      {/* Left Side - User Search */}
      <Box
        sx={{
          flex: 1,
          overflowY: 'auto',
          borderRight: '1px solid #333',
          padding: 2,
        }}
      >
        <Typography variant="h4" sx={{ marginBottom: 2 }}>
          ServiceNow User Search
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
                        <Button
                          variant="outlined"
                          color="success"
                          onClick={() => fetchDetails(user)}
                        >
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
      </Box>

      {/* Right Side - User Details */}
      <Box
        sx={{
          flex: 2,
          overflowY: 'auto',
          padding: 2,
        }}
      >
        {loadingDetails ? (
          <CircularProgress />
        ) : errorDetails ? (
          <Typography color="error">{errorDetails}</Typography>
        ) : selectedUser && userDetails ? (
          <Box>
            {/* User Details Header */}
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
              {/* Left Section: User Info */}
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

              {/* Right Section: Compilation Status */}
              <Box>{renderCompilationStatus(userDetails.events)}</Box>
            </Box>

            {/* Tabs Section */}
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
              <Tab icon={<ErrorIcon />} label="Unauthorized Access" />
            </Tabs>

            {/* Tab Content */}
            {tabIndex === 0 && renderTable(userDetails.groups, ['groupName'])}
            {tabIndex === 1 && (
              <Box>
                {renderTable(filterOtherEvents(userDetails.events), [
                  'eventName',
                  'eventTime',
                ])}
              </Box>
            )}
            {tabIndex === 2 && (
              <Box>
                {renderTable(filterAccessDeniedEvents(userDetails.events), [
                  'eventName',
                  'eventTime',
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

export default ServiceNowUserManagement;
