import PropTypes from 'prop-types';
import { textButtonClass } from '../users/userFormClasses.js';

/**
 * The foot of a schedule editor: a failed save's sentence (the editor stays open), Save and Cancel. Phone buttons are
 * 44px tall.
 *
 * @param {object} props
 * @param {string} props.jobKey the job being edited, for the test ids
 * @param {string|null} props.error the failed save's sentence, if any
 * @param {boolean} props.saveDisabled whether Save is dead
 * @param {() => void} props.onSave called when Save is pressed
 * @param {() => void} props.onCancel called when Cancel is pressed
 */
export default function EditorFooter({ jobKey, error = null, saveDisabled = false, onSave, onCancel }) {
  return (
    <>
      {error && (
        <p className="text-xs text-plex-coral" role="alert" data-testid={`inline-edit-error-${jobKey}`}>
          {error}
        </p>
      )}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onSave}
          disabled={saveDisabled}
          className="btn-primary text-xs disabled:opacity-50 max-[639px]:min-h-11"
          data-testid={`save-btn-${jobKey}`}
        >
          Save
        </button>
        <button type="button" onClick={onCancel} className={textButtonClass} data-testid={`cancel-btn-${jobKey}`}>
          Cancel
        </button>
      </div>
    </>
  );
}

EditorFooter.propTypes = {
  jobKey: PropTypes.string.isRequired,
  error: PropTypes.string,
  saveDisabled: PropTypes.bool,
  onSave: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
