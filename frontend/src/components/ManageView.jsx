import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { runForecast, fetchLocations, addLocation } from '../api/forecastApi.js';
import { formatDateLabel } from '../utils/conversions.js';

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

  async function refreshLocations() {
    try {
      const data = await fetchLocations();
      setManageLocations(data);
    } catch {
      // Silently keep existing list on refresh failure
    }
  }

  useEffect(() => {
    fetchLocations()
      .then(setManageLocations)
      .finally(() => setLocationsLoading(false));
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

  return (
    <div className="flex flex-col gap-5">

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
              <label className="block text-xs text-gray-400 mb-1">Location name</label>
              <input
                type="text"
                className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                placeholder="e.g. Durham UK"
                value={addName}
                onChange={(e) => setAddName(e.target.value)}
              />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Latitude</label>
              <input
                type="number"
                step="any"
                className="w-full bg-gray-800 border border-gray-700 rounded px-3 py-1.5 text-sm text-gray-100 placeholder-gray-600 focus:outline-none focus:ring-1 focus:ring-amber-500"
                placeholder="e.g. 54.7753"
                value={addLat}
                onChange={(e) => setAddLat(e.target.value)}
              />
            </div>
            <div>
              <label className="block text-xs text-gray-400 mb-1">Longitude</label>
              <input
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
    </div>
  );
}

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
