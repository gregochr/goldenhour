import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import { runForecast, fetchLocations, addLocation } from '../api/forecastApi.js';
import { formatDateLabel } from '../utils/conversions.js';
import JobRunsMetricsView from './JobRunsMetricsView.jsx';
import LocationAlerts from './LocationAlerts.jsx';

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
  idle:    'bg-gray-800 text-gray-400 hover:bg-gray-700 hover:text-gray-200 cursor-pointer',
  running: 'bg-gray-700 text-amber-400 cursor-not-allowed',
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
  const [newRole, setNewRole] = useState('LITE_USER');
  const [addUserError, setAddUserError] = useState('');
  const [addUserSaving, setAddUserSaving] = useState(false);
  const [showNewPassword, setShowNewPassword] = useState(false);

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
    if (!trimmedUsername || !newPassword.trim()) {
      setAddUserError('Username and password are required.');
      return;
    }
    const isEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedUsername);
    if (trimmedUsername !== 'admin' && !isEmail) {
      setAddUserError('Username must be "admin" or a valid email address.');
      return;
    }
    setAddUserError('');
    setAddUserSaving(true);
    try {
      await axios.post('/api/users', {
        username: trimmedUsername,
        password: newPassword,
        role: newRole,
      });
      setNewUsername('');
      setNewPassword('');
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

  async function handleToggleEnabled(userId, currentEnabled) {
    try {
      await axios.put(`/api/users/${userId}/enabled`, { enabled: !currentEnabled });
      await fetchUsers();
    } catch {
      // Silently ignore
    }
  }

  return (
    <div className="flex flex-col gap-5">

      {/* Sub-tabs */}
      <div className="inline-flex rounded-lg border border-gray-700 bg-gray-900 p-0.5 gap-0.5 self-start">
        {[{ value: 'users', label: 'Users' }, { value: 'locations', label: 'Locations' }, { value: 'metrics', label: 'Job Runs' }].map((tab) => (
          <button
            key={tab.value}
            onClick={() => setManageTab(tab.value)}
            className={`px-4 py-1.5 text-sm font-medium rounded-md transition-colors ${
              manageTab === tab.value
                ? 'bg-gray-700 text-gray-100'
                : 'text-gray-500 hover:text-gray-300'
            }`}
            data-testid={`manage-tab-${tab.value}`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* ── Users tab ── */}
      {manageTab === 'users' && (
        <div className="card border border-gray-800 flex flex-col gap-4">
          <div className="flex items-center justify-between gap-4">
            <p className="text-sm font-semibold text-gray-100">User Management</p>
            <button
              className="btn-secondary text-xs shrink-0"
              onClick={() => setShowAddUserForm((v) => !v)}
            >
              {showAddUserForm ? '✕ Cancel' : '+ Add user'}
            </button>
          </div>

          {showAddUserForm && (
            <div className="flex flex-col gap-3">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label htmlFor="add-user-username" className="block text-xs text-gray-400 mb-1">Username</label>
                  <input
                    id="add-user-username"
                    type="text"
                    autoComplete="off"
                    className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                    placeholder="e.g. jane"
                    value={newUsername}
                    onChange={(e) => setNewUsername(e.target.value)}
                  />
                </div>
                <div>
                  <label htmlFor="add-user-password" className="block text-xs text-gray-400 mb-1">Password</label>
                  <div className="relative">
                    <input
                      id="add-user-password"
                      type={showNewPassword ? 'text' : 'password'}
                      autoComplete="new-password"
                      className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 pr-10 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                      placeholder="Temporary password"
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                    />
                    <button
                      type="button"
                      aria-label={showNewPassword ? 'Hide password' : 'Show password'}
                      onClick={() => setShowNewPassword((v) => !v)}
                      className="absolute inset-y-0 right-0 flex items-center px-3 text-gray-400 hover:text-gray-200"
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
                  <label htmlFor="add-user-role" className="block text-xs text-gray-400 mb-1">Role</label>
                  <select
                    id="add-user-role"
                    className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 focus:outline-none focus:ring-1 focus:ring-amber-500"
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
            <p className="text-sm text-gray-500 animate-pulse">Loading users…</p>
          )}

          {!usersLoading && users.length > 0 && (
            <table className="w-full text-sm text-left">
              <thead>
                <tr className="text-xs text-gray-500 border-b border-gray-800">
                  <th className="pb-2 font-medium">Username</th>
                  <th className="pb-2 font-medium">Role</th>
                  <th className="pb-2 font-medium">Created</th>
                  <th className="pb-2 font-medium">Enabled</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id} className="border-b border-gray-900 last:border-0">
                    <td className="py-2 text-gray-200">{user.username}</td>
                    <td className="py-2">
                      <span className={`text-xs px-2 py-0.5 rounded-full ${
                        user.role === 'ADMIN'
                          ? 'bg-amber-900/50 text-amber-400'
                          : 'bg-gray-800 text-gray-400'
                      }`}>
                        {user.role}
                      </span>
                    </td>
                    <td className="py-2 text-gray-500 text-xs">
                      {user.createdAt ? user.createdAt.slice(0, 10) : '—'}
                    </td>
                    <td className="py-2">
                      <button
                        className={`text-xs px-2 py-0.5 rounded ${
                          user.enabled
                            ? 'bg-green-900/40 text-green-400 hover:bg-green-900/70'
                            : 'bg-red-900/40 text-red-400 hover:bg-red-900/70'
                        }`}
                        onClick={() => handleToggleEnabled(user.id, user.enabled)}
                      >
                        {user.enabled ? 'Enabled' : 'Disabled'}
                      </button>
                    </td>
                  </tr>
                ))}
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
      <div className="card border border-gray-800 flex items-center justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-gray-100">Re-run all locations × all dates</p>
          <p className="text-xs text-gray-500 mt-0.5">
            {manageLocations.length} location{manageLocations.length !== 1 ? 's' : ''} × {DATES.length} days × sunrise + sunset
          </p>
          {anyRunning && (
            <p className="text-xs text-amber-400 mt-1 animate-pulse">
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
        <div className="card border border-gray-700 flex flex-col gap-4">
          <p className="text-sm font-semibold text-gray-100">Add location</p>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div className="sm:col-span-1">
              <label htmlFor="add-location-name" className="block text-xs text-gray-400 mb-1">Location name</label>
              <input
                id="add-location-name"
                type="text"
                className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                placeholder="e.g. Durham UK"
                value={addName}
                onChange={(e) => setAddName(e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="add-location-lat" className="block text-xs text-gray-400 mb-1">Latitude</label>
              <input
                id="add-location-lat"
                type="number"
                step="any"
                className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                placeholder="e.g. 54.7753"
                value={addLat}
                onChange={(e) => setAddLat(e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="add-location-lon" className="block text-xs text-gray-400 mb-1">Longitude</label>
              <input
                id="add-location-lon"
                type="number"
                step="any"
                className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
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
        <p className="text-sm text-gray-500 animate-pulse">Loading locations…</p>
      )}

      {/* Per-location cards */}
      {manageLocations.map((loc) => {
        const locRunning = DATES.some((d) => getStatus(loc.name, d) === 'running');
        const locDone    = DATES.filter((d) => getStatus(loc.name, d) === 'done').length;

        return (
          <div key={loc.name} className="card border border-gray-800 flex flex-col gap-4">

            {/* Location header */}
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-base font-semibold text-gray-100">{loc.name}</p>
                <p className="text-xs text-gray-500">
                  {loc.lat}° N, {Math.abs(loc.lon)}° {loc.lon < 0 ? 'W' : 'E'}
                </p>
                {locRunning && (
                  <p className="text-xs text-amber-400 mt-0.5 animate-pulse">
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
        <div className="card border border-gray-800 flex flex-col gap-4">
          <p className="text-sm font-semibold text-gray-100">Job Run Metrics</p>
          <JobRunsMetricsView />
        </div>
      )}

    </div>
  );
}

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
