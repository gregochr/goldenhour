import PropTypes from 'prop-types';

/**
 * Compact summary strip shown between the Hot Topics and the full briefing grid on the Plan tab.
 *
 * <p>One pill per upcoming solar event (a calendar chip + a verdict roll-up), so the highlights
 * lead and the wall of STANDDOWN cells stays behind the grid expander. Pills with at least one
 * rated region are actionable — clicking opens the map overlay via the same `onShowOnMap` seam the
 * grid uses. "All poor" pills are inert.
 *
 * <p>Purely presentational: the caller computes each pill's roll-up (peak / counts / calendar chip)
 * from the same `getVerdictCounts` / event data the grid uses, so the strip can never disagree with
 * the grid.
 *
 * @param {Object}   props
 * @param {Array}    props.pills       pre-computed pill descriptors
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
        const clickable = pill.ratedCount > 0 && !pill.isTravelDay;
        const accent = pill.peak === 'go'
          ? 'rgba(138,174,114,0.4)'
          : pill.peak === 'maybe'
            ? 'rgba(224,165,66,0.34)'
            : 'var(--color-plex-border)';
        const peakColour = pill.peak === 'go'
          ? 'var(--color-verdict-go)'
          : pill.peak === 'maybe'
            ? 'var(--color-verdict-marginal)'
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
            key={`${pill.date}-${pill.targetType}`}
            data-testid="summary-pill"
            role={clickable ? 'button' : undefined}
            tabIndex={clickable ? 0 : undefined}
            onClick={clickable ? handleClick : undefined}
            onKeyDown={clickable ? handleKeyDown : undefined}
            className={`summary-pill${clickable ? ' summary-pill-clickable' : ''}${pill.isTravelDay ? ' opacity-50' : ''}`}
            style={{
              background: 'var(--color-plex-surface-light)',
              border: `1px solid ${pill.isTravelDay ? 'var(--color-plex-border)' : accent}`,
              borderRadius: '12px',
              padding: '11px 12px',
              cursor: clickable ? 'pointer' : 'default',
            }}
          >
            <div className="flex items-center" style={{ gap: '10px' }}>
              {/* Calendar chip — weekday over day-number, mirrors the date control */}
              <div
                className="flex-shrink-0 text-center"
                style={{
                  width: '42px',
                  background: 'rgba(0,0,0,0.26)',
                  border: `1px solid ${pill.isTravelDay ? 'var(--color-plex-border)' : accent}`,
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
                <div className="font-mono text-plex-text-muted" style={{ fontSize: '11px', marginTop: '3px' }}>
                  {pill.targetType === 'SUNRISE' ? '↑' : '↓'} {pill.targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset'}
                  {pill.eventTime ? ` · ${pill.eventTime}` : ''}
                </div>
              </div>
            </div>

            <div
              data-testid="summary-pill-peak"
              style={{
                marginTop: '10px',
                fontSize: '12px',
                fontWeight: pill.peak === 'poor' ? 400 : 600,
                fontStyle: pill.peak === 'poor' ? 'italic' : 'normal',
                color: peakColour,
              }}
            >
              {pill.peakLabel}
            </div>
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
      targetType: PropTypes.string.isRequired,
      dow: PropTypes.string.isRequired,
      dayNum: PropTypes.string.isRequired,
      dayLabel: PropTypes.string.isRequired,
      eventTime: PropTypes.string,
      peak: PropTypes.oneOf(['go', 'maybe', 'poor']).isRequired,
      peakLabel: PropTypes.string.isRequired,
      countLabel: PropTypes.string.isRequired,
      ratedCount: PropTypes.number.isRequired,
      isTravelDay: PropTypes.bool,
    }),
  ).isRequired,
  onPillClick: PropTypes.func,
};
