import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import { runForecast, fetchLocations, addLocation } from '../api/forecastApi.js';
import { resetUserPassword, updateUserEmail, updateUserRole, updateUserEnabled } from '../api/userApi.js';
import { formatDateLabel } from '../utils/conversions.js';
import JobRunsMetricsView from './JobRunsMetricsView.jsx';
import LocationAlerts from './LocationAlerts.jsx';
import ModelSelectionView from './ModelSelectionView.jsx';

const FORECAST_DAYS = 8; // T through T+7

/**
 * Generates an array of UTC date strings from today through today + (FORECAST_DAYS - 1).
 *
 * @returns {Array<string>} Sorted array of YYYY-MM-DD strings.
 */
function buildDateRange() {
  return Array.from({ length: FORECAST_DAYS }, (_, i) => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + i);
    return d.toISOString().slice(0, 10);
  });
}

const DATES = buildDateRange();

const CHIP_STYLE = {
  idle:    'bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text cursor-pointer',
  running: 'bg-plex-border text-plex-gold cursor-not-allowed',
  done:    'bg-green-900/50 text-green-400 ring-1 ring-inset ring-green-700/40 hover:bg-green-900/70 cursor-pointer',
  error:   'bg-red-900/50 text-red-400 ring-1 ring-inset ring-red-700/40 hover:bg-red-900/70 cursor-pointer',
};

/**
 * Management screen for triggering on-demand forecast re-runs and adding new locations.
 * Fetches its own location list from the API and supports adding locations inline.
 *
 * @param {object}   props
 * @param {function} props.onComplete - Called after any run completes, so other views refresh.
 */
