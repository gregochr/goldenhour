import { useId, useRef, useState } from 'react';
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
function RangeEditPanel({ range, title, meta, onSaved, onCancel, onClose, onSavingChange }) {
  const { form, errors, formError, saving, set, submit } = useTravelRangeForm(
    { startDate: range.startDate, endDate: range.endDate, note: range.note ?? '' },
    async (payload) => { await updateTravelDay(range.id, payload); },
    'Failed to save travel range',
    onSavingChange,
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
        type="submit"
        form="travel-edit-form"
        className="btn-primary max-[639px]:min-h-11"
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
        <TravelRangeForm idPrefix="travel-edit" form={form} errors={errors} onChange={set} onSubmit={handleSave} disabled={saving} />
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
  onSavingChange: PropTypes.func.isRequired,
};

/**
 * The Travel days range panel (runs-redesign-plan.md §5.6): the range as its title, "{n} days · {when}" as its meta,
 * then Range (From, To, Note, with Edit), Effect, and a Remove range row (the words left, the coral Remove right). Edit swaps the Range values for the shared form
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
 * @param {(saving: boolean) => void} props.onSavingChange told when a save or a remove starts and ends
 */
export default function TravelRangePanel({
  range, now, editing, onEditStart, onEditCancel, onSaved, onRemoved, onClose, onSavingChange,
}) {
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');
  const removeInFlight = useRef(false);
  const removeHeadingId = useId();

  async function handleRemove() {
    if (removeInFlight.current) return;
    removeInFlight.current = true;
    setRemoving(true);
    onSavingChange(true);
    setRemoveError('');
    try {
      await deleteTravelDay(range.id);
      onRemoved();
    } catch {
      setRemoveError('Failed to remove travel range');
    } finally {
      removeInFlight.current = false;
      setRemoving(false);
      onSavingChange(false);
    }
  }

  const title = rangeText(range, now);
  const meta = `${lengthText(range.startDate, range.endDate)} · ${whenText(range, now)}`;

  if (editing) {
    return <RangeEditPanel
        range={range}
        title={title}
        meta={meta}
        onSaved={onSaved}
        onCancel={onEditCancel}
        onClose={onClose}
        onSavingChange={onSavingChange}
      />;
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
            { label: 'From', value: formatDay(range.startDate, now), mono: true, testId: 'travel-panel-from' },
            { label: 'To', value: formatDay(range.endDate, now), mono: true, testId: 'travel-panel-to' },
            { label: 'Note', value: range.note, testId: 'travel-panel-note' },
          ]}
        />
      </PanelSection>
      <PanelSection label="Effect" testId="travel-panel-effect">
        <p className="text-[13px] text-plex-text-secondary">
          The Plan tab shows these as Away. No forecast is generated for them, so nothing is rated.
        </p>
      </PanelSection>
      {/* One row, as the design draws it (no mono section label over the same words): the words on the left, the
          coral Remove on the right, stacking when the panel is narrow. */}
      <section
        aria-labelledby={removeHeadingId}
        className="px-[18px] py-[14px] max-[639px]:px-4"
        data-testid="travel-panel-remove-section"
      >
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
          <div className="min-w-0">
            <p id={removeHeadingId} className="text-[13px] text-plex-text">Remove range</p>
            <p className="mt-0.5 text-[12px] text-plex-text-secondary">These days are forecast again from the next run.</p>
          </div>
          <button
            type="button"
            className="rounded-lg border border-plex-coral px-3 py-1.5 text-sm font-medium text-plex-coral transition-colors hover:bg-plex-coral/10 focus-visible:outline-2 focus-visible:outline-plex-coral aria-disabled:opacity-40 max-[639px]:min-h-11"
            aria-disabled={removing ? 'true' : undefined}
            onClick={handleRemove}
            data-testid={`travel-day-delete-${range.id}`}
          >
            {removing ? 'Removing…' : 'Remove'}
          </button>
        </div>
        {removeError && (
          <p className="mt-2 text-xs text-plex-coral" role="alert" data-testid="travel-remove-error">{removeError}</p>
        )}
      </section>
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
  onSavingChange: PropTypes.func.isRequired,
};
