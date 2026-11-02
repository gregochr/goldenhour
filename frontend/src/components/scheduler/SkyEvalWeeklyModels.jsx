import { useEffect, useState } from 'react';
import { getAvailableModels } from '../../api/modelsApi.js';
import { getSkyRatingEvalSchedule, setSkyRatingEvalScheduled } from '../../api/skyRatingEvalApi.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { modelLabel } from '../../utils/modelLabels.js';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import PanelSection from '../data/PanelSection.jsx';
import { Swatch } from '../skyeval/parts.jsx';

/**
 * The weekly Sky Eval run's model checkboxes, in the Scheduler panel of the {@code sky_rating_eval} job (they used
 * to sit on the Sky Eval page). One checkbox per selectable model, ticked from the served schedule; a toggle is
 * optimistic, applies only that model's response row, and reverts only that model if the PUT fails, so two quick
 * toggles cannot undo each other. If the schedule fails to load the boxes stay disabled and unticked, never
 * ticked by guess.
 */
export default function SkyEvalWeeklyModels() {
  // model -> scheduled; null until loaded. scheduleModels keeps the server's order.
  const [schedule, setSchedule] = useState(null);
  const [scheduleModels, setScheduleModels] = useState([]);
  const [loadError, setLoadError] = useState(null);
  const [toggleError, setToggleError] = useState(null);
  const [toggling, setToggling] = useState([]);

  useEffect(() => {
    (async () => {
      try {
        const res = await getSkyRatingEvalSchedule();
        const next = {};
        const order = [];
        (res.data || []).forEach((row) => { next[row.model] = row.scheduled; order.push(row.model); });
        setScheduleModels(order);
        setSchedule(next);
      } catch (err) {
        setLoadError(apiErrorMessage(err, err?.message || 'Failed to load the weekly schedule'));
        // The boxes still list the selectable models, disabled and unticked, so the row is not blank.
        try {
          const data = await getAvailableModels();
          setScheduleModels((data?.available ?? []).map((m) => m.name));
        } catch {
          // no list either: the error above says why
        }
      }
    })();
  }, []);

  const toggle = async (model, next) => {
    setToggleError(null);
    setToggling((prev) => [...prev, model]);
    setSchedule((prev) => ({ ...prev, [model]: next }));
    try {
      const res = await setSkyRatingEvalScheduled(model, next);
      setSchedule((prev) => ({ ...prev, [res.data.model]: res.data.scheduled }));
    } catch (err) {
      setSchedule((prev) => ({ ...prev, [model]: !next }));
      setToggleError(apiErrorMessage(err, err?.message || 'Failed to change the weekly schedule'));
    } finally {
      setToggling((prev) => prev.filter((m) => m !== model));
    }
  };

  const ready = schedule !== null;
  const noneScheduled = ready && scheduleModels.length > 0 && scheduleModels.every((m) => schedule[m] !== true);

  return (
    <PanelSection label="Weekly models" testId="sky-eval-schedule-row">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2" role="group" aria-label="Weekly models">
        {scheduleModels.map((m) => (
          <label key={m} className="flex cursor-pointer items-center gap-1.5 text-sm">
            <input
              type="checkbox"
              checked={ready && schedule[m] === true}
              disabled={!ready || toggling.includes(m)}
              onChange={(e) => toggle(m, e.target.checked)}
              className="accent-plex-gold"
              data-testid={`sky-eval-schedule-${m}`}
            />
            <Swatch model={m} />
            <span>{modelLabel(m)}</span>
          </label>
        ))}
      </div>
      {loadError && <p className="mt-2 text-xs text-red-400" data-testid="sky-eval-schedule-error">{loadError}</p>}
      <ErrorBanner message={toggleError} className="mt-2" data-testid="sky-eval-error" />
      {noneScheduled && (
        <p className="mt-2 text-xs text-amber-300" data-testid="sky-eval-schedule-none">
          No model is ticked, so the weekly run will be skipped.
        </p>
      )}
    </PanelSection>
  );
}
