import { useEffect } from 'react';
import PropTypes from 'prop-types';

/**
 * Modal that opens the map *over* the Plan tab (instead of a full tab switch), focused on whatever
 * recommendation was tapped. Frames a `MapView` node with a header (what you're looking at), the
 * preserved Claude narrative as a footer band, and a quiet escape hatch to the full Map tab.
 *
 * <p>Closeable with ✕, Esc, or a backdrop click — the caller's `viewMode` is untouched, so the user
 * stays exactly where they were on the Plan tab.
 *
 * @param {Object}      props
 * @param {string}      props.title           header title (region / topic / event name)
 * @param {string}      [props.subLine]       mono sub-line (e.g. "Today sunset · 21:49")
 * @param {React.Node}  props.children        the map (a `MapView` instance)
 * @param {string}      [props.caption]       bottom-center caption for a multi-region fit-to-pins view
 * @param {string}      [props.narrative]     the region's Claude gloss, preserved from the briefing
 * @param {string}      [props.narrativeHead] verdict-coloured narrative head (e.g. "◎ Worth it sunset · Region")
 * @param {string}      [props.narrativeTone] 'go' | 'marginal' | 'standdown' — colours the head
 * @param {Function}    props.onClose         close the overlay (stay on Plan)
 * @param {Function}    props.onOpenFullMap   switch to the full Map tab, focused where the overlay was
 */
export default function MapOverlay({
  title,
  subLine = null,
  children,
  caption = null,
  narrative = null,
  narrativeHead = null,
  narrativeTone = 'standdown',
  onClose,
  onOpenFullMap,
}) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const headColour = narrativeTone === 'go'
    ? 'var(--color-verdict-go)'
    : narrativeTone === 'marginal'
      ? 'var(--color-verdict-marginal)'
      : 'var(--color-plex-text-muted)';

  return (
    // Backdrop close is a convenience; Esc (the window keydown above) is the accessible path.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      data-testid="map-overlay-scrim"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 200,
        background: 'rgba(8,6,5,0.72)',
        backdropFilter: 'blur(3px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '24px',
      }}
    >
      <div
        data-testid="map-overlay-panel"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="map-overlay-panel"
        style={{
          width: 'min(920px, 94vw)',
          maxHeight: '88vh',
          background: 'var(--color-plex-surface)',
          border: '1px solid var(--color-plex-border-light)',
          borderRadius: '12px',
          boxShadow: '0 30px 80px rgba(0,0,0,0.7)',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {/* Header */}
        <div
          className="flex items-center gap-3"
          style={{ padding: '14px 18px', borderBottom: '1px solid var(--color-plex-border)', flexShrink: 0 }}
        >
          <span className="font-bold text-plex-text" style={{ fontSize: '15px' }}>{title}</span>
          {subLine && (
            <span className="font-mono text-plex-text-muted" style={{ fontSize: '11px' }}>· {subLine}</span>
          )}
          <button
            type="button"
            data-testid="map-overlay-close"
            aria-label="Close map"
            onClick={onClose}
            className="ml-auto flex items-center justify-center text-plex-text-secondary hover:text-plex-text border border-plex-border rounded transition-colors"
            style={{ width: '30px', height: '30px', fontSize: '15px' }}
          >
            ✕
          </button>
        </div>

        {/* Map body — takes the remaining space and clips the embedded map so the header,
            narrative and foot always stay visible within the panel. */}
        <div style={{ position: 'relative', flex: '1 1 auto', minHeight: '260px', overflow: 'hidden' }}>
          {children}
          {caption && (
            <div
              data-testid="map-overlay-caption"
              className="font-mono"
              style={{
                position: 'absolute',
                left: '50%',
                bottom: '16px',
                transform: 'translateX(-50%)',
                zIndex: 500,
                fontSize: '11px',
                color: 'var(--color-plex-text-secondary)',
                background: 'rgba(20,15,12,0.88)',
                border: '1px solid var(--color-plex-border)',
                borderRadius: '999px',
                padding: '6px 14px',
                whiteSpace: 'nowrap',
                pointerEvents: 'none',
              }}
            >
              {caption}
            </div>
          )}
        </div>

        {/* Narrative footer band — the preserved Claude gloss */}
        {narrative && (
          <div
            data-testid="map-overlay-narrative"
            style={{ padding: '13px 18px', borderTop: '1px solid var(--color-plex-border)', background: 'rgba(0,0,0,0.16)', flexShrink: 0 }}
          >
            {narrativeHead && (
              <div
                className="font-mono uppercase"
                style={{ fontSize: '11px', fontWeight: 600, letterSpacing: '0.04em', marginBottom: '5px', color: headColour }}
              >
                {narrativeHead}
              </div>
            )}
            <p style={{ fontFamily: 'var(--font-serif)', fontStyle: 'italic', fontSize: '13px', lineHeight: 1.55, color: 'var(--color-plex-text-secondary)' }}>
              {narrative}
            </p>
          </div>
        )}

        {/* Foot row */}
        <div
          className="flex items-center gap-3"
          style={{ padding: '11px 18px', borderTop: '1px solid var(--color-plex-border)', flexShrink: 0 }}
        >
          <span className="font-mono text-plex-text-muted" style={{ fontSize: '11px' }}>
            Esc to close · you stay on the Plan tab
          </span>
          <button
            type="button"
            data-testid="map-overlay-open-full"
            onClick={onOpenFullMap}
            className="ml-auto font-semibold"
            style={{ fontSize: '12px', color: 'var(--color-tide)' }}
          >
            Open the full Map tab →
          </button>
        </div>
      </div>
    </div>
  );
}

MapOverlay.propTypes = {
  title: PropTypes.string.isRequired,
  subLine: PropTypes.string,
  children: PropTypes.node,
  caption: PropTypes.string,
  narrative: PropTypes.string,
  narrativeHead: PropTypes.string,
  narrativeTone: PropTypes.oneOf(['go', 'marginal', 'standdown']),
  onClose: PropTypes.func.isRequired,
  onOpenFullMap: PropTypes.func.isRequired,
};
