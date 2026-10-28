import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import './fonts.js';
import './index.css';
import { takeLinkToken } from './utils/oneTimeLinkToken.js';
import { takeInviteToken } from './utils/inviteLinkToken.js';
import { preloadInitialTab } from './utils/preloadInitialTab.js';

// Lift a one-time link token out of the address before any component (or its effects) runs.
// AuthGate's initialiser would strip too; this is belt and braces so the address is clean before ANY
// component effect (e.g. RegisterPage's verify POST) runs. Not covered by tests by design.
takeLinkToken();
// A waitlist invitation (`/register#invite=`) is lifted the same way, into memory, before React renders.
takeInviteToken();
// Start the opening tab's chunk now rather than when the shell first renders (signed-in readers only).
preloadInitialTab();

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
