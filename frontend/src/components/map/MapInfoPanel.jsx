import React from 'react';
import PropTypes from 'prop-types';

/**
 * The frame of the Map's Best bet reason panel (glance-to-map-plan.md G1): a capped panel hung under the top-left
 * chrome group at the frame level (`.wf-bestbet-panel` in `index.css`) with a Close button that is a finger-sized
 * target. The caller owns what it says and when it is open. (A warning no longer has a panel: it opens in place under
 * its row, map-warning-row-plan.md M2.)
 *
 * @param {object} props
 * @param {string} props.className the panel's placement class
 * @param {string} props.hostTestId the frame's test id
 * @param {string} props.closeTestId the Close button's test id
 * @param {Function} props.onClose closes the panel
 * @param {React.ReactNode} props.children the panel's content
 */
export default function MapInfoPanel({
  className, hostTestId, closeTestId, onClose, children,
}) {
  return (
    <div className={className} data-testid={hostTestId}>
      <button type="button" data-testid={closeTestId} className="wf-bestbet-panel-x font-mono" onClick={onClose}>
        Close
      </button>
      {children}
    </div>
  );
}

MapInfoPanel.propTypes = {
  className: PropTypes.string.isRequired,
  hostTestId: PropTypes.string.isRequired,
  closeTestId: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
  children: PropTypes.node,
};
