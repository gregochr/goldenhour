import React, { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';
import { formatInstantUk } from '../utils/conversions.js';

const DATE_ONLY = { day: 'numeric', month: 'short', year: 'numeric' };

/**
 * Admin view displaying waitlist email submissions ordered oldest-first, with an Invite button (which
 * mails a seven-day registration link that skips the early-access cap, once) and a Remove button per row.
 *
 * @param {object}   props
 * @param {function} props.onCountChange - Called with the total entry count after fetch and after a removal.
 */
export default function WaitlistManagementView({ onCountChange }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  // The id of the row whose request is in flight, and the id of the row asking "remove this?".
  const [busyId, setBusyId] = useState(null);
  const [confirmingId, setConfirmingId] = useState(null);
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    getWaitlist()
      .then((list) => {
        setEntries(list);
        onCountChange(list.length);
      })
      .catch(() => {
        // Keep empty list on failure
      })
      .finally(() => setLoading(false));
  }, [onCountChange]);

  const handleInvite = useCallback(async (entry) => {
    setActionError('');
    setNotice('');
    setBusyId(entry.id);
    try {
      await inviteWaitlistEntry(entry.id);
      setNotice('Invitation sent.');
      // The server holds the dates; read them back rather than guessing the expiry on the client.
      const list = await getWaitlist();
      setEntries(list);
    } catch (err) {
      setActionError(err?.response?.data?.error ?? 'Could not send the invitation. Please try again.');
    } finally {
      setBusyId(null);
    }
  }, []);

  const handleRemove = useCallback(async (entry) => {
    setActionError('');
    setNotice('');
    setBusyId(entry.id);
    try {
      await removeWaitlistEntry(entry.id);
      setConfirmingId(null);
      const next = entries.filter((e) => e.id !== entry.id);
      setEntries(next);
      onCountChange(next.length);
    } catch (err) {
      setActionError(err?.response?.data?.error ?? 'Could not remove the entry. Please try again.');
    } finally {
      setBusyId(null);
    }
  }, [entries, onCountChange]);

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
      {actionError && (
        <p className="text-xs text-plex-error mb-2" role="alert" data-testid="waitlist-action-error">
          {actionError}
        </p>
      )}
      {notice && (
        <p className="text-xs text-plex-text-secondary mb-2" role="status" data-testid="waitlist-notice">
          {notice}
        </p>
      )}
      <table className="w-full text-sm text-left" data-testid="waitlist-table">
        <thead>
          <tr className="text-xs text-plex-text-muted border-b border-plex-border">
            <th className="pb-2 font-medium w-[6%]">#</th>
            <th className="pb-2 font-medium w-[34%]">Email</th>
            <th className="pb-2 font-medium">Submitted</th>
            <th className="pb-2 font-medium">Invitation</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry, index) => {
            const busy = busyId === entry.id;
            return (
              <tr key={entry.id} className="border-b border-plex-surface last:border-0">
                <td className="py-2 text-plex-text-muted">{index + 1}</td>
                <td className="py-2 text-plex-text" data-testid={`waitlist-email-${index}`}>
                  {entry.email}
                </td>
                <td className="py-2 text-plex-text-muted text-xs" data-testid={`waitlist-submitted-${index}`}>
                  {/* `submittedAt` is a bare LocalDateTime produced in UTC, so it was being parsed as
                      the reader's local time AND formatted in it — the two errors cancelled to the
                      stored digits, which is UTC wearing the reader's clock. An hour early for the UK
                      all summer, and a day out west of Greenwich near midnight. */}
                  {formatInstantUk(entry.submittedAt, {
                    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
                  }) ?? '—'}
                </td>
                <td className="py-2 text-xs">
                  {entry.invitedAt && (
                    <p className="text-plex-text-muted mb-1" data-testid={`waitlist-invited-${index}`}>
                      Invited {formatInstantUk(entry.invitedAt, DATE_ONLY) ?? '—'} · expires{' '}
                      {formatInstantUk(entry.inviteExpiresAt, DATE_ONLY) ?? '—'}
                    </p>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      className="btn-secondary text-xs"
                      data-testid={`waitlist-invite-${index}`}
                      disabled={busy}
                      onClick={() => handleInvite(entry)}
                    >
                      {entry.invitedAt ? 'Invite again' : 'Invite'}
                    </button>
                    {confirmingId === entry.id ? (
                      <>
                        <button
                          type="button"
                          className="text-xs text-plex-error hover:underline"
                          data-testid={`waitlist-remove-confirm-${index}`}
                          disabled={busy}
                          onClick={() => handleRemove(entry)}
                        >
                          Remove for good
                        </button>
                        <button
                          type="button"
                          className="text-xs text-plex-text-secondary hover:text-plex-text"
                          data-testid={`waitlist-remove-cancel-${index}`}
                          disabled={busy}
                          onClick={() => setConfirmingId(null)}
                        >
                          Keep
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="text-xs text-plex-text-secondary hover:text-plex-error"
                        data-testid={`waitlist-remove-${index}`}
                        disabled={busy}
                        onClick={() => setConfirmingId(entry.id)}
                      >
                        Remove
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

WaitlistManagementView.propTypes = {
  onCountChange: PropTypes.func.isRequired,
};
