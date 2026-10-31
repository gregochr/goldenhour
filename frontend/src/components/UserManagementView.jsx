import { useCallback, useEffect, useState } from 'react';
import { getUsers } from '../api/userApi.js';
import Modal from './shared/Modal.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import SubRegionToast from './subregions/SubRegionToast.jsx';
import UserListPane from './users/UserListPane.jsx';
import LiteLimitButton from './users/LiteLimitButton.jsx';
import UserReaderPanel from './users/UserReaderPanel.jsx';
import UserAddPanel from './users/UserAddPanel.jsx';

/** The hash the Users tab lives at; a selected reader adds `/{id}`. */
const USERS_HASH = 'manage/users';

/** The reader id in the URL hash (`#manage/users/12`), or null. */
function selectedIdFromHash() {
  const [area, tab, id] = window.location.hash.replace('#', '').split('/');
  if (area !== 'manage' || tab !== 'users' || !/^\d+$/.test(id ?? '')) return null;
  return Number(id);
}

/** Writes the selection into the hash, the way the Manage tabs do, so it survives a reload and Back. */
function writeSelectionToHash(id) {
  // Intentional browser navigation side effect, as ManageView does for its tabs.
  window.location.hash = id == null ? USERS_HASH : `${USERS_HASH}/${id}`;
}

/**
 * Operations → People → Users (sub-regions plan S4b; the Claude Design handoff, Part B).
 *
 * <p>One list (search, chip filters, sortable columns, rows without buttons) and, beside it, the reader panel
 * a row opens: Account, Sub-regions, Preferences and legal, Account actions, with Change picks, Edit account and
 * Add user taking over the panel body. The selection is mirrored in the hash ({@code #manage/users/{id}}), so a
 * reload or the Back button lands on the same reader; Esc or × closes the panel. At iPad portrait the panel
 * replaces the list rather than sitting beside it. Phone layout is out of scope: the admin tabs are desktop and iPad.
 */
export default function UserManagementView() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selectedId, setSelectedId] = useState(selectedIdFromHash);
  const [adding, setAdding] = useState(false);
  const [mode, setMode] = useState('view'); // the reader panel's body: view | picks | edit
  const [tempPassword, setTempPassword] = useState(null);
  const [toast, setToast] = useState(null);
  const clearToast = useCallback(() => setToast(null), []);
  // Bumped when the Lite limit is saved, so an open reader panel refetches its sub-region state in place.
  const [policyVersion, setPolicyVersion] = useState(0);

  const refreshUsers = useCallback(async () => {
    try {
      setUsers(await getUsers());
    } catch {
      // Keep the list on screen if a refresh fails.
    }
  }, []);

  useEffect(() => {
    let live = true;
    getUsers()
      .then((list) => { if (live) setUsers(list); })
      .catch(() => { if (live) setLoadError("Couldn't load users."); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, []);

  // Back and Forward (and a pasted link) move the selection.
  useEffect(() => {
    const onHashChange = () => {
      const id = selectedIdFromHash();
      if (id != null) setAdding(false);
      setMode('view');
      setSelectedId(id);
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  // Each user's `overLimit` flag comes from GET /api/users, so a new limit means refetching the list too.
  const handlePolicySaved = useCallback(() => {
    refreshUsers();
    setPolicyVersion((v) => v + 1);
  }, [refreshUsers]);

  const selectedUser = users.find((u) => u.id === selectedId) ?? null;
  const panelOpen = adding || selectedUser != null;

  const closePanel = useCallback(() => {
    setAdding(false);
    setMode('view');
    setSelectedId(null);
    writeSelectionToHash(null);
  }, []);

  // Esc closes the panel, unless a dialog is open (it has its own buttons) or something already took the key.
  useEffect(() => {
    if (!panelOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      if (document.querySelector('[role="dialog"], [aria-modal="true"]')) return;
      // Esc in a text field belongs to the field (the search box clears itself in some browsers).
      const target = event.target;
      if (target instanceof HTMLElement && (target.tagName === 'TEXTAREA' || target.isContentEditable
          || (target.tagName === 'INPUT' && !['checkbox', 'radio', 'button'].includes(target.type)))) return;
      if (!adding && mode !== 'view') {
        setMode('view');
        return;
      }
      closePanel();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [panelOpen, adding, mode, closePanel]);

  function selectUser(user) {
    setAdding(false);
    setMode('view');
    setSelectedId(user.id);
    writeSelectionToHash(user.id);
  }

  function startAdd() {
    setSelectedId(null);
    setAdding(true);
    writeSelectionToHash(null);
  }

  async function handleCreated(created) {
    await refreshUsers();
    setAdding(false);
    if (created?.id != null) {
      setSelectedId(created.id);
      writeSelectionToHash(created.id);
    }
    setToast(`Added ${created?.username ?? 'the user'}.`);
  }

  async function handleDeleted() {
    closePanel();
    await refreshUsers();
  }

  return (
    <div className="-m-4 overflow-hidden rounded-xl" data-testid="user-management">
      {loading && <p className="p-4 text-sm text-plex-text-muted animate-pulse">Loading users...</p>}
      <ErrorBanner message={loadError} className="m-4" data-testid="users-load-error" />

      {!loading && !loadError && (
        <div className={panelOpen ? 'grid lg:grid-cols-[minmax(0,1fr)_440px]' : ''}>
          <div className={`min-w-0 ${panelOpen ? 'max-lg:hidden' : ''}`}>
            <UserListPane
              users={users}
              selectedId={selectedUser ? selectedUser.id : null}
              compact={panelOpen}
              onSelect={selectUser}
              onAdd={startAdd}
              headerActions={<LiteLimitButton onSaved={handlePolicySaved} />}
            />
          </div>
          {panelOpen && (
            <aside className="min-w-0 border-plex-border bg-plex-panel lg:border-l" data-testid="user-panel">
              {adding ? (
                <UserAddPanel onClose={closePanel} onCreated={handleCreated} />
              ) : (
                <UserReaderPanel
                  key={selectedUser.id}
                  user={selectedUser}
                  mode={mode}
                  onModeChange={setMode}
                  onClose={closePanel}
                  onUsersChanged={refreshUsers}
                  onTempPassword={setTempPassword}
                  onToast={setToast}
                  onDeleted={handleDeleted}
                  policyVersion={policyVersion}
                />
              )}
            </aside>
          )}
        </div>
      )}

      {tempPassword && (
        <Modal label="Temporary password" onClose={() => setTempPassword(null)} data-testid="temp-password-modal">
          <p className="text-sm font-semibold text-plex-text">
            Temporary password for <span className="text-plex-gold">{tempPassword.username}</span>
          </p>
          <div className="flex items-center gap-2">
            <code
              className="flex-1 font-mono text-base bg-plex-surface-light border border-plex-border rounded px-3 py-2 text-green-300 tracking-widest select-all"
              data-testid="temp-password-value"
            >
              {tempPassword.password}
            </code>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={() => navigator.clipboard.writeText(tempPassword.password)}
              data-testid="copy-temp-password"
            >
              Copy
            </button>
          </div>
          <p className="text-xs text-plex-text-secondary">
            The user will be required to change this password on next login.
          </p>
          <button
            className="btn-primary text-sm self-end"
            onClick={() => setTempPassword(null)}
            data-testid="close-temp-password-modal"
          >
            Close
          </button>
        </Modal>
      )}
      <SubRegionToast message={toast} onDone={clearToast} />
    </div>
  );
}
