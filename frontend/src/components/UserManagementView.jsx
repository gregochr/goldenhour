import React, { useEffect, useOptimistic, useState, useTransition, useMemo } from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import { resetUserPassword, updateUserEmail, updateUserRole, updateUserEnabled, deleteUser, resendVerification } from '../api/userApi.js';
import Pagination from './Pagination.jsx';
import usePagination from '../hooks/usePagination.js';
import useConfirmDialog from '../hooks/useConfirmDialog.js';
import Modal from './shared/Modal.jsx';

/**
 * Sortable, filterable header cell for data tables.
 *
 * @param {object} props
 * @param {string} props.label - Column header label.
 * @param {string} props.sortKey - Key used for sorting.
 * @param {string} props.currentSortKey - Currently active sort key.
 * @param {'asc'|'desc'} props.currentSortDir - Current sort direction.
 * @param {function} props.onSort - Called with the sort key when clicked.
 * @param {string} props.filterValue - Current filter value.
 * @param {function} props.onFilter - Called with new filter value.
 * @param {string} [props.filterPlaceholder] - Placeholder for filter input.
 */
function SortableHeader({ label, sortKey, currentSortKey, currentSortDir, onSort, filterValue, onFilter, filterPlaceholder, className = '' }) {
  const active = currentSortKey === sortKey;
  const arrow = active ? (currentSortDir === 'asc' ? ' ▲' : ' ▼') : '';

  return (
    <th className={`pb-1 font-medium align-bottom ${className}`}>
      <button
        type="button"
        onClick={() => onSort(sortKey)}
        className="text-xs text-plex-text-muted hover:text-plex-text cursor-pointer whitespace-nowrap"
      >
        {label}{arrow}
      </button>
      <div className="mt-1">
        <input
          type="text"
          value={filterValue}
          onChange={(e) => onFilter(e.target.value)}
          placeholder={filterPlaceholder || 'Filter…'}
          className="w-full bg-plex-surface-light border border-plex-border rounded px-1.5 py-0.5 text-xs text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
          data-testid={`filter-${sortKey}`}
        />
      </div>
    </th>
  );
}

SortableHeader.propTypes = {
  label: PropTypes.string.isRequired,
  sortKey: PropTypes.string.isRequired,
  currentSortKey: PropTypes.string.isRequired,
  currentSortDir: PropTypes.string.isRequired,
  onSort: PropTypes.func.isRequired,
  filterValue: PropTypes.string.isRequired,
  onFilter: PropTypes.func.isRequired,
  filterPlaceholder: PropTypes.string,
  className: PropTypes.string,
};

/**
 * Generic sort/filter hook for table data.
 *
 * @param {string} defaultSortKey - Initial sort column.
 * @param {'asc'|'desc'} defaultSortDir - Initial sort direction.
 * @param {Object<string, function>} accessors - Map of sort key to value accessor function.
 * @returns {object} Sort/filter state and handlers.
 */
function useSortAndFilter(defaultSortKey, defaultSortDir, accessors) {
  const [sortKey, setSortKey] = useState(defaultSortKey);
  const [sortDir, setSortDir] = useState(defaultSortDir);
  const [filters, setFilters] = useState({});

  function handleSort(key) {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('asc');
    }
  }

  function setFilter(key, value) {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }

  function getFilterValue(key) {
    return filters[key] || '';
  }

  function apply(items) {
    let result = [...items];

    // Filter
    for (const [key, value] of Object.entries(filters)) {
      if (!value) continue;
      const accessor = accessors[key];
      if (!accessor) continue;
      const lower = value.toLowerCase();
      result = result.filter((item) => {
        const val = accessor(item);
        return val != null && String(val).toLowerCase().includes(lower);
      });
    }

    // Sort
    const accessor = accessors[sortKey];
    if (accessor) {
      result.sort((a, b) => {
        const va = accessor(a);
        const vb = accessor(b);
        if (va == null && vb == null) return 0;
        if (va == null) return 1;
        if (vb == null) return -1;
        if (typeof va === 'string') {
          const cmp = va.localeCompare(vb, undefined, { sensitivity: 'base' });
          return sortDir === 'asc' ? cmp : -cmp;
        }
        if (typeof va === 'boolean') {
          const cmp = (va === vb) ? 0 : (va ? -1 : 1);
          return sortDir === 'asc' ? cmp : -cmp;
        }
        const cmp = va < vb ? -1 : va > vb ? 1 : 0;
        return sortDir === 'asc' ? cmp : -cmp;
      });
    }

    return result;
  }

  return { sortKey, sortDir, handleSort, setFilter, getFilterValue, apply };
}

