import { useCallback, useEffect, useRef, useState } from 'react';
import { getUsers } from '../api/userApi.js';
import Modal from './shared/Modal.jsx';
import ErrorBanner from './shared/ErrorBanner.jsx';
import DataSplitLayout from './data/DataSplitLayout.jsx';
import SubRegionToast from './subregions/SubRegionToast.jsx';
import UserListPane from './users/UserListPane.jsx';
import LiteLimitButton from './users/LiteLimitButton.jsx';
import UserReaderPanel from './users/UserReaderPanel.jsx';
import UserAddPanel from './users/UserAddPanel.jsx';
import { textButtonClass } from './users/userFormClasses.js';
import useHashSelection from '../hooks/useHashSelection.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import usePanelEscape from '../hooks/usePanelEscape.js';

/** The Manage tab key; a selected reader adds `/{id}` ({@code #manage/users/12}). */
const TAB = 'users';

/**
 * Operations → People → Users (sub-regions plan S4b; the Claude Design handoff, Part B), on the shared Data
 * list-and-panel shell (data-tabs-redesign-plan.md, phase P2).
 *
 * <p>One list (search, chip filters, sortable columns, rows without buttons) and, beside it, the reader panel
 * a row opens: Account, Sub-regions, Preferences and legal, Account actions, with Change picks, Edit account and
 * Add user taking over the panel body. The selection is mirrored in the hash ({@code #manage/users/{id}}), so a
 * reload or the Back button lands on the same reader. The panel sits beside the list from 1000px, over it (under a
 * scrim) from 640 to 999px, and on a phone is a full page with a "‹ Users" back bar that also carries Edit. Esc steps
 * back from Change picks or Edit to the reader, then closes the panel; an open dialog keeps the key.
 */
export default function UserManagementView() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const { selectedId, select, clear } = useHashSelection(TAB);
  const [adding, setAdding] = useState(false);
  const [mode, setMode] = useState('view'); // the reader panel's body: view | picks | edit
  const [tempPassword, setTempPassword] = useState(null);
  const [toast, setToast] = useState(null);
  const clearToast = useCallback(() => setToast(null), []);
  // Bumped when the Lite limit is saved, so an open reader panel refetches its sub-region state in place.
  const [policyVersion, setPolicyVersion] = useState(0);
  const isMobile = useIsMobile();
  const panelRef = useRef(null);

  // A new selection (a click, Back, Forward, a pasted link) starts on the reader's own view, and leaves Add user.
  // Adjusting state while rendering, React's pattern for state derived from a changed value.
  const [seenSelection, setSeenSelection] = useState(selectedId);
  if (selectedId !== seenSelection) {
    setSeenSelection(selectedId);
    setMode('view');
    if (selectedId != null) setAdding(false);
  }

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

  // Each user's `overLimit` flag comes from GET /api/users, so a new limit means refetching the list too.
  const handlePolicySaved = useCallback(() => {
    refreshUsers();
    setPolicyVersion((v) => v + 1);
  }, [refreshUsers]);

  const selectedUser = users.find((u) => u.id === selectedId) ?? null;
  const panelOpen = adding || selectedUser != null;

  // A hash naming nobody (a pasted unknown id, the reader just deleted) leaves the bare tab address, replacing the
  // entry so Back does not land on a dead one.
  useEffect(() => {
    if (!loading && !loadError && selectedId != null && selectedUser == null) clear({ replace: true });
  }, [loading, loadError, selectedId, selectedUser, clear]);

  const closePanel = useCallback(() => {
    setAdding(false);
    setMode('view');
    clear();
  }, [clear]);

  // Esc steps back from Change picks or Edit account to the reader first, then closes the panel. The hook leaves it
  // alone when a dialog is open, when focus is in a text field (the search box, the Add user fields) and when the
  // Operations pane is hidden.
  usePanelEscape({
    active: panelOpen,
    panelRef,
    onEscape: !adding && mode !== 'view' ? () => setMode('view') : closePanel,
  });

  function selectUser(user) {
    setAdding(false);
    setMode('view');
    select(user.id);
  }

  function startAdd() {
    setAdding(true);
    clear();
  }

  async function handleCreated(created) {
    await refreshUsers();
    setAdding(false);
    if (created?.id != null) select(created.id);
    setToast(`Added ${created?.username ?? 'the user'}.`);
  }

  async function handleDeleted() {
    closePanel();
    await refreshUsers();
  }

  let panel = null;
  if (adding) {
    panel = <UserAddPanel onClose={closePanel} onCreated={handleCreated} />;
  } else if (selectedUser) {
    panel = (
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
        hideEdit={isMobile}
      />
    );
  }

  // On the phone page the reader's Edit rides the back bar; the other modes have their own Cancel and Save.
  const backAction = isMobile && selectedUser && !adding && mode === 'view' ? (
    <button
      type="button"
      className={`${textButtonClass} min-h-[44px] px-3 font-medium`}
      onClick={() => setMode('edit')}
      data-testid={`edit-user-${selectedUser.id}`}
    >
      Edit
    </button>
  ) : null;

  let panelLabel = 'Reader';
  if (adding) panelLabel = 'Add user';
  else if (selectedUser) panelLabel = `Reader ${selectedUser.username}`;

  // On the phone page the back button steps back from Change picks or Edit to the reader, as Esc does; from the
  // reader (or Add user) it returns to the list.
  const inSubMode = !adding && selectedUser != null && mode !== 'view';

  let selectionKey = null;
  if (adding) selectionKey = 'add';
  else if (selectedUser) selectionKey = selectedUser.id;

  return (
    <div className="-m-4 overflow-clip rounded-xl max-[639px]:m-0 max-[639px]:overflow-visible" data-testid="user-management">
      {loading && <p className="p-4 text-sm text-plex-text-muted animate-pulse">Loading users...</p>}
      <ErrorBanner message={loadError} className="m-4" data-testid="users-load-error" />

      {!loading && !loadError && (
        <DataSplitLayout
          list={(
            <UserListPane
              users={users}
              selectedId={selectedUser ? selectedUser.id : null}
              panelOpen={panelOpen}
              onSelect={selectUser}
              onAdd={startAdd}
              onSelectionFiltered={closePanel}
              headerActions={<LiteLimitButton onSaved={handlePolicySaved} />}
            />
          )}
          panel={panel}
          onClose={closePanel}
          panelLabel={panelLabel}
          backLabel={inSubMode ? selectedUser.username : 'Users'}
          onBack={inSubMode ? () => setMode('view') : closePanel}
          backAction={backAction}
          panelRef={panelRef}
          selectionKey={selectionKey}
          focusKey={`${selectionKey}:${adding ? 'add' : mode}`}
          rowTestIdPrefix="users"
          testId="user"
        />
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
