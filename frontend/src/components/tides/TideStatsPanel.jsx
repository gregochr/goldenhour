import PropTypes from 'prop-types';
import KvList from '../data/KvList.jsx';
import PanelSection from '../data/PanelSection.jsx';
import PanelShell from '../data/PanelShell.jsx';
import StatusDot from '../data/StatusDot.jsx';
import TideRangeBar from '../data/TideRangeBar.jsx';
import usePanelEscape from '../../hooks/usePanelEscape.js';
import {
  NO_VALUE, fmtCount, fmtMetres, fmtPercent, isThin, numOrNull, tideTypesText,
} from '../../utils/tideAdmin.js';

/** A signed metres figure for the bar's end labels ("−2.89 m", "+2.87 m"), or an em dash. */
function signedMetres(val) {
  const n = numOrNull(val);
  if (n == null) return NO_VALUE;
  return `${n < 0 ? '\u2212' : '+'}${Math.abs(n).toFixed(2)} m`;
}

/** A metres figure with its unit, or an em dash. */
function metres(val) {
  return val == null || fmtMetres(val) === NO_VALUE ? NO_VALUE : `${fmtMetres(val)} m`;
}

/** One big mono figure with its label (the Range section's 3x2 grid). */
function Stat({ label, value, tone = '', testId }) {
  return (
    <div className="min-w-0">
      <p className="text-[11px] text-plex-text-secondary">{label}</p>
      <p className={`font-mono text-[15px] ${tone || 'text-plex-text'}`} data-testid={testId}>{value}</p>
    </div>
  );
}

Stat.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  tone: PropTypes.string,
  testId: PropTypes.string.isRequired,
};

/**
 * The panel a Tides row opens (data-tabs-redesign-plan.md §7): the thin-data warning when it applies, the range bar
 * and its six figures, the high-tide percentiles, the spring and king tide thresholds, and the location's own tide
 * preference and status with a link to it in Locations. Read-only: nothing here is edited.
 *
 * @param {object} props
 * @param {object} props.row the joined row ({@code buildTideRows})
 * @param {number|null} props.median the median reading count across the list, for the thin-data warning
 * @param {() => void} props.onClose closes the panel
 * @param {{current: HTMLElement|null}} props.panelRef set to the panel's root, for Esc
 */
export default function TideStatsPanel({ row, median, onClose, panelRef }) {
  const s = row.stats;
  usePanelEscape({ active: true, panelRef, onEscape: onClose });

  let status = NO_VALUE;
  if (row.enabled != null) {
    status = row.enabled
      ? <StatusDot tone="go" testId="tide-panel-status">Active</StatusDot>
      : <StatusDot tone="off" testId="tide-panel-status">Disabled</StatusDot>;
  }

  return (
    <PanelShell
      title={row.name}
      subtitle={[row.subRegion, s.dataPoints == null ? null : `${fmtCount(s.dataPoints)} readings`]
        .filter(Boolean).join(' · ') || undefined}
      onClose={onClose}
      testIdPrefix="tide-panel-shell"
    >
      {isThin(row) && (
        <div className="border-b border-plex-border bg-verdict-marginal/10 px-[18px] py-3 text-[13px] text-plex-text max-[639px]:px-4" role="note" data-testid="tide-thin-warning">
          <p className="mb-0.5 font-mono text-[10px] uppercase tracking-[0.12em] text-verdict-marginal">Thin data</p>
          {fmtCount(s.dataPoints)} readings
          {median != null ? `, against about ${fmtCount(median)} for its neighbours` : ''}. Percentiles here are less reliable.
        </div>
      )}
      <PanelSection label="Range">
        <TideRangeBar
          size="lg"
          minLow={numOrNull(s.minLowMetres)}
          avgLow={numOrNull(s.avgLowMetres)}
          avgHigh={numOrNull(s.avgHighMetres)}
          maxHigh={numOrNull(s.maxHighMetres)}
          p95High={numOrNull(s.p95HighMetres)}
          testId="tide-panel-bar"
        />
        <div className="mt-1 flex justify-between font-mono text-[11px] text-plex-text-secondary" data-testid="tide-panel-bar-labels">
          <span>{signedMetres(s.minLowMetres)}</span>
          <span>0</span>
          <span>{signedMetres(s.maxHighMetres)}</span>
        </div>
        <div className="mt-3 grid grid-cols-3 gap-x-3 gap-y-3">
          <Stat label="Highest high" value={metres(s.maxHighMetres)} testId="tide-panel-max-high" />
          <Stat label="Average high" value={metres(s.avgHighMetres)} testId="tide-panel-avg-high" />
          <Stat label="Average range" value={metres(s.avgRangeMetres)} testId="tide-panel-avg-range" />
          <Stat label="Lowest low" value={metres(s.minLowMetres)} testId="tide-panel-min-low" />
          <Stat label="Average low" value={metres(s.avgLowMetres)} testId="tide-panel-avg-low" />
          <Stat
            label="Readings"
            value={fmtCount(s.dataPoints)}
            tone={isThin(row) ? 'text-verdict-marginal' : ''}
            testId="tide-panel-readings"
          />
        </div>
      </PanelSection>
      <PanelSection label="High-tide percentiles">
        <div className="grid grid-cols-3 gap-x-3">
          <Stat label="P75" value={metres(s.p75HighMetres)} testId="tide-panel-p75" />
          <Stat label="P90" value={metres(s.p90HighMetres)} testId="tide-panel-p90" />
          <Stat label="P95 · king" value={metres(s.p95HighMetres)} tone="text-verdict-marginal" testId="tide-panel-p95" />
        </div>
      </PanelSection>
      <PanelSection label="Spring and king tides">
        <KvList
          items={[
            {
              label: 'Spring',
              value: s.springTideThreshold == null
                ? null
                : `above ${fmtMetres(s.springTideThreshold)} m · ${fmtPercent(s.springTideFrequency)} of highs · ${fmtCount(s.springTideCount)}`,
              mono: true,
              testId: 'tide-panel-spring',
            },
            {
              label: 'King',
              value: s.kingTideThreshold == null
                ? null
                : `above ${fmtMetres(s.kingTideThreshold)} m · ${fmtCount(s.kingTideCount)} highs`,
              mono: true,
              testId: 'tide-panel-king',
            },
          ]}
        />
        <p className="mt-2 text-xs text-plex-text-secondary">
          Spring is more than 125% of the average high. King is above P95.
        </p>
      </PanelSection>
      <PanelSection label="Location">
        <KvList
          items={[
            { label: 'Tides worth shooting', value: tideTypesText(row.tideTypes), testId: 'tide-panel-tide-types' },
            { label: 'Status', value: status },
          ]}
        />
        {row.locationId != null && (
          <a
            href={`#manage/locations/${row.locationId}`}
            className="mt-3 inline-flex min-h-9 items-center text-xs text-plex-gold underline hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
            data-testid="tide-open-in-locations"
          >
            Open in Locations
          </a>
        )}
      </PanelSection>
    </PanelShell>
  );
}

TideStatsPanel.propTypes = {
  row: PropTypes.object.isRequired,
  median: PropTypes.number,
  onClose: PropTypes.func.isRequired,
  panelRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
};
