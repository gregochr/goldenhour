import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import useBodyScrollLock from '../hooks/useBodyScrollLock.js';
import { MapScreenContext } from '../context/MapScreenContext.jsx';
import ScreenNote from './ScreenNote.jsx';
import { foreignModalOver, MAP_PANE_SELECTOR } from '../utils/mapForeignModal.js';
import { escapeAnswered } from '../utils/mapEscape.js';

/** What the header says before the pane has published a window. */
const DEFAULT_TITLE = 'Map';

/**
 * The map view (`docs/engineering/one-screen-plan.md` D1, D10, D11, D12): the one map, a labelled region over the
 * screen the reader came from. The shell owns it and renders it INSIDE its own root, never portalled, so the checks
 * that read "is a dialog outside the shell open" (Ask, `/`, ⌘K) keep working.
 *
 * <h2>A region, deliberately not a dialog</h2>
 *
 * <p>{@code role="region"}, labelled by its header title, and NOT {@code role="dialog" aria-modal}: an
 * {@code aria-modal} view would wrap the pane, so {@code foreignModalOver} would count it as a foreign modal and
 * freeze the pane's Escape and outside-press rules; {@code foreignDialogOpen} would kill Ask and Find; and the desktop
 * Ask dock, the docked guide and the phone sheets portalled to {@code <body>} would all sit outside an
 * {@code aria-modal} and be hidden from assistive technology. The shell makes the screen and masthead behind it
 * {@code inert} instead, which gives the same isolation. A test pins that no {@code [aria-modal]} sits on or above
 * {@code .wf-map-tab}.
 *
 * <h2>Mounted once, hidden when closed</h2>
 *
 * <p>The shell mounts this on the first open, visible from its first commit, and keeps it mounted behind the
 * {@code hidden} ATTRIBUTE (never a class, visibility, opacity or an off-screen position) when closed: that is what
 * `MapView`'s hidden-pane guards read, and a 0x0 box is what the pane's ResizeObserver reports. There is never a
 * transform on this element or an ancestor (a transform is invisible to the observer and counted by
 * {@code getBoundingClientRect}); the enter animation is opacity only.
 *
 * <h2>The header</h2>
 *
 * <p>The title, verdict, best star and confidence are the SERVED fields of the window on screen, published by the
 * pane ({@code publishHeader}); the existing `WindowControl` is portalled into {@code windowSlot} (there is still one
 * control); Find, the origin chip and (tablet and desktop) the Ask field come from the shell as nodes; ✕ closes.
 *
 * <h2>Escape</h2>
 *
 * <p>Inner layers (the callout, a menu, a panel) close before the view does. The pane publishes its Escape ladder
 * ({@code publishEscape}, which answers whether it closed a layer); a press the pane already answered is left alone, a
 * press from the header runs the ladder itself, and with nothing inside the view closes. A foreign modal over the pane
 * (a sheet, Find, Ask) is answered by that dialog and stands this down.
 *
 * @param {object} props
 * @param {boolean} props.open whether the view is showing
 * @param {function(): void} props.onClose ✕, Escape, and the scrim when nothing is stacked
 * @param {function(): void} props.onScrimPress a press on the scrim (the shell closes only when nothing is stacked)
 * @param {function(object): void} props.onRoute the pane's current `{date, eventType, locationId}`
 * @param {{current: ?Element}} props.titleRef the title node: initial focus, and the shell's focus fallback
 * @param {number} [props.right] px from the viewport's right edge where the view ends (the Ask dock column)
 * @param {?string} [props.note] a one-line dismissible note under the header
 * @param {function(): void} [props.onDismissNote]
 * @param {React.ReactNode} [props.find] the ⌕ Find control
 * @param {React.ReactNode} [props.origin] the origin chip
 * @param {React.ReactNode} [props.ask] the Ask field (tablet and desktop)
 * @param {React.ReactNode} props.children the pane
 */
