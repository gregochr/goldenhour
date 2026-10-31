import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';
import DataGrid from './data/DataGrid.jsx';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import { DataSearch, DataTools } from './data/DataTools.jsx';
import KvList from './data/KvList.jsx';
import PanelSection from './data/PanelSection.jsx';
import PanelShell from './data/PanelShell.jsx';
import SubRegionToast from './subregions/SubRegionToast.jsx';
import useHashSelection from '../hooks/useHashSelection.js';
import usePanelEscape from '../hooks/usePanelEscape.js';
import { formatDataDateTime, formatPlainDate, formatWaiting } from '../utils/dataDates.js';
import { downloadTextFile, waitlistCsv, waitlistEmailLines } from '../utils/waitlistExport.js';
import { textButtonClass } from './users/userFormClasses.js';

/** The Manage tab key; the hash is {@code #manage/waitlist/{id}}. */
const TAB = 'waitlist';

/** The invitation status line: "Invited 9 Apr 2026 · expires 16 Apr 2026", or "Not invited". */
function invitationText(entry) {
  if (!entry.invitedAt) return 'Not invited';
  return `Invited ${formatPlainDate(entry.invitedAt)} · expires ${formatPlainDate(entry.inviteExpiresAt)}`;
}

/** The server's own sentence from a failed request, else the fallback. */
function errorSentence(err, fallback) {
  return err?.response?.data?.error ?? fallback;
}

/**
 * The panel a waitlist row opens (decision D1): the entry's dates and invitation, then Invite (or Invite again)
 * and Remove behind its two-step confirm. Keyed by entry id by the caller, so its busy, notice and error state
 * never carries from one entry to the next.
 */
function WaitlistPanel({ entry, total, onClose, onInvite, onRemove, panelRef }) {
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const removeButton = useRef(null);
  const confirmButton = useRef(null);
  // Which button takes focus once the confirm step has changed what is on screen; null on first render.
  const focusAfterToggle = useRef(null);

  useEffect(() => {
    if (focusAfterToggle.current === 'confirm') confirmButton.current?.focus();
    if (focusAfterToggle.current === 'remove') removeButton.current?.focus();
    focusAfterToggle.current = null;
  }, [confirming]);

  function askToRemove() {
    focusAfterToggle.current = 'confirm';
    setConfirming(true);
  }

  function keepEntry() {
    focusAfterToggle.current = 'remove';
    setConfirming(false);
  }

  // Esc backs out of the confirm step first, and only then closes the panel.
  usePanelEscape({ active: true, panelRef, onEscape: confirming ? keepEntry : onClose });

  async function invite() {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      await onInvite(entry);
      setNotice('Invitation sent.');
    } catch (err) {
      setError(errorSentence(err, "Couldn't send the invitation. Please try again."));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setError('');
    setNotice('');
    setBusy(true);
    try {
      await onRemove(entry);
    } catch (err) {
      setError(errorSentence(err, "Couldn't remove the entry. Please try again."));
      setBusy(false);
    }
  }

  return (
    <PanelShell
      title={entry.email}
      subtitle={`Number ${entry.position} of ${total} in the queue`}
      onClose={onClose}
      testIdPrefix="waitlist-panel-shell"
    >
      <PanelSection label="Details">
        <KvList
          items={[
            { label: 'Joined', value: formatDataDateTime(entry.submittedAt), mono: true, testId: 'waitlist-panel-joined' },
            { label: 'Waiting', value: formatWaiting(entry.submittedAt), mono: true, testId: 'waitlist-panel-waiting' },
            { label: 'Invitation', value: invitationText(entry), testId: 'waitlist-invited' },
          ]}
        />
      </PanelSection>
      <PanelSection label="Actions">
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            className="btn-secondary text-xs max-[639px]:min-h-11"
            disabled={busy}
            onClick={invite}
            data-testid="waitlist-invite"
          >
            {entry.invitedAt ? 'Invite again' : 'Invite'}
          </button>
          {confirming ? (
            <>
              <button
                type="button"
                className="min-h-9 text-xs text-plex-error hover:underline max-[639px]:min-h-11"
                ref={confirmButton}
                disabled={busy}
                onClick={remove}
                data-testid="waitlist-remove-confirm"
              >
                Remove for good
              </button>
              <button
                type="button"
                className={`${textButtonClass} text-xs max-[639px]:min-h-11`}
                disabled={busy}
                onClick={keepEntry}
                data-testid="waitlist-remove-cancel"
              >
                Keep
              </button>
            </>
          ) : (
            <button
              type="button"
              className={`${textButtonClass} text-xs hover:text-plex-error max-[639px]:min-h-11`}
              ref={removeButton}
              disabled={busy}
              onClick={askToRemove}
              data-testid="waitlist-remove"
            >
              Remove
            </button>
          )}
        </div>
        {error && (
          <p className="mt-3 text-xs text-plex-error" role="alert" data-testid="waitlist-action-error">{error}</p>
        )}
        {notice && (
          <p className="mt-3 text-xs text-plex-text-secondary" role="status" data-testid="waitlist-notice">{notice}</p>
        )}
      </PanelSection>
    </PanelShell>
  );
}