/**
 * User management view with list/add/edit modes, sorting, filtering, and consistent card pattern.
 */
export default function UserManagementView() {
  const [users, setUsers] = useState([]);
  const [usersLoading, setUsersLoading] = useState(true);
  const [mode, setMode] = useState('list'); // list | add | edit

  // Add user state
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newRole, setNewRole] = useState('LITE_USER');
  const [addUserError, setAddUserError] = useState('');
  const [addUserSaving, setAddUserSaving] = useState(false);
  const [showNewPassword, setShowNewPassword] = useState(false);

  // Edit user state
  const [editingUser, setEditingUser] = useState(null);
  const [editEmail, setEditEmail] = useState('');
  const [editRole, setEditRole] = useState('');
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState('');

  // Reset password state
  const [resetPasswordLoadingId, setResetPasswordLoadingId] = useState(null);
  const [tempPasswordModal, setTempPasswordModal] = useState(null);
  const [resetPasswordError, setResetPasswordError] = useState('');

  // Confirmation dialog state (shared for reset password and delete)
  const { openDialog, closeDialog, dialogElement } = useConfirmDialog();
  // Delete state
  const [deleteError, setDeleteError] = useState('');

  // Expandable detail row state
  const [expandedUserId, setExpandedUserId] = useState(null);

  // Resend verification state
  const [resendLoadingId, setResendLoadingId] = useState(null);
  const [resendError, setResendError] = useState('');
  const [resendSuccess, setResendSuccess] = useState('');

  const userAccessors = useMemo(() => ({
    username: (u) => u.username,
    email: (u) => u.email || '',
    role: (u) => u.role,
    created: (u) => u.createdAt || '',
    lastActive: (u) => u.lastActiveAt || '',
    status: (u) => u.enabled ? 'Enabled' : 'Disabled',
  }), []);

  const [optimisticUsers, addOptimisticUser] = useOptimistic(users, (current, toggledId) =>
    current.map((u) => u.id === toggledId ? { ...u, enabled: !u.enabled } : u),
  );
  const [, startToggleTransition] = useTransition();

  const sf = useSortAndFilter('username', 'asc', userAccessors);

  const filteredUsers = useMemo(() => sf.apply(optimisticUsers), [sf, optimisticUsers]);

  const { pageItems: pageUsers, ...pagination } = usePagination(filteredUsers);

  async function fetchUsers() {
    try {
      const res = await axios.get('/api/users');
      setUsers(res.data);
    } catch {
      // Keep existing list on failure
    }
  }

  useEffect(() => {
    axios.get('/api/users')
      .then((res) => setUsers(res.data))
      .finally(() => setUsersLoading(false));
  }, []);

  function handleStartAdd() {
    setMode('add');
    setNewUsername('');
    setNewPassword('');
    setNewEmail('');
    setNewRole('LITE_USER');
    setShowNewPassword(false);
    setAddUserError('');
  }

  function handleStartEdit(user) {
    setMode('edit');
    setEditingUser(user);
    setEditEmail(user.email || '');
    setEditRole(user.role);
    setEditError('');
  }

  function handleCancel() {
    setMode('list');
    setEditingUser(null);
    setAddUserError('');
    setEditError('');
  }

  const usernamePattern = /^[a-zA-Z0-9_-]{3,30}$/;
  const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const addUsernameValid = usernamePattern.test(newUsername.trim());
  const addEmailValid = emailPattern.test(newEmail.trim());

  async function handleAddUser() {
    const trimmedUsername = newUsername.trim();
    const trimmedEmail = newEmail.trim();
    if (!trimmedUsername || !newPassword.trim()) {
      setAddUserError('Username and password are required.');
      return;
    }
    if (!addUsernameValid) {
      setAddUserError('Username must be 3-30 characters: letters, numbers, hyphens, or underscores.');
      return;
    }
    if (!trimmedEmail) {
      setAddUserError('Email address is required.');
      return;
    }
    if (!addEmailValid) {
      setAddUserError('Please enter a valid email address.');
      return;
    }
    setAddUserError('');
    setAddUserSaving(true);
    try {
      await axios.post('/api/users', {
        username: trimmedUsername,
        password: newPassword,
        email: trimmedEmail,
        role: newRole,
      });
      await fetchUsers();
      handleCancel();
    } catch (err) {
      setAddUserError(err?.response?.data?.error ?? 'Failed to create user.');
    } finally {
      setAddUserSaving(false);
    }
  }

  async function handleSaveEdit() {
    const trimmedEmail = editEmail.trim();
    if (!trimmedEmail) {
      setEditError('Email address is required.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
      setEditError('Please enter a valid email address.');
      return;
    }
    setEditError('');
    setEditSaving(true);
    try {
      const calls = [];
      if (trimmedEmail !== (editingUser.email || '')) {
        calls.push(updateUserEmail(editingUser.id, trimmedEmail));
      }
      if (editRole !== editingUser.role) {
        calls.push(updateUserRole(editingUser.id, editRole));
      }
      await Promise.all(calls);
      await fetchUsers();
      handleCancel();
    } catch (err) {
      setEditError(err?.response?.data?.error ?? 'Failed to save changes.');
    } finally {
      setEditSaving(false);
    }
  }

  function handleToggleEnabled(user) {
    startToggleTransition(async () => {
      addOptimisticUser(user.id);
      try {
        await updateUserEnabled(user.id, !user.enabled);
        await fetchUsers();
      } catch (err) {
        console.error('Failed to toggle user enabled:', err);
      }
    });
  }

  function handleResetPassword(user) {
    openDialog({
      title: 'Reset Password',
      message: `Reset password for ${user.username}? A temporary password will be generated and emailed to them. They will be required to set a new password on next login.`,
      confirmLabel: 'Reset',
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setResetPasswordLoadingId(user.id);
        setResetPasswordError('');
        try {
          const data = await resetUserPassword(user.id);
          setTempPasswordModal({ username: user.username, password: data.temporaryPassword });
        } catch {
          setResetPasswordError(`Failed to reset password for ${user.username}.`);
        } finally {
          setResetPasswordLoadingId(null);
        }
      },
    });
  }

  function handleResendVerification(user) {
    openDialog({
      title: 'Resend Verification',
      message: `Resend verification email to ${user.email}?`,
      confirmLabel: 'Send',
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setResendLoadingId(user.id);
        setResendError('');
        setResendSuccess('');
        try {
          await resendVerification(user.id);
          setResendSuccess(`Verification email sent to ${user.email}`);
        } catch (err) {
          setResendError(err?.response?.data?.error ?? `Failed to resend verification for ${user.username}.`);
        } finally {
          setResendLoadingId(null);
        }
      },
    });
  }

  function handleDeleteUser(user) {
    openDialog({
      title: 'Delete User',
      message: `Permanently delete ${user.username}? This action cannot be undone.`,
      confirmLabel: 'Delete',
      destructive: true,
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setDeleteError('');
        try {
          await deleteUser(user.id);
          await fetchUsers();
        } catch (err) {
          setDeleteError(err?.response?.data?.error ?? `Failed to delete ${user.username}.`);
        }
      },
    });
  }

  const inputClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold';
  const labelClass = 'block text-xs text-plex-text-secondary mb-1';
  const selectClass = 'w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold';

  return (
    <div className="flex flex-col gap-4">

      {/* List mode */}
      {mode === 'list' && (
        <>
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-plex-text">User Management</p>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={handleStartAdd}
              data-testid="add-user-btn"
            >
              + Add User
            </button>
          </div>

          {usersLoading && (
            <p className="text-sm text-plex-text-muted animate-pulse">Loading users...</p>
          )}

          {resetPasswordError && (
            <p className="text-xs text-red-400">{resetPasswordError}</p>
          )}
          {deleteError && (
            <p className="text-xs text-red-400">{deleteError}</p>
          )}
          {resendError && (
            <p className="text-xs text-red-400">{resendError}</p>
          )}
          {resendSuccess && (
            <p className="text-xs text-green-400">{resendSuccess}</p>
          )}

          {!usersLoading && users.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm text-left table-fixed" data-testid="users-table">
                <thead>
                  <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                    <th className="pb-1 font-medium align-top w-[3%]">
                      <div className="h-[26px]" />
                    </th>
                    <SortableHeader label="Username" sortKey="username" className="w-[13%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('username')} onFilter={(v) => sf.setFilter('username', v)} />
                    <SortableHeader label="Email" sortKey="email" className="w-[21%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('email')} onFilter={(v) => sf.setFilter('email', v)} />
                    <SortableHeader label="Role" sortKey="role" className="w-[10%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('role')} onFilter={(v) => sf.setFilter('role', v)} />
                    <SortableHeader label="Created" sortKey="created" className="w-[11%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('created')} onFilter={(v) => sf.setFilter('created', v)} />
                    <SortableHeader label="Last Active" sortKey="lastActive" className="w-[13%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('lastActive')} onFilter={(v) => sf.setFilter('lastActive', v)} />
                    <SortableHeader label="Status" sortKey="status" className="w-[10%]" currentSortKey={sf.sortKey} currentSortDir={sf.sortDir} onSort={sf.handleSort} filterValue={sf.getFilterValue('status')} onFilter={(v) => sf.setFilter('status', v)} />
                    <th className="pb-1 font-medium align-top w-[19%]">
                      <span className="text-xs text-plex-text-muted whitespace-nowrap">Actions</span>
                      <div className="mt-1 h-[26px]" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {pageUsers.map((user) => (
                    <React.Fragment key={user.id}>
                      <tr className={`border-b border-plex-surface last:border-0 ${!user.enabled ? 'opacity-50' : ''}`}>
                        <td className="py-2 text-center">
                          <button
                            type="button"
                            onClick={() => setExpandedUserId(expandedUserId === user.id ? null : user.id)}
                            className="text-plex-text-muted hover:text-plex-text cursor-pointer text-xs leading-none"
                            aria-label={expandedUserId === user.id ? 'Collapse details' : 'Expand details'}
                            data-testid={`expand-user-${user.id}`}
                          >
                            {expandedUserId === user.id ? '▼' : '▶'}
                          </button>
                        </td>
                        <td className="py-2 text-plex-text truncate" title={user.username}>{user.username}</td>
                        <td className="py-2 text-plex-text-secondary text-xs truncate" title={user.email || ''}>{user.email || '—'}</td>
                        <td className="py-2">
                          <span className={`text-xs px-2 py-0.5 rounded-full ${
                            user.role === 'ADMIN'
                              ? 'bg-plex-gold/20 text-plex-gold'
                              : 'bg-plex-surface-light text-plex-text-secondary'
                          }`}>
                            {user.role}
                          </span>
                        </td>
                        <td className="py-2 text-plex-text-muted text-xs">
                          {user.createdAt ? user.createdAt.slice(0, 10) : '—'}
                        </td>
                        <td className="py-2 text-plex-text-muted text-xs">
                          {user.lastActiveAt ? user.lastActiveAt.slice(0, 16).replace('T', ' ') : 'Never'}
                        </td>
                        <td className="py-2">
                          <button
                            onClick={() => handleToggleEnabled(user)}
                            className={`text-xs px-2 py-0.5 rounded cursor-pointer ${
                              user.enabled
                                ? 'bg-green-900/40 text-green-400 hover:bg-green-900/60'
                                : 'bg-red-900/40 text-red-400 hover:bg-red-900/60'
                            }`}
                            data-testid={`toggle-user-enabled-${user.id}`}
                          >
                            {user.enabled ? 'Enabled' : 'Disabled'}
                          </button>
                        </td>
                        <td className="py-2">
                          <div className="flex gap-1">
                            <button
                              className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                              onClick={() => handleStartEdit(user)}
                              data-testid={`edit-user-${user.id}`}
                            >
                              Edit
                            </button>
                            <button
                              className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text disabled:opacity-50 disabled:cursor-not-allowed"
                              onClick={() => handleResetPassword(user)}
                              disabled={resetPasswordLoadingId === user.id}
                              data-testid={`reset-password-${user.id}`}
                            >
                              {resetPasswordLoadingId === user.id ? 'Resetting...' : 'Reset PW'}
                            </button>
                            {!user.enabled && user.email && (
                              <button
                                className="text-xs px-2 py-0.5 rounded bg-blue-900/40 text-blue-400 hover:bg-blue-900/60 hover:text-blue-300 disabled:opacity-50 disabled:cursor-not-allowed"
                                onClick={() => handleResendVerification(user)}
                                disabled={resendLoadingId === user.id}
                                data-testid={`resend-verify-${user.id}`}
                              >
                                {resendLoadingId === user.id ? 'Sending...' : 'Resend Verify'}
                              </button>
                            )}
                            <button
                              className="text-xs px-2 py-0.5 rounded bg-red-900/40 text-red-400 hover:bg-red-900/60 hover:text-red-300"
                              onClick={() => handleDeleteUser(user)}
                              data-testid={`delete-user-${user.id}`}
                            >
                              Delete
                            </button>
                          </div>
                        </td>
                      </tr>
                      {expandedUserId === user.id && (
                        <tr className="border-b border-plex-surface" data-testid={`user-detail-${user.id}`}>
                          <td colSpan={8} className="py-3 px-4 bg-plex-surface-light/50">
                            <div className="flex flex-wrap gap-x-8 gap-y-2 text-xs">
                              <div className="flex gap-x-4">
                                <span className="text-plex-text-muted font-medium">Legal</span>
                                <span>
                                  <span className="text-plex-text-muted">Terms accepted: </span>
                                  <span data-testid={`terms-accepted-${user.id}`}>
                                    {user.termsAcceptedAt
                                      ? new Date(user.termsAcceptedAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
                                      : <span className="text-plex-text-muted">Not accepted</span>}
                                  </span>
                                </span>
                                <span>
                                  <span className="text-plex-text-muted">Terms version: </span>
                                  <span data-testid={`terms-version-${user.id}`}>
                                    {user.termsVersion || <span className="text-plex-text-muted">&mdash;</span>}
                                  </span>
                                </span>
                              </div>
                              <div className="flex gap-x-4">
                                <span className="text-plex-text-muted font-medium">Preferences</span>
                                <span>
                                  <span className="text-plex-text-muted">Home postcode: </span>
                                  <span data-testid={`home-postcode-${user.id}`}>
                                    {user.homePostcode || <span className="text-plex-text-muted">Not set</span>}
                                  </span>
                                </span>
                                <span>
                                  <span className="text-plex-text-muted">Marketing emails: </span>
                                  <span data-testid={`marketing-emails-${user.id}`}>
                                    {user.marketingEmailOptIn ? 'Yes' : 'No'}
                                  </span>
                                </span>
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  ))}
                  {filteredUsers.length === 0 && users.length > 0 && (
                    <tr>
                      <td colSpan={8} className="py-4 text-center text-xs text-plex-text-muted">
                        No users match the current filters.
                      </td>
                    </tr>
                  )}
                  {pageUsers.length > 0 && pageUsers.length < pagination.pageSize && (
                    Array.from({ length: pagination.pageSize - pageUsers.length }, (_, i) => (
                      <tr key={`spacer-${i}`} aria-hidden="true">
                        <td colSpan={8} className="py-2 text-sm">&nbsp;</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
              <Pagination
                page={pagination.page}
                totalPages={pagination.totalPages}
                pageSize={pagination.pageSize}
                totalItems={filteredUsers.length}
                onNextPage={pagination.nextPage}
                onPrevPage={pagination.prevPage}
                onFirstPage={pagination.firstPage}
                onLastPage={pagination.lastPage}
                onSetPageSize={pagination.setPageSize}
              />
            </div>
          )}
        </>
      )}

      {/* Add mode */}
      {mode === 'add' && (
        <div className="flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Add New User</p>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="add-user-username" className={labelClass}>Username</label>
              <input
                id="add-user-username"
                type="text"
                autoComplete="off"
                className={`${inputClass} ${newUsername.length > 0 && !addUsernameValid ? '!border-red-700' : ''}`}
                placeholder="e.g. janesmith"
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
              />
              <p className={`text-xs mt-1 ${newUsername.length > 0 && !addUsernameValid ? 'text-red-400' : 'text-plex-text-muted'}`}>
                3-30 characters, letters, numbers, hyphens, or underscores
              </p>
            </div>
            <div>
              <label htmlFor="add-user-email" className={labelClass}>Email</label>
              <input
                id="add-user-email"
                type="email"
                autoComplete="off"
                className={`${inputClass} ${newEmail.length > 0 && !addEmailValid ? '!border-red-700' : ''}`}
                placeholder="jane@example.com"
                value={newEmail}
                onChange={(e) => setNewEmail(e.target.value)}
              />
              {newEmail.length > 0 && !addEmailValid && (
                <p className="text-xs text-red-400 mt-1">Please enter a valid email address</p>
              )}
            </div>
            <div>
              <label htmlFor="add-user-password" className={labelClass}>Password</label>
              <div className="relative">
                <input
                  id="add-user-password"
                  type={showNewPassword ? 'text' : 'password'}
                  autoComplete="new-password"
                  className={`${inputClass} pr-10`}
                  placeholder="Temporary password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                />
                <button
                  type="button"
                  aria-label={showNewPassword ? 'Hide password' : 'Show password'}
                  onClick={() => setShowNewPassword((v) => !v)}
                  className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
                >
                  {showNewPassword ? (
                    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                      <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                      <path d="M3.28 2.22a.75.75 0 00-1.06 1.06l14.5 14.5a.75.75 0 101.06-1.06L3.28 2.22z" />
                    </svg>
                  ) : (
                    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                      <path d="M10 3C5 3 1.73 7.11 1.05 8.27a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                    </svg>
                  )}
                </button>
              </div>
            </div>
            <div>
              <label htmlFor="add-user-role" className={labelClass}>Role</label>
              <select
                id="add-user-role"
                className={selectClass}
                value={newRole}
                onChange={(e) => setNewRole(e.target.value)}
              >
                <option value="LITE_USER">LITE_USER</option>
                <option value="PRO_USER">PRO_USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </div>
          </div>

          {addUserError && (
            <p className="text-xs text-red-400">{addUserError}</p>
          )}

          <div className="flex justify-between">
            <button
              className="btn-secondary text-sm"
              onClick={handleCancel}
              disabled={addUserSaving}
            >
              Cancel
            </button>
            <button
              className="btn-primary text-sm"
              onClick={handleAddUser}
              disabled={addUserSaving}
            >
              {addUserSaving ? 'Saving...' : 'Create User'}
            </button>
          </div>
        </div>
      )}

      {/* Edit mode */}
      {mode === 'edit' && editingUser && (
        <div className="flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">
            Edit User: {editingUser.username}
          </p>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label htmlFor="edit-user-email" className={labelClass}>Email</label>
              <input
                id="edit-user-email"
                type="email"
                className={inputClass}
                value={editEmail}
                onChange={(e) => setEditEmail(e.target.value)}
                data-testid={`edit-email-${editingUser.id}`}
              />
            </div>
            <div>
              <label htmlFor="edit-user-role" className={labelClass}>Role</label>
              <select
                id="edit-user-role"
                className={selectClass}
                value={editRole}
                onChange={(e) => setEditRole(e.target.value)}
                data-testid={`edit-role-${editingUser.id}`}
              >
                <option value="LITE_USER">LITE_USER</option>
                <option value="PRO_USER">PRO_USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </div>
          </div>

          {editError && (
            <p className="text-xs text-red-400">{editError}</p>
          )}

          <div className="flex justify-between">
            <button
              className="btn-secondary text-sm"
              onClick={handleCancel}
              disabled={editSaving}
            >
              Cancel
            </button>
            <button
              className="btn-primary text-sm"
              onClick={handleSaveEdit}
              disabled={editSaving}
              data-testid={`save-edit-${editingUser.id}`}
            >
              {editSaving ? 'Saving...' : 'Save Changes'}
            </button>
          </div>
        </div>
      )}

      {/* Temp password modal */}
      {tempPasswordModal && (
        <Modal label="Temporary password" onClose={() => setTempPasswordModal(null)} data-testid="temp-password-modal">
            <p className="text-sm font-semibold text-plex-text">
              Temporary password for <span className="text-plex-gold">{tempPasswordModal.username}</span>
            </p>
            <div className="flex items-center gap-2">
              <code
                className="flex-1 font-mono text-base bg-plex-surface-light border border-plex-border rounded px-3 py-2 text-green-300 tracking-widest select-all"
                data-testid="temp-password-value"
              >
                {tempPasswordModal.password}
              </code>
              <button
                className="btn-secondary text-xs shrink-0"
                onClick={() => navigator.clipboard.writeText(tempPasswordModal.password)}
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
              onClick={() => setTempPasswordModal(null)}
              data-testid="close-temp-password-modal"
            >
              Close
            </button>
        </Modal>
      )}
      {dialogElement}
    </div>
  );
}
