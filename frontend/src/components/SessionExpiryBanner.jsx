import React, { useState } from 'react';
import { useAuth } from '../context/AuthContext.jsx';

/**
 * Renders an amber or red banner when the user's session (refresh token) is close to expiry.
 * Shows at ≤7 days remaining; turns red at ≤1 day. Offers a one-click session refresh.
 */
export default function SessionExpiryBanner() {
  const { sessionDaysRemaining, refreshSession } = useAuth();
  const [loading, setLoading] = useState(false);

  if (sessionDaysRemaining === null || sessionDaysRemaining > 7) return null;

  const urgent = sessionDaysRemaining <= 1;
  const label = sessionDaysRemaining === 0
    ? 'Your session expires today'
    : `Your session expires in ${sessionDaysRemaining} day${sessionDaysRemaining === 1 ? '' : 's'}`;

  async function handleRefresh() {
    setLoading(true);
    try {
      await refreshSession();
    } catch {
      // Silently ignore — user can try again or re-login
    } finally {
      setLoading(false);
    }
  }

  return (
    <div
      className={`border-b px-4 py-2 ${urgent ? 'bg-red-950 border-red-900' : 'bg-amber-950 border-amber-900'}`}
      role="alert"
      data-testid="session-expiry-banner"
    >
      <div className="max-w-4xl mx-auto flex items-center justify-between gap-4">
        <p className={`text-xs ${urgent ? 'text-red-300' : 'text-amber-300'}`}>
          {label}
        </p>
        <button
          className="btn-secondary text-xs shrink-0"
          onClick={handleRefresh}
          disabled={loading}
          data-testid="session-refresh-button"
        >
          {loading ? 'Refreshing…' : 'Refresh session'}
        </button>
      </div>
    </div>
  );
}