WaitlistPanel.propTypes = {
  entry: PropTypes.object.isRequired,
  total: PropTypes.number.isRequired,
  onClose: PropTypes.func.isRequired,
  onInvite: PropTypes.func.isRequired,
  onRemove: PropTypes.func.isRequired,
  panelRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
};

/**
 * Operations → People → Waitlist, on the shared Data list-and-panel shell (data-tabs-redesign-plan.md §8).
 *
 * <p>The list is the queue, oldest first (the server's order, never re-sorted): a stable position #, the email, when
 * the address joined and how long it has waited. A row opens a panel ({@code #manage/waitlist/{id}}) with the
 * entry's dates and invitation, <b>Invite</b> / <b>Invite again</b> (which mails a seven-day registration link that
 * skips the early-access cap, once) and <b>Remove</b> behind its two-step confirm. Copy emails and Download CSV act
 * on the whole list, not the search result. A failed load says so, and is not the empty state.
 *
 * @param {object}   props
 * @param {function} props.onCountChange - Called with the total entry count after fetch and after a removal.
 */
export default function WaitlistManagementView({ onCountChange }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [query, setQuery] = useState('');
  const [toast, setToast] = useState(null);
  const [copyError, setCopyError] = useState('');
  const clearToast = useCallback(() => setToast(null), []);
  const { selectedId, select, clear } = useHashSelection(TAB);
  const panelRef = useRef(null);
  // The latest list, and the ids removed this session: a re-read that was already in flight when an entry was
  // removed still carries that entry, and must not bring it back (nor its count).
  const entriesRef = useRef([]);
  const removedIds = useRef(new Set());
  const commit = useCallback((list) => {
    entriesRef.current = list;
    setEntries(list);
  }, []);

  useEffect(() => {
    let live = true;
    getWaitlist()
      .then((list) => {
        if (!live) return;
        commit(list);
        onCountChange(list.length);
      })
      .catch(() => { if (live) setLoadError(true); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [onCountChange, commit]);

  // The queue position is the entry's place in the server's oldest-first list, so a search never renumbers it.
  const queue = useMemo(() => entries.map((entry, index) => ({ ...entry, position: index + 1 })), [entries]);
  const needle = query.trim().toLowerCase();
  const shown = useMemo(
    () => (needle === '' ? queue : queue.filter((e) => e.email.toLowerCase().includes(needle))),
    [queue, needle],
  );
  const selected = queue.find((e) => e.id === selectedId) ?? null;

  // A selection that is not on the list (a pasted unknown id, or the entry just removed) leaves the address as the
  // bare tab, replacing the entry so Back does not land on a dead one.
  useEffect(() => {
    if (!loading && !loadError && selectedId != null && selected == null) clear({ replace: true });
  }, [loading, loadError, selectedId, selected, clear]);

  const handleInvite = useCallback(async (entry) => {
    await inviteWaitlistEntry(entry.id);
    try {
      // The server holds the dates; read them back rather than guessing the expiry on the client.
      const list = (await getWaitlist()).filter((e) => !removedIds.current.has(e.id));
      commit(list);
      onCountChange(list.length);
    } catch {
      // The invitation went out; a failed re-read leaves the old dates until the next load.
    }
  }, [commit, onCountChange]);

  const handleRemove = useCallback(async (entry) => {
    await removeWaitlistEntry(entry.id);
    removedIds.current.add(entry.id);
    const next = entriesRef.current.filter((e) => e.id !== entry.id);
    commit(next);
    onCountChange(next.length);
    clear({ replace: true });
  }, [commit, onCountChange, clear]);

  async function copyEmails() {
    setCopyError('');
    try {
      await navigator.clipboard.writeText(waitlistEmailLines(entries));
      setToast(entries.length === 1 ? 'Copied 1 email.' : `Copied ${entries.length} emails.`);
    } catch {
      setCopyError("Couldn't copy. Use Download CSV instead.");
    }
  }

  function downloadCsv() {
    downloadTextFile('waitlist.csv', waitlistCsv(entries));
  }

  if (loading) {
    return <p className="text-sm text-plex-text-muted animate-pulse">Loading waitlist...</p>;
  }

  if (loadError) {
    return (
      <p className="text-sm text-plex-error" role="alert" data-testid="waitlist-load-error">
        Couldn&apos;t load the waitlist.
      </p>
    );
  }

  const columns = [
    {
      key: 'position',
      header: '#',
      width: '.3fr',
      secondary: true,
      cell: (e) => <span className="font-mono text-xs text-plex-text-secondary">{e.position}</span>,
    },
    {
      key: 'email',
      header: 'Email',
      width: '2fr',
      card: 'title',
      cell: (e) => <span className="block truncate font-medium" title={e.email}>{e.email}</span>,
    },
    {
      key: 'joined',
      header: 'Joined',
      width: '1fr',
      cell: (e) => (
        <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatDataDateTime(e.submittedAt)}</span>
      ),
    },
    {
      key: 'waiting',
      header: 'Waiting',
      width: '.8fr',
      secondary: true,
      cell: (e) => <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatWaiting(e.submittedAt)}</span>,
    },
    {
      key: 'card-status',
      card: 'status',
      cardOnly: true,
      cell: (e) => <span className="font-mono text-[11.5px] text-plex-text-secondary">{formatWaiting(e.submittedAt)}</span>,
    },
    {
      key: 'card-summary',
      card: 'summary',
      cardOnly: true,
      cell: (e) => (
        <span className="font-mono text-[11.5px] text-plex-text-secondary">
          {e.position} &middot; joined {formatDataDateTime(e.submittedAt)}
        </span>
      ),
    },
  ];

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="flex items-baseline gap-2 text-sm font-semibold text-plex-text">
        Waitlist
        <span className="font-mono text-[11px] font-normal text-plex-text-muted" data-testid="waitlist-total">
          {entries.length === 0 ? '0' : `${entries.length} · oldest first`}
        </span>
      </h2>
      {entries.length > 0 && (
        <div className="flex items-center gap-2">
          <button
            type="button"
            className="btn-secondary text-xs max-[639px]:min-h-11"
            onClick={copyEmails}
            data-testid="waitlist-copy"
          >
            Copy emails
          </button>
          <button
            type="button"
            className="btn-secondary text-xs max-[639px]:min-h-11"
            onClick={downloadCsv}
            data-testid="waitlist-csv"
          >
            Download CSV
          </button>
        </div>
      )}
    </div>
  );

  let body;
  if (entries.length === 0) {
    body = (
      <div className="flex flex-col items-center gap-2 p-14 text-center" data-testid="waitlist-empty">
        <p className="font-serif text-xl font-semibold text-plex-text">No one&apos;s on the waitlist yet</p>
        <p className="text-[13px] text-plex-text-secondary">
          Sign-ups appear here, oldest first, with the time they joined.
        </p>
      </div>
    );
  } else if (shown.length === 0) {
    body = (
      <div className="flex flex-col items-center gap-2 py-8 text-center" data-testid="waitlist-no-match">
        <p className="text-sm text-plex-text-secondary">No one matches.</p>
        <button
          type="button"
          className={`${textButtonClass} underline`}
          onClick={() => setQuery('')}
          data-testid="waitlist-no-match-clear"
        >
          Clear search
        </button>
      </div>
    );
  } else {
    body = (
      <DataGrid
        columns={columns}
        rows={shown}
        getRowId={(e) => e.id}
        getRowLabel={(e) => `${e.email}, number ${e.position} in the queue, joined ${formatDataDateTime(e.submittedAt)}, `
          + `waiting ${formatWaiting(e.submittedAt)}, ${invitationText(e).toLowerCase()}`}
        selectedId={selected ? selected.id : null}
        onSelect={(e) => select(e.id)}
        testIdPrefix="waitlist"
      />
    );
  }

  const list = (
    <div className="flex flex-col gap-4 p-4 max-[639px]:px-[14px] max-[639px]:py-3" data-testid="waitlist-list-pane">
      {header}
      {copyError && (
        <p className="text-xs text-plex-error" role="alert" data-testid="waitlist-copy-error">{copyError}</p>
      )}
      {entries.length > 0 && (
        <DataTools>
          <DataSearch value={query} onChange={setQuery} placeholder="Search email" testId="waitlist-search" />
        </DataTools>
      )}
      {body}
    </div>
  );

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="waitlist-management">
      <DataSplitLayout
        list={list}
        panel={selected && (
          <WaitlistPanel
            key={selected.id}
            entry={selected}
            total={entries.length}
            onClose={clear}
            onInvite={handleInvite}
            onRemove={handleRemove}
            panelRef={panelRef}
          />
        )}
        onClose={clear}
        panelLabel={selected ? `Waitlist entry ${selected.email}` : 'Waitlist entry'}
        backLabel="Waitlist"
        panelRef={panelRef}
        selectionKey={selected ? selected.id : null}
        rowTestIdPrefix="waitlist"
        testId="waitlist-split"
      />
      <SubRegionToast message={toast} onDone={clearToast} />
    </div>
  );
}

WaitlistManagementView.propTypes = {
  onCountChange: PropTypes.func.isRequired,
};
