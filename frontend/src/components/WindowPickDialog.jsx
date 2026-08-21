import React from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';

/**
 * The prose behind a window's pick badge, and the two places it can send you.
 *
 * <h2>A dialog, not the popover host</h2>
 *
 * <p>{@code PopoverHost} was the obvious home and is the wrong one, by its own rule: it is a
 * {@code role="tooltip"}, and "content that a reader <em>must</em> reach — a destination, an action
 * — belongs on the trigger itself or in a dialog, not here". This panel carries two destinations.
 * §6 makes the same point from the other side: no control whose only visible effect is an
 * {@code aria-hidden} panel. So the pick opens a real dialog — announced as one, dismissible three
 * ways (Escape, the close control, the backdrop), and reachable by keyboard and touch alike, which
 * a hover peek is not.
 *
 * <p><b>Focus is moved in and restored, but not trapped</b> — {@code Modal} now does both for every
 * dialog in the app, and {@link useDialogFocus} records why containment was left out. Escape is
 * opt-in per caller and this one opts in: the panel is read-only prose plus two navigations, so
 * dismissing it loses nothing.
 *
 * <p>That is a deviation from the mock's {@code .pk} hover peek, and a deliberate one. The mock's
 * peek belongs to a <em>spot card</em>, which is itself a link — the peek is a shortcut past a
 * route that already exists. A pick badge is not a card you can click through, so its panel is the
 * only route to the pick's prose and to both of its destinations. P10′ keeps the hover peek for
 * spots, where the licence holds.
 *
 * <h2>What it shows, and what it may not</h2>
 *
 * <p>Everything comes from one object, {@code BriefingWindow.Pick}. {@code headline} is never null
 * — a pick exists only when the headline is usable — while {@code detail} is independently
 * nullable and is omitted rather than replaced. Two things are deliberately absent:
 *
 * <ul>
 *   <li><b>No drive time or distance.</b> The design's location line reads
 *       {@code Bothal · 45 min · 23 mi}; the last two are per-user reach data, which must never
 *       ride the ETag-revalidated briefing payload and arrives at P8 through its own endpoint. The
 *       name alone is honest now.</li>
 *   <li><b>No rating.</b> {@code Pick.averageRating} is the region's mean over its voting slots;
 *       the card header's star is {@code bestRating}, a max over the non-canopy ones. Printing both
 *       puts two different statistics one row apart under the same glyph — a mean and a max, which
 *       differ for any region that is not uniform. The average is published so the selection is
 *       auditable from the payload, not to be shown as a headline figure.</li>
 * </ul>
 *
 * @param {object}   props
 * @param {object}   props.pick        the window's pick — kind, region, headline, detail, location
 * @param {string}   props.when        the window's own title, e.g. "Tomorrow sunrise"
 * @param {string}   [props.time]      the window's clock time
 * @param {Function} props.onClose     dismisses the dialog
 * @param {Function} [props.onShowRegion]   opens the map on the pick's region
 * @param {Function} [props.onShowLocation] opens the map on the pick's location
 */
