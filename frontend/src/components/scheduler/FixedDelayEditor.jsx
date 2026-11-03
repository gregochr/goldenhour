import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import Segmented from '../data/Segmented.jsx';
import EditorFooter from './EditorFooter.jsx';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import { delayOptions } from '../../utils/schedulerEdit.js';

/**
 * The editor for a fixed-delay job: the presets (1, 5, 10, 30 minutes, 1 hour) as one segmented control. A stored
 * delay that is not a preset is shown as an extra, selected option labelled with its duration, and nothing changes
 * unless the admin picks another: Save stays off until the choice differs from the stored delay.
 *
 * @param {object} props
 * @param {object} props.job the served job
 * @param {(job: object, value: string) => Promise<void>} props.onSave saves the delay in milliseconds; rejects on
 *   failure
 * @param {() => void} props.onCancel closes the editor
 */
export default function FixedDelayEditor({ job, onSave, onCancel }) {
  const stored = String(job.fixedDelayMs);
  const [value, setValue] = useState(stored);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const sending = useRef(false);
  const root = useRef(null);

  // Opening the editor replaces the row the reader pressed Edit on: put focus on the chosen delay (the first preset when none is chosen).
  useEffect(() => {
    const group = root.current;
    (group?.querySelector('[aria-pressed="true"]') ?? group?.querySelector('button'))?.focus();
  }, []);

  const handleSave = async () => {
    if (sending.current || value === stored) return;
    sending.current = true;
    setSaving(true);
    setError(null);
    try {
      await onSave(job, value);
    } catch {
      setError(`Couldn't save ${job.displayName}'s schedule.`);
    } finally {
      sending.current = false;
      setSaving(false);
    }
  };

  return (
    <div
      ref={root}
      className="flex flex-col gap-2"
      {...{ [PANEL_EDIT_FORM_ATTR]: '' }}
      data-testid={`fixed-delay-edit-${job.jobKey}`}
    >
      <span className="text-xs text-plex-text-secondary">Delay between runs</span>
      <Segmented
        label="Delay between runs"
        options={delayOptions(job.fixedDelayMs)}
        value={value}
        onChange={setValue}
        disabled={saving}
        testId={`fixed-delay-${job.jobKey}`}
      />
      <EditorFooter
        jobKey={job.jobKey}
        error={error}
        saveDisabled={value === stored || saving}
        onSave={handleSave}
        onCancel={onCancel}
      />
    </div>
  );
}

FixedDelayEditor.propTypes = {
  job: PropTypes.shape({
    jobKey: PropTypes.string.isRequired,
    displayName: PropTypes.string,
    fixedDelayMs: PropTypes.number,
  }).isRequired,
  onSave: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
