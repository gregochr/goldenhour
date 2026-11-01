import PropTypes from 'prop-types';
import PanelSection from '../data/PanelSection.jsx';
import { changedDimensionsText, pickHeadline, pickLine, rankLabel } from '../../utils/cycleView.js';
import { formatClockUk } from '../../utils/runsView.js';

/** One rank's card: the current pick, and for a changed one "Was {pick}" struck through and the changed dimensions. */
function PlanCard({ diff }) {
  const headline = pickHeadline(diff.intraday);
  // The old values stay visible: a changed card names the nightly pick by its headline AND its line (region, rating).
  const wasLine = diff.nightly?.headline ? pickLine(diff.nightly) : null;
  return (
    <div className="rounded-lg border border-plex-border px-3 py-2.5" data-testid={`cross-run-diff-${diff.rank}`}>
      <p className="font-mono text-[10.5px] uppercase tracking-wider text-plex-text-muted">{rankLabel(diff.rank)}</p>
      <p className="mt-1 break-words text-[13.5px] text-plex-text" data-testid={`cross-run-pick-${diff.rank}`}>{headline}</p>
      {diff.changed ? (
        <>
          <p className="mt-1 break-words text-[12.5px] text-plex-text-muted line-through" data-testid={`cross-run-was-${diff.rank}`}>
            Was {pickHeadline(diff.nightly)}
            {wasLine && <span className="block" data-testid={`cross-run-was-line-${diff.rank}`}>{wasLine}</span>}
          </p>
          <p className="mt-0.5 text-[12.5px] text-verdict-marginal" data-testid={`cross-run-changed-${diff.rank}`}>
            {changedDimensionsText(diff)}
          </p>
        </>
      ) : (
        <p className="mt-1 text-[12.5px] text-plex-text-muted">No change</p>
      )}
    </div>
  );
}

PlanCard.propTypes = { diff: PropTypes.object.isRequired };

/**
 * "vs this morning's nightly" (plan §5.2): only for a cycle with a {@code comparison}. A chip says Plan changed (amber)
 * or No change; one card per rank (Plan A, Plan B, then Plan {n}), a stay-home pick in words; then the baseline.
 *
 * @param {object} props
 * @param {object} props.comparison {@code baselineRunId}, {@code baselineTriggerTime} and {@code diffs}
 */
export default function CycleComparison({ comparison }) {
  const diffs = comparison.diffs ?? [];
  const anyChanged = diffs.some((d) => d.changed);
  return (
    <PanelSection
      label="vs this morning's nightly"
      testId="cross-run-comparison"
      action={(
        <span
          className={`inline-flex items-center gap-1.5 text-[12px] ${anyChanged ? 'text-verdict-marginal' : 'text-plex-text-secondary'}`}
          data-testid="cross-run-verdict"
        >
          <span className={`size-1.5 rounded-full ${anyChanged ? 'bg-verdict-marginal' : 'bg-plex-ok'}`} aria-hidden="true" />
          {anyChanged ? 'Plan changed' : 'No change'}
        </span>
      )}
    >
      <div className="flex flex-col gap-2.5">
        {diffs.map((diff) => <PlanCard key={diff.rank} diff={diff} />)}
      </div>
      <p className="mt-2.5 break-words text-[12.5px] text-plex-text-secondary" data-testid="cross-run-baseline">
        {[`Baseline: nightly cycle #${comparison.baselineRunId}`, formatClockUk(comparison.baselineTriggerTime)]
          .filter(Boolean).join(' \u00b7 ')}
      </p>
    </PanelSection>
  );
}

CycleComparison.propTypes = {
  comparison: PropTypes.shape({
    baselineRunId: PropTypes.number,
    baselineTriggerTime: PropTypes.string,
    diffs: PropTypes.arrayOf(PropTypes.object),
  }).isRequired,
};
