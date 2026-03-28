import PropTypes from 'prop-types';

/**
 * Aurora section displayed below the heatmap grid.
 * Tonight is tappable when MINOR/MODERATE/STRONG with clear locations.
 * Tomorrow is informational only.
 * Not affected by the quality slider.
 */
export default function AuroraGridRow({ auroraTonight, auroraTomorrow }) {
  const hasTonight = !!auroraTonight;
  const hasTomorrow = !!auroraTomorrow && auroraTomorrow.label !== 'Quiet';

  if (!hasTonight && !hasTomorrow) return null;

  const TAPPABLE_LEVELS = ['MINOR', 'MODERATE', 'STRONG'];
  const tonightTappable =
    hasTonight &&
    TAPPABLE_LEVELS.includes(auroraTonight.alertLevel) &&
    auroraTonight.clearLocationCount > 0;

  const levelColour = (level) => {
    if (level === 'STRONG') return 'text-red-400';
    if (level === 'MODERATE') return 'text-amber-400';
    return 'text-green-400';
  };

  const levelLabel = { MINOR: 'Minor', MODERATE: 'Moderate', STRONG: 'Strong' };

  return (
    <div className="mt-3 pt-2.5 border-t border-plex-border/30" data-testid="aurora-grid-row">
      {/* Tonight */}
      {hasTonight && (
        <div
          data-testid="aurora-tonight-row"
          role={tonightTappable ? 'button' : undefined}
          tabIndex={tonightTappable ? 0 : undefined}
          className={`flex items-center gap-2 px-2 py-1.5 rounded mb-1
            ${tonightTappable
              ? 'cursor-pointer hover:bg-indigo-500/10 border border-indigo-500/20'
              : 'border border-transparent'
            }`}
          onClick={tonightTappable ? () => {
            // Navigate to map tab to view aurora
            window.location.hash = 'map';
          } : undefined}
        >
          <span style={{ fontSize: '14px' }}>🌌</span>
          <span className="font-medium text-indigo-300" style={{ fontSize: '12px' }}>
            Aurora tonight
          </span>
          <span className={`font-bold ${levelColour(auroraTonight.alertLevel)}`} style={{ fontSize: '12px' }}>
            {levelLabel[auroraTonight.alertLevel] || auroraTonight.alertLevel}
            {auroraTonight.kp != null && ` (Kp ${auroraTonight.kp.toFixed(1)})`}
          </span>
          <span className="text-plex-text-secondary flex-1" style={{ fontSize: '12px' }}>
            · {auroraTonight.clearLocationCount} location{auroraTonight.clearLocationCount !== 1 ? 's' : ''} clear
            {(auroraTonight.regions || []).length > 0 && (
              <> in {auroraTonight.regions[0].regionName}</>
            )}
          </span>
          {tonightTappable && (
            <span className="text-plex-text-muted" style={{ fontSize: '12px' }}>›</span>
          )}
        </div>
      )}

      {/* Tomorrow */}
      {hasTomorrow && (
        <div
          data-testid="aurora-tomorrow-row"
          className="flex items-center gap-2 px-2 py-1 text-indigo-300/70"
          style={{ fontSize: '12px' }}
        >
          <span style={{ fontSize: '14px' }}>🌌</span>
          <span className="font-medium" style={{ fontSize: '12px' }}>Aurora tomorrow</span>
          <span>
            {auroraTomorrow.label} — Kp {auroraTomorrow.peakKp.toFixed(1)} forecast
          </span>
        </div>
      )}
    </div>
  );
}

AuroraGridRow.propTypes = {
  auroraTonight: PropTypes.shape({
    alertLevel: PropTypes.string.isRequired,
    kp: PropTypes.number,
    clearLocationCount: PropTypes.number.isRequired,
    regions: PropTypes.array.isRequired,
  }),
  auroraTomorrow: PropTypes.shape({
    peakKp: PropTypes.number.isRequired,
    label: PropTypes.string.isRequired,
  }),
};
