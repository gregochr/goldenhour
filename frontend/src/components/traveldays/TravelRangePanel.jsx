import { useRef, useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import TravelRangeForm from './TravelRangeForm.jsx';
import useTravelRangeForm from '../../hooks/useTravelRangeForm.js';
import { deleteTravelDay, updateTravelDay } from '../../api/travelDayApi.js';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import { textButtonClass } from '../users/userFormClasses.js';
import {
  formatDay, lengthText, rangeText, whenText,
} from '../../utils/travelDays.js';

/**
 * The panel while a range is being edited: the shared fields, saved with the PUT. A save closes the editor through
 * {@code onSaved}; a refusal shows under its field (or under the form when it names none).
 */
function RangeEditPanel({ range, title, meta, onSaved, onCancel, onClose }) {
  const { form, errors, formError, saving, set, submit } = useTravelRangeForm(
    { startDate: range.startDate, endDate: range.endDate, note: range.note ?? '' },
    async (payload) => { await updateTravelDay(range.id, payload); },
    'Failed to save travel range',
  );
  async function handleSave() {
    if (await submit()) onSaved();
  }
  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onCancel} disabled={saving} data-testid="travel-edit-cancel">
        Cancel
      </button>
      <button
        type="button"
        className="btn-primary max-[639px]:min-h-11"
        onClick={handleSave}
        disabled={saving}
        data-testid="travel-edit-save"
      >
        {saving ? 'Saving\u2026' : 'Save'}
      </button>
    </div>
  );
  return (
    <PanelShell
      title={title}
      subtitle={<span data-testid="travel-panel-meta">{meta}</span>}
      onClose={onClose}
      testIdPrefix="travel-panel"
      testId="travel-edit-panel"
      footer={footer}
    >
      <div className="flex flex-col gap-4 px-[18px] py-4 max-[639px]:px-4" {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        <TravelRangeForm idPrefix="travel-edit" form={form} errors={errors} onChange={set} disabled={saving} />
        {formError && (
          <p className="text-xs text-plex-error" role="alert" data-testid="travel-edit-error">{formError}</p>
        )}
      </div>
    </PanelShell>
  );
}

RangeEditPanel.propTypes = {
  range: PropTypes.shape({
    id: PropTypes.number.isRequired,
    startDate: PropTypes.string.isRequired,
    endDate: PropTypes.string.isRequired,
    note: PropTypes.string,
  }).isRequired,
  title: PropTypes.string.isRequired,
  meta: PropTypes.string.isRequired,
  onSaved: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
};

/**
 * The Travel days range panel (runs-redesign-plan.md §5.6): the range as its title, "{n} days · {when}" as its meta,
 * then Range (From, To, Note, with Edit), Effect, and Remove range. Edit swaps the Range values for the shared form
 * and a Cancel / Save footer; Remove needs no confirmation (the handoff's call) and is guarded against a double
 * press.
 *
 * @param {object} props
 * @param {object} props.range the served range
 * @param {Date} props.now the real clock
 * @param {boolean} props.editing whether the editor is open
 * @param {() => void} props.onEditStart opens the editor
 * @param {() => void} props.onEditCancel closes it without saving
 * @param {() => void} props.onSaved called after a save, to reload the ranges and close the editor
 * @param {() => void} props.onRemoved called after the range was removed
 * @param {() => void} props.onClose closes the panel
 */
export default function TravelRangePanel({
  range, now, editing, onEditStart, onEditCancel, onSaved, onRemoved, onClose,
}) {
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');
  const removeInFlight = useRef(false);

  async function handleRemove() {
    if (removeInFlight.current) return;
    removeInFlight.current = true;
    setRemoving(true);
    setRemoveError('');
    try {
      await deleteTravelDay(range.id);
      onRemoved();
    } catch {
      setRemoveError('Failed to remove travel range');
    } finally {
      removeInFlight.current = false;
      setRemoving(false);
    }
  }

  const title = rangeText(range);
  const meta = `${lengthText(range.startDate, range.endDate)} · ${whenText(range, now)}`;

  if (editing) {
    return <RangeEditPanel range={range} title={title} meta={meta} onSaved={onSaved} onCancel={onEditCancel} onClose={onClose} />;
  }

  return (
    <PanelShell
      title={title}
      subtitle={<span data-testid="travel-panel-meta">{meta}</span>}
      onClose={onClose}
      testIdPrefix="travel-panel"
    >
      <PanelSection
        label="Range"
        testId="travel-panel-range"
        action={(
          <button
            type="button"
            className={textButtonClass}
            onClick={onEditStart}
            data-testid="travel-panel-edit"
          >
            Edit
          </button>
        )}
      >
        <KvList
          items={[
            { label: 'From', value: formatDay(range.startDate), mono: true, testId: 'travel-panel-from' },
            { label: 'To', value: formatDay(range.endDate), mono: true, testId: 'travel-panel-to' },
            { label: 'Note', value: range.note, testId: 'travel-panel-note' },
          ]}
        />
      </PanelSection>
      <PanelSection label="Effect" testId="travel-panel-effect">
        <p className="text-[13px] text-plex-text-secondary">
          The Plan tab shows these as Away. No forecast is generated for them, so nothing is rated.
        </p>
      </PanelSection>
      <PanelSection label="Remove range" testId="travel-panel-remove-section">
        <p className="mb-3 text-[13px] text-plex-text-secondary">These days are forecast again from the next run.</p>
        <button
          type="button"
          className="rounded-lg border border-plex-coral px-3 py-1.5 text-sm font-medium text-plex-coral transition-colors hover:bg-plex-coral/10 focus-visible:outline-2 focus-visible:outline-plex-coral aria-disabled:opacity-40 max-[639px]:min-h-11"
          aria-disabled={removing ? 'true' : undefined}
          onClick={handleRemove}
          data-testid={`travel-day-delete-${range.id}`}
        >
          {removing ? 'Removing…' : 'Remove'}
        </button>
        {removeError && (
          <p className="mt-2 text-xs text-plex-coral" role="alert" data-testid="travel-remove-error">{removeError}</p>
        )}
      </PanelSection>
    </PanelShell>
  );
}

TravelRangePanel.propTypes = {
  range: PropTypes.shape({
    id: PropTypes.number.isRequired,
    startDate: PropTypes.string.isRequired,
    endDate: PropTypes.string.isRequired,
    note: PropTypes.string,
  }).isRequired,
  now: PropTypes.instanceOf(Date).isRequired,
  editing: PropTypes.bool.isRequired,
  onEditStart: PropTypes.func.isRequired,
  onEditCancel: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
  onRemoved: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
};
