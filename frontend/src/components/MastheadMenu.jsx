import React, { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * The masthead's ☰ menu, one mount at every width (`docs/engineering/one-screen-plan.md` D9, D14): Settings, Change
 * home postcode, Guide, Operations and Sign out, plus, on the phone and for an admin, the status line that opens the
 * health panel. Desktop's old inline ⚙, `?` and Sign out controls fold into it.
 *
 * <p><b>Operations is an admin's row and no one else's.</b> It is drawn only when {@code onOperations} is passed, and
 * {@code App} hands that to an admin alone: Pro and Lite get no row, no pill and no greyed stand-in. The backend's 403
 * on {@code /api/admin/**} stays the real enforcement.
 *
 * <h2>A popover, not a dialog</h2>
 *
 * <p>The rows are plain buttons in a labelled group: {@code role="menu"} would promise arrow-key
 * behaviour this does not have. It is CONTROLLED ({@code open} / {@code onOpenChange}) because its
 * state lives in the shell, where {@code closeShellLayers} closes it — every route out of the masthead
 * takes it down, and it is never open beside a dialog it opened.
 *
 * <h2>⚠️ It must not leak into the Map</h2>
 *
 * <p>The popover floats over the map, whose own Escape and outside-press rules know nothing of it.
 * So Escape is handled on this component's container with {@code stopPropagation} (the press
 * dismisses the menu and nothing else), and an outside press lands on a transparent full-viewport
 * CATCHER under the popover that closes it and swallows the press, so the tap that dismisses the
 * menu never also drops a pin or closes a map popover. It also closes when focus Tabs out of the
 * wrapper (a non-null {@code relatedTarget} outside it; a null one is a tap on dead space, which
 * Safari reports for every button press, and must not close the menu before the click lands).
 *
 * <h2>Focus</h2>
 *
 * <p>Closing returns focus to the ☰ button, which the shell also uses as its focus fallback and as where Operations
 * returns focus. A row
 * focuses that button BEFORE calling its handler (R5), synchronously, so the dialog the handler
 * opens records the button as its opener rather than a row that is about to unmount.
 *
 * @param {object} props
 * @param {boolean} props.open whether the popover is shown
 * @param {Function} props.onOpenChange called with the next open state
 * @param {{current: ?HTMLElement}} props.buttonRef the ☰ button's ref, shared with the shell
 * @param {React.ReactNode} [props.statusLine] the admin-only status line, or null
 * @param {Function} props.onSettings the Settings row's handler
 * @param {Function} props.onChangePostcode the Change home postcode row's handler
 * @param {Function} props.onSignOut the Sign out row's handler
 * @param {Function} [props.onWarmSettings] warms the settings chunk on hover and focus of its row
 * @param {Function} [props.onGoHome] the phone's away origin undo ("Plan from home again"); no row when omitted
 * @param {?string} [props.awayFrom] the away origin's base town, named on that row
 * @param {Function} [props.onGuide] the Guide row's handler (G2); no row when omitted
 * @param {boolean} [props.guideOpen] whether the guide is showing: the row then reads "Close guide" and says expanded
 * @param {string} [props.guideControls] the docked guide column's id, named by `aria-controls` while it is open
 * @param {Function} [props.onOperations] the Operations row's handler; no row when omitted (everyone but an admin)
 */
export default function MastheadMenu({
  open, onOpenChange, buttonRef, statusLine = null, onSettings, onChangePostcode, onSignOut,
  onWarmSettings, onGuide = undefined, onGoHome = undefined, awayFrom = null, onOperations = undefined, guideOpen = false, guideControls = undefined,
}) {
  const wrapRef = useRef(null);

  // Opening puts focus on the ⋯ button whatever the device did with the press: Safari does not focus
  // a button on tap, and the Escape rule below needs focus inside the wrapper to hear the key.
  useEffect(() => {
    if (open) buttonRef.current?.focus({ preventScroll: true });
  }, [open, buttonRef]);

  const close = () => {
    buttonRef.current?.focus({ preventScroll: true });
    onOpenChange(false);
  };

  const press = (handler) => {
    buttonRef.current?.focus({ preventScroll: true });
    onOpenChange(false);
    handler();
  };

  const onKeyDown = (e) => {
    if (e.key !== 'Escape' || !open) return;
    e.preventDefault();
    e.stopPropagation();
    close();
  };

  const onBlur = (e) => {
    const next = e.relatedTarget;
    if (open && next && !wrapRef.current?.contains(next)) onOpenChange(false);
  };

  return (
    // The wrapper's handlers are the menu's whole keyboard and focus contract; it is not itself a
    // control, so the lint rule about handlers on non-interactive elements does not apply.
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div ref={wrapRef} className="wf-mast-menu" onKeyDown={onKeyDown} onBlur={onBlur}>
      <button
        ref={buttonRef}
        type="button"
        className="wf-mast-menu-btn"
        data-testid="masthead-menu-button"
        data-open={open ? 'true' : undefined}
        aria-label="Menu"
        aria-expanded={open}
        aria-controls="wf-mast-menu-pop"
        onClick={() => onOpenChange(!open)}
      >
        <span aria-hidden="true">☰</span>
      </button>
      {open && (
        <>
          <div
            className="wf-mast-menu-catcher"
            data-testid="masthead-menu-catcher"
            aria-hidden="true"
            onClick={(e) => { e.preventDefault(); e.stopPropagation(); close(); }}
          />
          <div
            id="wf-mast-menu-pop"
            role="group"
            aria-label="Menu"
            className="wf-mast-menu-pop"
            data-testid="masthead-menu"
          >
            {statusLine && (
              // The line is the app's node; the wrapper closes the menu around it and hands focus
              // to ⋯, as a row does, so the panel it opens never stands over a vanished control.
              // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
              <div className="wf-mast-menu-status-wrap" onClick={close}>
                {statusLine}
              </div>
            )}
            <button
              type="button"
              className="wf-mast-menu-row"
              data-testid="window-first-settings"
              onClick={() => press(onSettings)}
              onPointerEnter={onWarmSettings}
              onFocus={onWarmSettings}
            >
              Settings
            </button>
            <button
              type="button"
              className="wf-mast-menu-row"
              data-testid="masthead-menu-postcode"
              onClick={() => press(onChangePostcode)}
            >
              Change home postcode
            </button>
            {onGoHome && (
              // The phone's masthead has no origin chip (screen-spec.md P4), so an away origin is undone from here. The lens
              // caption ("From Keswick") says where the plan is measured from. The press focuses the ☰ button first like
              // every row, and that button outlives the origin it removes, so focus never falls to <body>.
              <button
                type="button"
                className="wf-mast-menu-row"
                data-testid="masthead-menu-home"
                onClick={() => press(onGoHome)}
              >
                Plan from home again
                {awayFrom && <span className="wf-mast-menu-sub">{` · from ${awayFrom}`}</span>}
              </button>
            )}
            {onGuide && (
              // The guide's door (G2). Absent when the shell passes none (Operations has no guide page).
              <button
                type="button"
                className="wf-mast-menu-row"
                data-testid="masthead-menu-guide"
                // The row is a toggle, so it says which way it goes and whether the guide is up; `aria-controls` names the
                // docked column while that is what is open (a dialog or sheet is not in this tree).
                aria-expanded={guideOpen}
                aria-controls={guideOpen ? guideControls : undefined}
                onClick={() => press(onGuide)}
              >
                {guideOpen ? 'Close guide' : 'Guide'}
              </button>
            )}
            {onOperations && (
              // An admin's only (D9): the full-screen Operations view, opened through the `#manage` address.
              <button
                type="button"
                className="wf-mast-menu-row"
                data-testid="masthead-menu-operations"
                onClick={() => press(onOperations)}
              >
                Operations
              </button>
            )}
            <button
              type="button"
              className="wf-mast-menu-row"
              data-testid="window-first-signout"
              onClick={() => press(onSignOut)}
            >
              Sign out
            </button>
          </div>
        </>
      )}
    </div>
  );
}

MastheadMenu.propTypes = {
  open: PropTypes.bool.isRequired,
  onOpenChange: PropTypes.func.isRequired,
  buttonRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  statusLine: PropTypes.node,
  onSettings: PropTypes.func.isRequired,
  onChangePostcode: PropTypes.func.isRequired,
  onSignOut: PropTypes.func.isRequired,
  onWarmSettings: PropTypes.func,
  onGuide: PropTypes.func,
  onGoHome: PropTypes.func,
  awayFrom: PropTypes.string,
  onOperations: PropTypes.func,
  guideOpen: PropTypes.bool,
  guideControls: PropTypes.string,
};