export default function MapScreen({
  open, onClose, onScrimPress, onRoute, titleRef, right = 0, note = null, onDismissNote = undefined,
  find = null, origin = null, ask = null, children,
}) {
  const titleId = useId();
  const rootRef = useRef(null);
  const [windowSlot, setWindowSlot] = useState(null);
  const [info, setInfo] = useState(null);
  // The pane's Escape ladder, published once; read at keydown time, so a ref and no state.
  const escapeRef = useRef(null);
  useBodyScrollLock(open);

  // Initial focus is the view's title, never the Leaflet container. Runs on the commit the view opens (and on a mount
  // that is already open), so a reader's focus is never left in the screen that has just gone inert.
  const wasOpen = useRef(false);
  useLayoutEffect(() => {
    if (open && !wasOpen.current) titleRef.current?.focus({ preventScroll: true });
    wasOpen.current = open;
  }, [open, titleRef]);

  const publishEscape = useCallback((close) => { escapeRef.current = close; }, []);
  // The pane's window stepper (`step(delta)`), for the arrow keys pressed on the header.
  const scrimDown = useRef(false);
  const stepRef = useRef(null);
  const publishStep = useCallback((step) => { stepRef.current = step; }, []);
  /** Moves focus to the title, for a control that unmounts under focus (the lens note's Clear). */
  const focusTitle = useCallback(() => { titleRef.current?.focus({ preventScroll: true }); }, [titleRef]);
  const value = useMemo(
    () => ({
      windowSlot,
      publishHeader: setInfo,
      publishRoute: onRoute,
      publishEscape,
      publishStep,
      focusTitle,
      open,
    }),
    [windowSlot, onRoute, publishEscape, publishStep, focusTitle, open],
  );

  // Escape with focus on <body> (the reader clicked the dimmed screen, or a focused control unmounted): no element
  // inside the view hears it, so the document does. Stands down for a dialog over the pane and for a press an inner
  // layer or the view's own handler already answered.
  const closeRef = useRef(onClose);
  useEffect(() => { closeRef.current = onClose; });
  useEffect(() => {
    if (!open) return undefined;
    const onDocumentKey = (event) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      if (event.target !== document.body && event.target !== document.documentElement) return;
      const pane = rootRef.current?.querySelector(MAP_PANE_SELECTOR) ?? null;
      if (foreignModalOver(pane) || escapeAnswered(event)) return;
      if (escapeRef.current?.()) return;
      event.preventDefault();
      closeRef.current();
    };
    document.addEventListener('keydown', onDocumentKey);
    return () => document.removeEventListener('keydown', onDocumentKey);
  }, [open]);

  /** Where the arrow keys do NOT step the window: a field, a list, a menu, the map itself, the window control. */
  const NO_STEP = 'input, textarea, select, [contenteditable], [role="listbox"], [role="menu"], '
    + '[role="combobox"], .leaflet-container, [data-testid="wf-win-control"]';
  function handleHeaderKeyDown(event) {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    if (event.target.closest?.(NO_STEP)) return;
    if (!stepRef.current) return;
    event.preventDefault();
    stepRef.current(event.key === 'ArrowLeft' ? -1 : 1);
  }

  function handleKeyDown(event) {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    const pane = rootRef.current?.querySelector(MAP_PANE_SELECTOR) ?? null;
    // A dialog over the pane (a sheet, Find, Ask, settings) owns this press.
    if (foreignModalOver(pane)) return;
    // Pressed on the pane, its own handler has already closed an inner layer: that was this press's one layer.
    if (escapeAnswered(event.nativeEvent)) return;
    // Pressed on the header (where the pane's handler never hears the key), answer it the same way: the innermost
    // layer first. With nothing inside, the view itself closes.
    if (escapeRef.current?.()) return;
    event.preventDefault();
    onClose();
  }

  const title = info?.title || DEFAULT_TITLE;
  const confidence = typeof info?.confidence === 'string' && info.confidence ? info.confidence : null;
  return (
    <MapScreenContext.Provider value={value}>
      {/* The region is not itself interactive: the key handler only hears Escape from the controls inside it. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={rootRef}
        role="region"
        aria-labelledby={titleId}
        hidden={!open}
        data-map-screen=""
        data-testid="map-screen"
        className="wf-map-screen"
        style={right > 0 ? { '--wf-map-right': `${right}px` } : undefined}
        onKeyDown={handleKeyDown}
        // Capture runs before the scrim's own pointerdown, so a press anywhere else forgets an earlier scrim press.
        onPointerDownCapture={() => { scrimDown.current = false; }}
      >
        {/* A pointer-only dismissal surface: the keyboard's route is Escape and the close button; it is aria-hidden. */}
        <div
          aria-hidden="true"
          data-testid="map-screen-scrim"
          className="wf-map-screen-scrim"
          onPointerDown={() => { scrimDown.current = true; }}
          onClick={() => {
            // A click that began on the scrim itself: a touch that landed elsewhere and ended here is not a dismissal,
            // and closing on pointerdown would hand the same tap to whatever sits under the closing view.
            if (!scrimDown.current) return;
            scrimDown.current = false;
            onScrimPress();
          }}
        />
        <div className="wf-map-screen-box">
          {/* A div, not a `<header>`: inside a landmark region a header would still map to a (nested) banner landmark. */}
          {/* The arrows step the window from anywhere on the header that is not a field or a list (D12). */}
          {/* eslint-disable-next-line jsx-a11y/no-static-element-interactions */}
          <div className="wf-map-screen-head" data-testid="map-screen-header" onKeyDown={handleHeaderKeyDown}>
            <div className="wf-map-screen-titleblock">
              <h2
                id={titleId}
                ref={titleRef}
                tabIndex={-1}
                data-testid="map-screen-title"
                className="wf-map-screen-title font-serif"
              >
                {title}
              </h2>
              <p className="wf-map-screen-facts font-mono" data-testid="map-screen-facts">
                {info?.verdictLabel && <span data-testid="map-screen-verdict">{info.verdictLabel}</span>}
                {info?.bestRating != null && (
                  <span data-testid="map-screen-best">
                    <span aria-hidden="true">{`${info.bestRating}★`}</span>
                    <span className="sr-only">{`best ${info.bestRating} stars`}</span>
                  </span>
                )}
                {confidence && <span data-testid="map-screen-confidence">{`${confidence.toLowerCase()} confidence`}</span>}
              </p>
            </div>
            <div ref={setWindowSlot} className="wf-map-screen-window" data-testid="map-screen-window-slot" />
            <div className="wf-map-screen-tools">
              {find}
              {origin}
              {ask}
              <button
                type="button"
                data-testid="map-screen-close"
                className="wf-map-screen-close font-mono"
                aria-label="Close the map"
                onClick={onClose}
              >
                <span aria-hidden="true">✕</span>
              </button>
            </div>
          </div>
          {note && <ScreenNote text={note} onDismiss={onDismissNote} />}
          <div className="wf-map-screen-body">{children}</div>
        </div>
      </div>
    </MapScreenContext.Provider>
  );
}

MapScreen.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  onScrimPress: PropTypes.func.isRequired,
  onRoute: PropTypes.func.isRequired,
  titleRef: PropTypes.shape({ current: PropTypes.any }).isRequired,
  right: PropTypes.number,
  note: PropTypes.string,
  onDismissNote: PropTypes.func,
  find: PropTypes.node,
  origin: PropTypes.node,
  ask: PropTypes.node,
  children: PropTypes.node,
};
