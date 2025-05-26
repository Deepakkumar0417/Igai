import { useState } from 'react';
import { CssBaseline, ThemeProvider, Dialog, DialogContent, DialogTitle } from '@mui/material';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import ReCAPTCHA from 'react-google-recaptcha';
import "bootstrap/dist/css/bootstrap.min.css";

import { darkTheme } from './theme';
import AzureUserManagement from './pages/Azure/AzureUserManagement';
import HomePage from './pages/HomePage';
import ServiceNowUserManagement from './pages/ServiceNow/ServiceNowUserManagement';

const App = () => {
  // Set to false to force CAPTCHA verification on startup
  const [isCaptchaVerified, setCaptchaVerified] = useState(true);

  const handleCaptchaSuccess = (value: string | null) => {
    if (value) {
      setCaptchaVerified(true);
    }
  };

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <BrowserRouter>
        <Routes>
          <Route
            path="/"
            element={
              isCaptchaVerified ? (
                <HomePage />
              ) : (
                <CaptchaDialog onCaptchaSuccess={handleCaptchaSuccess} />
              )
            }
          />
          <Route
            path="/azure"
            element={
              isCaptchaVerified ? (
                <AzureUserManagement />
              ) : (
                <CaptchaDialog onCaptchaSuccess={handleCaptchaSuccess} />
              )
            }
          />
          <Route
            path="/servicenow"
            element={
              isCaptchaVerified ? (
                <ServiceNowUserManagement />
              ) : (
                <CaptchaDialog onCaptchaSuccess={handleCaptchaSuccess} />
              )
            }
          />
        </Routes>
      </BrowserRouter>
    </ThemeProvider>
  );
};

const CaptchaDialog = ({
  onCaptchaSuccess,
}: {
  onCaptchaSuccess: (value: string | null) => void;
}) => {
  return (
    <Dialog open={true}>
      <DialogTitle sx={{ color: "white" }}>Verify CAPTCHA</DialogTitle>
      <DialogContent>
        <p style={{ color: "white" }}>Complete CAPTCHA verification to continue.</p>
        <ReCAPTCHA
          sitekey="6Lea-UspAAAAANKgjx7dkcXMNEKH0Y1jWlaYZ6nX" // Replace with your actual reCAPTCHA site key
          onChange={onCaptchaSuccess}
        />
      </DialogContent>
    </Dialog>
  );
};

export default App;
