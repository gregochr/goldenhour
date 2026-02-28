import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { resetUserPassword, updateUserEmail, updateUserRole, updateUserEnabled } from '../api/userApi.js';

/**
 * User management view with list/add/edit modes and consistent card pattern.
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

  async function handleAddUser() {
    const trimmedUsername = newUsername.trim();
    const trimmedEmail = newEmail.trim();
    if (!trimmedUsername || !newPassword.trim()) {
      setAddUserError('Username and password are required.');
      return;
    }
    if (trimmedUsername.length < 5) {
      setAddUserError('Username must be at least 5 characters.');
      return;
    }
    if (!trimmedEmail) {
      setAddUserError('Email address is required.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) {
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

  async function handleToggleEnabled(user) {
    try {
      await updateUserEnabled(user.id, !user.enabled);
      await fetchUsers();
    } catch (err) {
      console.error('Failed to toggle user enabled:', err);
    }
  }

  async function handleResetPassword(user) {
    if (!window.confirm(`Reset password for ${user.username}?`)) return;
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

          {!usersLoading && users.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm text-left" data-testid="users-table">
                <thead>
                  <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                    <th className="pb-2 font-medium">Username</th>
                    <th className="pb-2 font-medium">Email</th>
                    <th className="pb-2 font-medium">Role</th>
                    <th className="pb-2 font-medium">Created</th>
                    <th className="pb-2 font-medium">Status</th>
                    <th className="pb-2 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => (
                    <tr key={user.id} className={`border-b border-plex-surface last:border-0 ${!user.enabled ? 'opacity-50' : ''}`}>
                      <td className="py-2 text-plex-text">{user.username}</td>
                      <td className="py-2 text-plex-text-secondary text-xs">{user.email || '—'}</td>
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
                            {resetPasswordLoadingId === user.id ? 'Resetting...' : 'Reset'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
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
                className={inputClass}
                placeholder="e.g. janesmith"
                value={newUsername}
                onChange={(e) => setNewUsername(e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="add-user-email" className={labelClass}>Email</label>
              <input
                id="add-user-email"
                type="email"
                autoComplete="off"
                className={inputClass}
                placeholder="jane@example.com"
                value={newEmail}
                onChange={(e) => setNewEmail(e.target.value)}
              />
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
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
          role="dialog"
          aria-modal="true"
          aria-label="Temporary password"
          data-testid="temp-password-modal"
        >
          <div className="bg-plex-surface border border-plex-border rounded-xl shadow-2xl p-6 w-full max-w-md flex flex-col gap-4">
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
          </div>
        </div>
      )}
    </div>
  );
}
