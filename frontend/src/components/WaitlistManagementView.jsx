import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';

/**
 * Admin view displaying waitlist email submissions ordered oldest-first.
 *
 * @param {object}   props
 * @param {function} props.onCountChange - Called with the total entry count after fetch.
 */
export default function WaitlistManagementView({ onCountChange }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    axios.get('/api/admin/waitlist')
      .then((res) => {
        setEntries(res.data);
        onCountChange(res.data.length);
      })
      .catch(() => {
        // Keep empty list on failure
      })
      .finally(() => setLoading(false));
  }, [onCountChange]);

  if (loading) {
    return <p className="text-sm text-plex-text-muted animate-pulse">Loading waitlist...</p>;
  }

  if (entries.length === 0) {
    return (
      <p className="text-sm text-plex-text-muted text-center py-8" data-testid="waitlist-empty">
        No waitlist entries yet.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm text-left" data-testid="waitlist-table">
        <thead>
          <tr className="text-xs text-plex-text-muted border-b border-plex-border">
            <th className="pb-2 font-medium w-[8%]">#</th>
            <th className="pb-2 font-medium w-[50%]">Email</th>
            <th className="pb-2 font-medium">Submitted</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry, index) => (
            <tr key={entry.id} className="border-b border-plex-surface last:border-0">
              <td className="py-2 text-plex-text-muted">{index + 1}</td>
              <td className="py-2 text-plex-text" data-testid={`waitlist-email-${index}`}>
                {entry.email}
              </td>
              <td className="py-2 text-plex-text-muted text-xs" data-testid={`waitlist-submitted-${index}`}>
                {entry.submittedAt
                  ? new Date(entry.submittedAt).toLocaleDateString('en-GB', {
                    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
                  })
                  : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

WaitlistManagementView.propTypes = {
  onCountChange: PropTypes.func.isRequired,
};
