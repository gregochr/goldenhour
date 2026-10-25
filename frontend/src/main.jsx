import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import './fonts.js';
import './index.css';
import { takeLinkToken } from './utils/oneTimeLinkToken.js';

// Lift a one-time link token out of the address before any component (or its effects) runs.
// AuthGate's initialiser would strip too; this is belt and braces so the address is clean before ANY
// component effect (e.g. RegisterPage's verify POST) runs. Not covered by tests by design.
takeLinkToken();

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
