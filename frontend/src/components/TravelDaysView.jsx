import { useCallback, useEffect, useState } from 'react';
import {
  fetchTravelDays,
  addTravelDay,
  deleteTravelDay,
} from '../api/travelDayApi.js';

/**
 * Formats an ISO date (YYYY-MM-DD) for display, e.g. "Wed 1 Jul 2026".
 *
 * @param {string} iso - ISO date string
 * @returns {string} a human-readable date
 */
function formatDate(iso) {
  const d = new Date(`${iso}T00:00:00`);
  return d.toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * Admin panel for managing travel-day ranges. On a travel day the operator is
 * away (typically in London) and cannot shoot, so the overnight forecast batch
 * skips any forecast whose target date falls inside a declared range — saving
 * Claude spend on forecasts that will never be acted on.
 *
 * Lives under Manage → Operations → Travel Days.
 */
export default function TravelDaysView() {
  const [ranges, setRanges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [note, setNote] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await fetchTravelDays();
      setRanges(data);
      setError(null);
    } catch {
      setError('Failed to load travel days');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    (async () => {
      await load();
    })();
  }, [load]);

  // Open the native date calendar when the field is clicked anywhere (not just the
  // small indicator icon). showPicker requires a user gesture; the click provides it.
  const openPicker = (e) => {
    try {
      e.currentTarget.showPicker?.();
    } catch {
      // showPicker is unsupported on this browser, or the picker is already open.
    }
  };

  const handleAdd = async (e) => {
    e.preventDefault();
    if (!startDate || !endDate) {
      setError('Both a start and end date are required');
      return;
    }
    if (endDate < startDate) {
      setError('End date must not be before start date');
      return;
    }
    setSaving(true);
    try {
      await addTravelDay({ startDate, endDate, note: note.trim() || null });
      setStartDate('');
      setEndDate('');
      setNote('');
      setError(null);
      await load();
    } catch {
      setError('Failed to add travel range');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id) => {
    try {
      await deleteTravelDay(id);
      await load();
    } catch {
      setError('Failed to delete travel range');
    }
  };

  if (loading) {
    return <p className="text-sm text-plex-text-muted">Loading travel days...</p>;
  }

  return (
    <div className="flex flex-col gap-4" data-testid="travel-days-view">
      <p className="text-xs text-plex-text-muted">
        On a travel day you&apos;re away and can&apos;t shoot, so the overnight batch
        skips any forecast whose target date falls inside a range — no Claude spend
        on days you can&apos;t use. Travel changes weekly; add and remove ranges as
        your roster changes. Both the start and end date are included.
      </p>

      {error && (
        <div className="text-sm text-red-400 bg-red-900/20 px-3 py-2 rounded">
          {error}
        </div>
      )}

      <form
        onSubmit={handleAdd}
        className="flex flex-wrap items-end gap-3 border border-plex-border rounded-lg p-4"
        data-testid="travel-day-form"
      >
        <label className="flex flex-col gap-1 text-xs text-plex-text-muted">
          From <span className="text-plex-text-muted/70">(inclusive)</span>
          <input
            type="date"
            value={startDate}
            onChange={(e) => setStartDate(e.target.value)}
            onClick={openPicker}
            className="date-field bg-plex-bg border border-plex-border rounded px-2 py-1 text-sm text-plex-text cursor-pointer"
            data-testid="travel-day-start"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-plex-text-muted">
          To <span className="text-plex-text-muted/70">(inclusive)</span>
          <input
            type="date"
            value={endDate}
            onChange={(e) => setEndDate(e.target.value)}
            onClick={openPicker}
            className="date-field bg-plex-bg border border-plex-border rounded px-2 py-1 text-sm text-plex-text cursor-pointer"
            data-testid="travel-day-end"
          />
        </label>
        <label className="flex flex-col gap-1 text-xs text-plex-text-muted flex-1 min-w-[8rem]">
          Note (optional)
          <input
            type="text"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="London — work"
            className="bg-plex-bg border border-plex-border rounded px-2 py-1 text-sm text-plex-text"
            data-testid="travel-day-note"
          />
        </label>
        <button
          type="submit"
          disabled={saving}
          className="bg-plex-accent text-black text-sm font-semibold px-4 py-1.5 rounded disabled:opacity-50"
          data-testid="travel-day-add"
        >
          {saving ? 'Adding…' : 'Add range'}
        </button>
      </form>

      {ranges.length === 0 ? (
        <p className="text-sm text-plex-text-muted" data-testid="travel-day-empty">
          No travel days set — every date is treated as workable.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {ranges.map((r) => (
            <li
              key={r.id}
              className="flex items-center justify-between border border-plex-border rounded-lg px-4 py-2"
              data-testid={`travel-day-${r.id}`}
            >
              <div>
                <span className="text-sm text-plex-text">
                  {formatDate(r.startDate)}
                  {r.endDate !== r.startDate && ` → ${formatDate(r.endDate)}`}
                </span>
                {r.note && (
                  <p className="text-xs text-plex-text-muted mt-0.5">{r.note}</p>
                )}
              </div>
              <button
                type="button"
                onClick={() => handleDelete(r.id)}
                className="text-xs text-red-400 hover:text-red-300"
                data-testid={`travel-day-delete-${r.id}`}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
