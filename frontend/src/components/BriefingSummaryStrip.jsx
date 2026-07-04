import PropTypes from 'prop-types';

/**
 * Compact summary strip shown between the Hot Topics and the full briefing grid on the Plan tab.
 *
 * <p>One pill per upcoming day (mirroring the grid's day columns), each carrying both solar events
 * (↑ sunrise / ↓ sunset) and a day-best verdict roll-up, so the highlights lead and the wall of
 * STANDDOWN cells stays behind the grid expander. Pills with at least one rated region are
 * actionable — clicking opens the map overlay via the same `onShowOnMap` seam the grid uses.
 * "All poor" and "Away" (travel-day) pills are inert.
 *
 * <p>Purely presentational: the caller computes each pill's roll-up from the same `getVerdictCounts`
 * the grid uses, so the strip can never disagree with the grid.
 *
 * @param {Object}   props
 * @param {Array}    props.pills       pre-computed day-pill descriptors
 * @param {Function} props.onPillClick callback(date, targetType) for actionable pills
 */
export default function BriefingSummaryStrip({ pills, onPillClick }) {
  if (!pills || pills.length === 0) return null;

  return (
    <div
      data-testid="briefing-summary-strip"
      className="grid mb-1"
      style={{ gridTemplateColumns: `repeat(${pills.length}, minmax(0, 1fr))`, gap: '8px' }}
    >
      {pills.map((pill) => {
        const clickable = pill.ratedCount > 0 && !pill.isAway;
        const accent = pill.peak === 'go'
          ? 'rgba(138,174,114,0.4)'
          : pill.peak === 'maybe'
            ? 'rgba(224,165,66,0.34)'
            : 'var(--color-plex-border)';
        const peakColour = pill.peak === 'go'
          ? 'var(--color-verdict-go)'
          : pill.peak === 'maybe'
            ? 'var(--color-verdict-marginal)'
            : pill.isAway
              ? 'var(--color-tide)'
              : 'var(--color-plex-text-muted)';
        const handleClick = () => { if (clickable) onPillClick?.(pill.date, pill.targetType); };
        const handleKeyDown = (e) => {
          if (clickable && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault();
            onPillClick?.(pill.date, pill.targetType);
          }
        };
        return (
          <div
            key={pill.date}
            data-testid="summary-pill"
            data-away={pill.isAway ? 'true' : undefined}
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onClick={clickable ? handleClick : undefined}
            onKeyDown={clickable ? handleKeyDown : undefined}
            className={`summary-pill${clickable ? ' summary-pill-clickable' : ''}`}
            style={{
              background: 'var(--color-plex-surface-light)',
              border: `1px solid ${pill.isAway ? 'var(--color-plex-border)' : accent}`,
              borderRadius: '12px',
              padding: '11px 12px',
              cursor: clickable ? 'pointer' : 'default',
              opacity: pill.isAway ? 0.62 : 1,
            }}
          >
            <div className="flex items-center" style={{ gap: '10px' }}>
              {/* Calendar chip — weekday over day-number, mirrors the date control */}
              <div
                className="flex-shrink-0 text-center"
                style={{
                  width: '42px',
                  background: 'rgba(0,0,0,0.26)',
                  border: `1px solid ${pill.isAway ? 'var(--color-plex-border)' : accent}`,
                  borderRadius: '9px',
                  padding: '5px 0 6px',
                }}
              >
                <span
                  className="block font-mono uppercase"
                  style={{ fontSize: '9px', letterSpacing: '0.11em', color: 'var(--color-plex-text-muted)' }}
                >
                  {pill.dow}
                </span>
                <span className="block font-bold" style={{ fontSize: '18px', lineHeight: 1, marginTop: '2px' }}>
                  {pill.dayNum}
                </span>
              </div>
              <div style={{ minWidth: 0 }}>
                <div className="font-semibold text-plex-text" style={{ fontSize: '13px' }}>{pill.dayLabel}</div>
                <div
                  data-testid="summary-pill-times"
                  className="font-mono text-plex-text-muted"
                  style={{ fontSize: '11px', marginTop: '3px', fontVariantNumeric: 'tabular-nums' }}
                >
                  {[
                    pill.sunriseTime && `↑ ${pill.sunriseTime}`,
                    pill.sunsetTime && `↓ ${pill.sunsetTime}`,
                  ].filter(Boolean).join(' · ')}
                </div>
              </div>
            </div>

            <div
              data-testid="summary-pill-peak"
              style={{
                marginTop: '10px',
                fontSize: '12px',
                fontWeight: pill.peak === 'poor' || pill.isAway ? 400 : 600,
                fontStyle: pill.peak === 'poor' ? 'italic' : 'normal',
                color: peakColour,
              }}
            >
              {pill.peakLabel}
            </div>
            {pill.subLabel && (
              <div className="font-mono" style={{ fontSize: '10px', color: 'var(--color-tide)', marginTop: '4px' }}>
                {pill.subLabel}
              </div>
            )}
            <div className="font-mono" style={{ fontSize: '10px', color: 'var(--color-plex-text-muted)', marginTop: '4px' }}>
              {pill.countLabel}
            </div>
            {clickable && (
              <div className="font-mono" style={{ fontSize: '10px', color: 'var(--color-tide)', marginTop: '8px' }}>
                ◍ Show on map →
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

BriefingSummaryStrip.propTypes = {
  pills: PropTypes.arrayOf(
    PropTypes.shape({
      date: PropTypes.string.isRequired,
      targetType: PropTypes.string,
      dow: PropTypes.string.isRequired,
      dayNum: PropTypes.string.isRequired,
      dayLabel: PropTypes.string.isRequired,
      sunriseTime: PropTypes.string,
      sunsetTime: PropTypes.string,
      peak: PropTypes.oneOf(['go', 'maybe', 'poor', 'away']).isRequired,
      peakLabel: PropTypes.string.isRequired,
      subLabel: PropTypes.string,
      countLabel: PropTypes.string.isRequired,
      ratedCount: PropTypes.number.isRequired,
      isAway: PropTypes.bool,
    }),
  ).isRequired,
  onPillClick: PropTypes.func,
};