export default function ManageView({ onComplete }) {
  const [manageLocations, setManageLocations] = useState([]);
  const [locationsLoading, setLocationsLoading] = useState(true);
  const [status, setStatus] = useState(new Map());
  const [runningAll, setRunningAll] = useState(false);

  // Add-location form state
  const [showAddForm, setShowAddForm] = useState(false);
  const [addName, setAddName] = useState('');
  const [addLat, setAddLat] = useState('');
  const [addLon, setAddLon] = useState('');
  const [addError, setAddError] = useState('');
  const [addSaving, setAddSaving] = useState(false);

  // Sub-tab
  const [manageTab, setManageTab] = useState('users');

  // User management state
  const [users, setUsers] = useState([]);
  const [usersLoading, setUsersLoading] = useState(true);
  const [showAddUserForm, setShowAddUserForm] = useState(false);
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newRole, setNewRole] = useState('LITE_USER');
  const [addUserError, setAddUserError] = useState('');
  const [addUserSaving, setAddUserSaving] = useState(false);
  const [showNewPassword, setShowNewPassword] = useState(false);

  // Edit-user state
  const [editingUserId, setEditingUserId] = useState(null);
  const [editEmail, setEditEmail] = useState('');
  const [editRole, setEditRole] = useState('');
  const [editEnabled, setEditEnabled] = useState(false);
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState('');

  // Reset-password state
  const [resetPasswordLoadingId, setResetPasswordLoadingId] = useState(null);
  const [tempPasswordModal, setTempPasswordModal] = useState(null); // { username, password } | null
  const [resetPasswordError, setResetPasswordError] = useState('');

  async function refreshLocations() {
    try {
      const data = await fetchLocations();
      setManageLocations(data);
    } catch {
      // Silently keep existing list on refresh failure
    }
  }

  async function fetchUsers() {
    try {
      const res = await axios.get('/api/users');
      setUsers(res.data);
    } catch {
      // Silently keep existing list on failure
    }
  }

  useEffect(() => {
    fetchLocations()
      .then(setManageLocations)
      .finally(() => setLocationsLoading(false));
    axios.get('/api/users')
      .then((res) => setUsers(res.data))
      .finally(() => setUsersLoading(false));
  }, []);

  function getStatus(locationName, date) {
    return status.get(`${locationName}::${date}`) ?? 'idle';
  }

  function setOneStatus(locationName, date, s) {
    setStatus((prev) => new Map(prev).set(`${locationName}::${date}`, s));
  }

  const anyRunning = [...status.values()].some((s) => s === 'running');
  const doneCount  = [...status.values()].filter((s) => s === 'done').length;
  const totalCount = manageLocations.length * DATES.length;

  async function runOne(locationName, date) {
    setOneStatus(locationName, date, 'running');
    try {
      await runForecast(date, locationName, null);
      setOneStatus(locationName, date, 'done');
      onComplete();
    } catch {
      setOneStatus(locationName, date, 'error');
    }
  }

  async function runAllForLocation(locationName) {
    for (const date of DATES) {
      await runOne(locationName, date);
    }
  }

  async function runAll() {
    setRunningAll(true);
    try {
      for (const date of DATES) {
        for (const loc of manageLocations) {
          await runOne(loc.name, date);
        }
      }
    } finally {
      setRunningAll(false);
    }
  }

  function resetAddForm() {
    setAddName('');
    setAddLat('');
    setAddLon('');
    setAddError('');
    setShowAddForm(false);
  }

  async function handleAddLocation() {
    const trimmedName = addName.trim();
    const latNum = parseFloat(addLat);
    const lonNum = parseFloat(addLon);

    if (!trimmedName) {
      setAddError('Location name is required.');
      return;
    }
    if (isNaN(latNum) || latNum < -90 || latNum > 90) {
      setAddError('Latitude must be a number between −90 and 90.');
      return;
    }
    if (isNaN(lonNum) || lonNum < -180 || lonNum > 180) {
      setAddError('Longitude must be a number between −180 and 180.');
      return;
    }

    setAddError('');
    setAddSaving(true);
    try {
      await addLocation(trimmedName, latNum, lonNum);
      await refreshLocations();
      resetAddForm();
    } catch (err) {
      const message = err?.response?.data?.error ?? 'Failed to add location.';
      setAddError(message);
    } finally {
      setAddSaving(false);
    }
  }

  async function handleAddUser() {
    const trimmedUsername = newUsername.trim();
    const trimmedEmail    = newEmail.trim();
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
      setNewUsername('');
      setNewPassword('');
      setNewEmail('');
      setNewRole('LITE_USER');
      setShowNewPassword(false);
      setShowAddUserForm(false);
      await fetchUsers();
    } catch (err) {
      setAddUserError(err?.response?.data?.error ?? 'Failed to create user.');
    } finally {
      setAddUserSaving(false);
    }
  }

  function handleStartEdit(user) {
    setEditingUserId(user.id);
    setEditEmail(user.email || '');
    setEditRole(user.role);
    setEditEnabled(user.enabled);
    setEditError('');
  }

  function handleCancelEdit() {
    setEditingUserId(null);
    setEditEmail('');
    setEditRole('');
    setEditEnabled(false);
    setEditError('');
    setEditSaving(false);
  }

  async function handleSaveEdit(user) {
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
      if (trimmedEmail !== (user.email || '')) {
        calls.push(updateUserEmail(user.id, trimmedEmail));
      }
      if (editRole !== user.role) {
        calls.push(updateUserRole(user.id, editRole));
      }
      if (editEnabled !== user.enabled) {
        calls.push(updateUserEnabled(user.id, editEnabled));
      }
      await Promise.all(calls);
      await fetchUsers();
      handleCancelEdit();
    } catch (err) {
      setEditError(err?.response?.data?.error ?? 'Failed to save changes.');
    } finally {
      setEditSaving(false);
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

  return (
    <div className="flex flex-col gap-5">

      {/* Sub-tabs */}
      <div className="flex gap-6 border-b border-plex-border">
        {[{ value: 'users', label: 'Users' }, { value: 'locations', label: 'Locations' }, { value: 'metrics', label: 'Job Runs' }, { value: 'models', label: 'Model Config' }].map((tab) => (
          <button
            key={tab.value}
            onClick={() => setManageTab(tab.value)}
            className={`pb-2 text-sm font-medium transition-colors border-b-2 ${
              manageTab === tab.value
                ? 'text-plex-gold border-plex-gold'
                : 'text-plex-text-secondary hover:text-plex-text border-transparent'
            }`}
            data-testid={`manage-tab-${tab.value}`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* ── Users tab ── */}
      {manageTab === 'users' && (
        <div className="card flex flex-col gap-4">
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-plex-text">User Management</p>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={() => setShowAddUserForm((v) => !v)}
            >
              {showAddUserForm ? '✕ Cancel' : '+ Add user'}
            </button>
          </div>

          {showAddUserForm && (
            <div className="flex flex-col gap-3">
              <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
                <div>
                  <label htmlFor="add-user-username" className="block text-xs text-plex-text-secondary mb-1">Username</label>
                  <input
                    id="add-user-username"
                    type="text"
                    autoComplete="off"
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                    placeholder="e.g. janesmith"
                    value={newUsername}
                    onChange={(e) => setNewUsername(e.target.value)}
                  />
                </div>
                <div>
                  <label htmlFor="add-user-email" className="block text-xs text-plex-text-secondary mb-1">Email</label>
                  <input
                    id="add-user-email"
                    type="email"
                    autoComplete="off"
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                    placeholder="jane@example.com"
                    value={newEmail}
                    onChange={(e) => setNewEmail(e.target.value)}
                  />
                </div>
                <div>
                  <label htmlFor="add-user-password" className="block text-xs text-plex-text-secondary mb-1">Password</label>
                  <div className="relative">
                    <input
                      id="add-user-password"
                      type={showNewPassword ? 'text' : 'password'}
                      autoComplete="new-password"
                      className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
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
                  <label htmlFor="add-user-role" className="block text-xs text-plex-text-secondary mb-1">Role</label>
                  <select
                    id="add-user-role"
                    className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold"
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
              <div className="flex gap-2">
                <button
                  className="btn-primary text-sm"
                  onClick={handleAddUser}
                  disabled={addUserSaving}
                >
                  {addUserSaving ? 'Saving…' : 'Create user'}
                </button>
                <button
                  className="btn-secondary text-sm"
                  onClick={() => { setShowAddUserForm(false); setAddUserError(''); }}
                  disabled={addUserSaving}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}

          {usersLoading && (
            <p className="text-sm text-plex-text-muted animate-pulse">Loading users…</p>
          )}

          {resetPasswordError && (
            <p className="text-xs text-red-400">{resetPasswordError}</p>
          )}

          {editError && (
            <p className="text-xs text-red-400">{editError}</p>
          )}

          {!usersLoading && users.length > 0 && (
            <table className="w-full text-sm text-left">
              <thead>
                <tr className="text-xs text-plex-text-muted border-b border-plex-border">
                  <th className="pb-2 font-medium">Username</th>
                  <th className="pb-2 font-medium">Email</th>
                  <th className="pb-2 font-medium">Role</th>
                  <th className="pb-2 font-medium">Created</th>
                  <th className="pb-2 font-medium">Enabled</th>
                  <th className="pb-2 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => {
                  const isEditing = editingUserId === user.id;
                  return (
                    <tr key={user.id} className="border-b border-plex-surface last:border-0">
                      <td className="py-2 text-plex-text">{user.username}</td>
                      <td className="py-2">
                        {isEditing ? (
                          <input
                            data-testid={`edit-email-${user.id}`}
                            type="email"
                            className="w-full bg-plex-surface-light border border-plex-border rounded px-2 py-1 text-xs text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold"
                            value={editEmail}
                            onChange={(e) => setEditEmail(e.target.value)}
                          />
                        ) : (
                          <span className="text-plex-text-secondary text-xs">{user.email || '—'}</span>
                        )}
                      </td>
                      <td className="py-2">
                        {isEditing ? (
                          <select
                            data-testid={`edit-role-${user.id}`}
                            className="bg-plex-surface-light border border-plex-border rounded px-2 py-1 text-xs text-plex-text focus:outline-none focus:ring-1 focus:ring-plex-gold"
                            value={editRole}
                            onChange={(e) => setEditRole(e.target.value)}
                          >
                            <option value="LITE_USER">LITE_USER</option>
                            <option value="PRO_USER">PRO_USER</option>
                            <option value="ADMIN">ADMIN</option>
                          </select>
                        ) : (
                          <span className={`text-xs px-2 py-0.5 rounded-full ${
                            user.role === 'ADMIN'
                              ? 'bg-plex-gold/20 text-plex-gold'
                              : 'bg-plex-surface-light text-plex-text-secondary'
                          }`}>
                            {user.role}
                          </span>
                        )}
                      </td>
                      <td className="py-2 text-plex-text-muted text-xs">
                        {user.createdAt ? user.createdAt.slice(0, 10) : '—'}
                      </td>
                      <td className="py-2">
                        {isEditing ? (
                          <input
                            data-testid={`edit-enabled-${user.id}`}
                            type="checkbox"
                            checked={editEnabled}
                            onChange={(e) => setEditEnabled(e.target.checked)}
                            className="accent-plex-gold"
                          />
                        ) : (
                          <span className={`text-xs px-2 py-0.5 rounded ${
                            user.enabled
                              ? 'bg-green-900/40 text-green-400'
                              : 'bg-red-900/40 text-red-400'
                          }`}>
                            {user.enabled ? 'Enabled' : 'Disabled'}
                          </span>
                        )}
                      </td>
                      <td className="py-2">
                        {isEditing ? (
                          <div className="flex gap-1">
                            <button
                              data-testid={`save-edit-${user.id}`}
                              className="text-xs px-2 py-0.5 rounded bg-plex-gold text-gray-900 hover:bg-plex-gold-light disabled:opacity-50 disabled:cursor-not-allowed"
                              onClick={() => handleSaveEdit(user)}
                              disabled={editSaving}
                            >
                              {editSaving ? 'Saving…' : 'Save'}
                            </button>
                            <button
                              data-testid={`cancel-edit-${user.id}`}
                              className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                              onClick={handleCancelEdit}
                              disabled={editSaving}
                            >
                              Cancel
                            </button>
                          </div>
                        ) : (
                          <div className="flex gap-1">
                            <button
                              data-testid={`edit-user-${user.id}`}
                              className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text"
                              onClick={() => handleStartEdit(user)}
                              disabled={editingUserId !== null}
                            >
                              Edit
                            </button>
                            <button
                              data-testid={`reset-password-${user.id}`}
                              className="text-xs px-2 py-0.5 rounded bg-plex-surface-light text-plex-text-secondary hover:bg-plex-border hover:text-plex-text disabled:opacity-50 disabled:cursor-not-allowed"
                              onClick={() => handleResetPassword(user)}
                              disabled={resetPasswordLoadingId === user.id || editingUserId !== null}
                            >
                              {resetPasswordLoadingId === user.id ? 'Resetting…' : 'Reset Password'}
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      )}

      {/* ── Locations tab ── */}
      {manageTab === 'locations' && <>

      {/* Location alerts (failing/disabled) */}
      <LocationAlerts
        locations={manageLocations}
        onReenabledLocation={() => refreshLocations()}
      />

      {/* Global re-run */}
      <div className="card flex items-center justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-plex-text">Re-run all locations × all dates</p>
          <p className="text-xs text-plex-text-muted mt-0.5">
            {manageLocations.length} location{manageLocations.length !== 1 ? 's' : ''} × {DATES.length} days × sunrise + sunset
          </p>
          {anyRunning && (
            <p className="text-xs text-plex-gold mt-1 animate-pulse">
              {doneCount} / {totalCount} date-locations complete…
            </p>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            className="btn-secondary text-xs"
            onClick={() => setShowAddForm((v) => !v)}
            disabled={anyRunning}
          >
            {showAddForm ? '✕ Cancel' : '+ Add location'}
          </button>
          <button
            className="btn-primary text-sm"
            onClick={runAll}
            disabled={anyRunning}
          >
            {runningAll ? '⟳ Running…' : '⟳ Re-run All'}
          </button>
        </div>
      </div>

      {/* Add location form */}
      {showAddForm && (
        <div className="card flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Add location</p>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div className="sm:col-span-1">
              <label htmlFor="add-location-name" className="block text-xs text-plex-text-secondary mb-1">Location name</label>
              <input
                id="add-location-name"
                type="text"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                placeholder="e.g. Durham UK"
                value={addName}
                onChange={(e) => setAddName(e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="add-location-lat" className="block text-xs text-plex-text-secondary mb-1">Latitude</label>
              <input
                id="add-location-lat"
                type="number"
                step="any"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                placeholder="e.g. 54.7753"
                value={addLat}
                onChange={(e) => setAddLat(e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="add-location-lon" className="block text-xs text-plex-text-secondary mb-1">Longitude</label>
              <input
                id="add-location-lon"
                type="number"
                step="any"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-1.5 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                placeholder="e.g. -1.5849"
                value={addLon}
                onChange={(e) => setAddLon(e.target.value)}
              />
            </div>
          </div>
          {addError && (
            <p className="text-xs text-red-400">{addError}</p>
          )}
          <div className="flex gap-2">
            <button
              className="btn-primary text-sm"
              onClick={handleAddLocation}
              disabled={addSaving}
            >
              {addSaving ? 'Saving…' : 'Done'}
            </button>
            <button
              className="btn-secondary text-sm"
              onClick={resetAddForm}
              disabled={addSaving}
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {/* Loading state */}
      {locationsLoading && (
        <p className="text-sm text-plex-text-muted animate-pulse">Loading locations…</p>
      )}

      {/* Per-location cards */}
      {manageLocations.map((loc) => {
        const locRunning = DATES.some((d) => getStatus(loc.name, d) === 'running');
        const locDone    = DATES.filter((d) => getStatus(loc.name, d) === 'done').length;

        return (
          <div key={loc.name} className="card flex flex-col gap-4">

            {/* Location header */}
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-base font-semibold text-plex-text">{loc.name}</p>
                <p className="text-xs text-plex-text-muted">
                  {loc.lat}° N, {Math.abs(loc.lon)}° {loc.lon < 0 ? 'W' : 'E'}
                </p>
                {locRunning && (
                  <p className="text-xs text-plex-gold mt-0.5 animate-pulse">
                    {locDone} / {DATES.length} dates complete…
                  </p>
                )}
              </div>
              <button
                className="btn-secondary text-xs shrink-0"
                onClick={() => runAllForLocation(loc.name)}
                disabled={anyRunning}
              >
                {locRunning ? '⟳ Running…' : '⟳ All dates'}
              </button>
            </div>

            {/* Date chips */}
            <div className="flex flex-wrap gap-2">
              {DATES.map((date) => {
                const s = getStatus(loc.name, date);
                return (
                  <button
                    key={date}
                    className={`inline-flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-full transition-colors ${CHIP_STYLE[s]}`}
                    onClick={() => runOne(loc.name, date)}
                    disabled={anyRunning}
                    aria-label={`Re-run ${loc.name} for ${formatDateLabel(date)}`}
                  >
                    {s === 'running' && <span className="inline-block animate-spin">⟳</span>}
                    {s === 'done'    && <span>✓</span>}
                    {s === 'error'   && <span>✗</span>}
                    {formatDateLabel(date)}
                  </button>
                );
              })}
            </div>

          </div>
        );
      })}
      </>}

      {/* ── Metrics tab ── */}
      {manageTab === 'metrics' && (
        <div className="card flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Job Run Metrics</p>
          <JobRunsMetricsView />
        </div>
      )}

      {/* ── Models tab ── */}
      {manageTab === 'models' && (
        <ModelSelectionView />
      )}

      {/* ── Temp Password Modal ── */}
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

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
