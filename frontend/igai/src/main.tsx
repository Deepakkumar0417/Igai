import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import AIContextProvider from './context/AIContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AIContextProvider>
    <App />
    </AIContextProvider>
  </StrictMode>,
)
