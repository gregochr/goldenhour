import PropTypes from 'prop-types';
import { createPortal } from 'react-dom';

/**
 * The Map label layer's hover tooltip card (performance review F21). Its own component so the
 * position, which changes on every pointer move, lives on the node (a CSS transform written through
 * {@code attach}, see {@code useChipTipPosition}) and never in {@code MapLabels}' state.
 *
 * <p>Portalled to the chrome wrapper, not into the Leaflet pane, for the stacking-context reason
 * documented in {@code MapLabels}: the pane is transformed, so the tooltip's z-index 1400 would be
 * inert inside it.
 *
 * @param {object} props
 * @param {Element} props.container the chrome wrapper to portal into
 * @param {Function} props.attach the position hook's callback ref
 * @param {string} [props.kind] a {@code data-kind} marker (the big-seas card)
 * @param {import('react').ReactNode} props.children the card's lines
 * @returns {import('react').ReactPortal} the tooltip
 */
export default function MapChipTooltip({ container, attach, kind, children }) {
  return createPortal(
    <div
      ref={attach}
      className="wf-maplab-tip"
      data-testid="map-label-tip"
      data-kind={kind}
    >
      {children}
    </div>,
    container,
  );
}

MapChipTooltip.propTypes = {
  container: PropTypes.instanceOf(Element).isRequired,
  attach: PropTypes.func.isRequired,
  kind: PropTypes.string,
  children: PropTypes.node,
};
