import { createTheme } from '@mui/material/styles';

export const darkTheme = createTheme({
  palette: {
    mode: 'dark',
    background: {
      default: 'rgb(255, 255, 255)',
    },
    text: {
      primary: '#121212',
    },
  },
});
