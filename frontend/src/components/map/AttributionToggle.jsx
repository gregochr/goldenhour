import { useEffect } from 'react';
import PropTypes from 'prop-types';
import { useMap } from 'react-leaflet';

/** The class a tap puts on Leaflet's attribution control to show its full, wrapped text (`index.css`). */
export const ATTRIBUTION_FULL_CLASS = 'wf-attr-full';
/** The same tap's class on the control's CORNER: the control's z-index counts only inside the corner's own. */
export const ATTRIBUTION_OPEN_CLASS = 'wf-attr-open';

/**
 * The phone's one-line attribution, tap to expand (map-mobile-compact-plan.md C2 step 5, S9;
 * `docs/design/map-mobile-compact/README.md` §5). A tap anywhere on the control but a link toggles
 * {@link ATTRIBUTION_FULL_CLASS} on it (and {@link ATTRIBUTION_OPEN_CLASS} on its corner), which the
 * stylesheet turns into the full wrapped text on an opaque ground above the lifted chrome and below the
 * sheet. Links inside still work: the handler returns before toggling and never calls
 * {@code preventDefault}.
 *
 * <p>One native listener on the control's container, added once through
 * {@code map.attributionControl.getContainer()} in an effect keyed on {@code enabled} (the phone) and
 * removed on cleanup, which also removes both classes — so leaving the phone (a resize past the
 * breakpoint) leaves the desktop control exactly as Leaflet drew it. Expansion is a visual aid for pointers:
 * the full text is already in the DOM for assistive tech, so no keyboard control is added (S9).
 *
 * <p>Guarded with optional chaining like {@code ZoomControlPositioner}: the test harnesses' {@code useMap()}
 * stubs have no {@code attributionControl}.
 *
 * @param {object} props
 * @param {boolean} props.enabled the phone layout is on
 */
export default function AttributionToggle({ enabled }) {
  const map = useMap();
  useEffect(() => {
    const container = enabled ? map?.attributionControl?.getContainer?.() : null;
    if (!container) return undefined;
    const corner = container.parentElement;
    const onClick = (event) => {
      if (event.target?.closest?.('a')) return;
      const full = container.classList.toggle(ATTRIBUTION_FULL_CLASS);
      corner?.classList.toggle(ATTRIBUTION_OPEN_CLASS, full);
    };
    container.addEventListener('click', onClick);
    return () => {
      container.removeEventListener('click', onClick);
      container.classList.remove(ATTRIBUTION_FULL_CLASS);
      corner?.classList.remove(ATTRIBUTION_OPEN_CLASS);
    };
  }, [map, enabled]);
  return null;
}

AttributionToggle.propTypes = {
  enabled: PropTypes.bool.isRequired,
};