export default function WindowPickDialog({
  pick, when, time, onClose, onShowRegion, onShowLocation, escapeEnabled = true,
}) {
  const isBest = pick.kind === 'best';
  const accent = isBest ? 'var(--color-badge-go)' : 'var(--color-badge-also)';

  return (
    <Modal
      label={`${isBest ? 'Best bet' : 'Also good'} — ${pick.regionName}`}
      onClose={onClose}
      bare
      // Nothing here is lost by dismissing: the panel is read-only prose plus two navigations.
      // ⚠️ Conditional since M2, and that IS the Escape order. `Modal` installs a document-level
      // Escape listener per instance, so two open dialogs both close on one press. Each layer
      // declines the key while something sits over it, which makes a press take exactly one layer
      // (plan-matrix §6 M2.5). Defaults to true, so a caller that does not stack is unchanged.
      closeOnEscape={escapeEnabled}
      data-testid="window-pick-dialog"
    >
      {/* `bare` rather than the default panel plus overrides: the panel ships `p-6 gap-4`, and this
          dialog is a header/body/footer stack whose sections own their own padding — which is the
          case the prop's own docs name. Overriding with `!important` utilities would have fought it
          rather than opted out. */}
      <div
        className="relative w-full flex flex-col overflow-hidden"
        style={{
          maxWidth: '480px',
          // At 400% zoom the viewport is ~320×256 CSS px and this panel is taller than that, so
          // without a cap the footer's actions land off-screen with nothing to scroll. The prose
          // scrolls; the header and the two destinations stay pinned.
          maxHeight: 'calc(100dvh - 32px)',
          background: 'var(--color-plex-surface)',
          border: '1px solid var(--color-plex-border-light)',
          borderRadius: '13px',
          boxShadow: '0 30px 80px rgba(0,0,0,0.7)',
        }}
      >
      <div
        className="flex items-baseline gap-2 flex-wrap"
        style={{ padding: '12px 16px 10px', borderBottom: '1px solid var(--color-plex-border)' }}
      >
        <span
          data-testid="window-pick-dialog-kind"
          className="font-mono uppercase"
          style={{ fontSize: '10px', fontWeight: 600, letterSpacing: '0.11em', color: accent }}
        >
          <span aria-hidden="true">◎ </span>
          {isBest ? 'Best bet' : 'Also good'}
        </span>
        <span className="font-semibold text-plex-text" style={{ fontSize: '13.5px' }}>
          {pick.regionName}
        </span>
        <span
          className="font-mono text-plex-text-muted ml-auto"
          style={{ fontSize: '10px' }}
        >
          {[when, time].filter(Boolean).join(' · ')}
        </span>
        {/* Every other dialog in this app ships one, and this one's two remaining controls both
            navigate away — so without it the only pointer exit is the backdrop. */}
        <button
          type="button"
          data-testid="window-pick-dialog-close"
          onClick={onClose}
          aria-label="Close"
          className="window-pick-close font-mono text-plex-text-muted"
          style={{ fontSize: '13px', lineHeight: 1, padding: '2px 4px' }}
        >
          ✕
        </button>
      </div>

      <div style={{ padding: '12px 16px 14px', overflowY: 'auto', minHeight: 0 }}>
        <p
          data-testid="window-pick-dialog-headline"
          className="font-serif text-plex-text"
          style={{ fontSize: '15.5px', fontWeight: 500, lineHeight: 1.32, letterSpacing: '-0.005em' }}
        >
          {pick.headline}
        </p>
        {pick.detail && (
          <p
            data-testid="window-pick-dialog-detail"
            className="text-plex-text-secondary"
            style={{ fontSize: '12px', lineHeight: 1.58, marginTop: '8px' }}
          >
            {pick.detail}
          </p>
        )}
        {pick.locationName && (
          <p
            data-testid="window-pick-dialog-location"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '10.5px', marginTop: '10px' }}
          >
            {pick.locationName}
          </p>
        )}
      </div>

      <div
        className="flex items-center gap-3 flex-wrap"
        style={{
          padding: '9px 16px',
          borderTop: '1px solid var(--color-plex-border)',
          background: 'rgba(0,0,0,0.2)',
        }}
      >
        <button
          type="button"
          data-testid="window-pick-dialog-region"
          onClick={() => onShowRegion?.()}
          className="window-pick-action font-mono"
          style={{ fontSize: '10.5px', fontWeight: 600 }}
        >
          <span aria-hidden="true">◍ </span>
          {`Show ${pick.regionName} on map`}
          <span aria-hidden="true"> →</span>
        </button>
        {/* Omitted, not disabled, when the region has nothing rated: `locationName` is null exactly
            then, and a greyed control for a place that does not exist explains nothing. */}
        {pick.locationName && (
          <button
            type="button"
            data-testid="window-pick-dialog-location-action"
            onClick={() => onShowLocation?.()}
            className="window-pick-action font-mono ml-auto"
            style={{ fontSize: '10.5px', fontWeight: 600 }}
          >
            <span aria-hidden="true">◍ </span>
            {`Open ${pick.locationName} on map`}
            <span aria-hidden="true"> →</span>
          </button>
        )}
      </div>
      </div>
    </Modal>
  );
}

WindowPickDialog.propTypes = {
  /**
   * Whether Escape closes THIS dialog. False while another layer sits over it — the shell owns that
   * ordering, because only it knows what else is open (plan-matrix §6 M2.5).
   */
  escapeEnabled: PropTypes.bool,
  pick: PropTypes.shape({
    kind: PropTypes.oneOf(['best', 'also']).isRequired,
    regionName: PropTypes.string.isRequired,
    headline: PropTypes.string.isRequired,
    detail: PropTypes.string,
    locationName: PropTypes.string,
    locationId: PropTypes.number,
  }).isRequired,
  when: PropTypes.string.isRequired,
  time: PropTypes.string,
  onClose: PropTypes.func.isRequired,
  onShowRegion: PropTypes.func,
  onShowLocation: PropTypes.func,
};
