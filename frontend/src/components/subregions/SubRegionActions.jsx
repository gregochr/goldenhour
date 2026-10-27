import PropTypes from 'prop-types';
import { useSubRegionStatus } from '../../context/SubRegionStatusContext.jsx';
import { subRegionAction } from '../../utils/subRegionAction.js';

/**
 * The S4 status line the Map prints above its change button (sub-regions-plan §11 S5a-3, decision 9): S4's own
 * words for the status, from the shared status read. Draws nothing until that read lands, for a reader who does
 * not pick, or when the status has no line.
 *
 * @param {object} props
 * @param {string} [props.className]
 * @param {string} [props.testId]
 */
export function SubRegionStatusLine({ className = 'wf-subregion-line', testId = 'wf-subregion-status-line' }) {
  const { state } = useSubRegionStatus();
  const action = subRegionAction(state);
  if (!action?.line) return null;
  return <p className={className} data-testid={testId}>{action.line}</p>;
}

SubRegionStatusLine.propTypes = {
  className: PropTypes.string,
  testId: PropTypes.string,
};

/**
 * The S4 button for the reader's status: Choose, Change or Add, or nothing at all while locked at the limit (the
 * line says when). It opens Settings on the picker, through {@code onOpen} or, absent that, the one the app gave
 * the status provider. With no way to open Settings it draws nothing: a dead control is worse than none.
 *
 * @param {object} props
 * @param {string} [props.className]
 * @param {string} [props.testId]
 * @param {?Function} [props.onOpen] overrides the provider's opener (the location sheet closes itself first)
 */
export function SubRegionActionButton({
  className = 'wf-subregion-btn', testId = 'wf-subregion-action', onOpen = null,
}) {
  const { state, openSubRegions } = useSubRegionStatus();
  const action = subRegionAction(state);
  const open = onOpen ?? openSubRegions;
  if (!action?.label || typeof open !== 'function') return null;
  return (
    <button type="button" className={className} data-testid={testId} onClick={() => open()}>
      {action.label}
    </button>
  );
}

SubRegionActionButton.propTypes = {
  className: PropTypes.string,
  testId: PropTypes.string,
  onOpen: PropTypes.func,
};
