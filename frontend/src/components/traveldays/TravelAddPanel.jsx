import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import TravelRangeForm from './TravelRangeForm.jsx';
import useTravelRangeForm from '../../hooks/useTravelRangeForm.js';
import { addTravelDay } from '../../api/travelDayApi.js';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import { textButtonClass } from '../users/userFormClasses.js';
import { formatDay, lengthText } from '../../utils/travelDays.js';

/**
 * Add travel days, in the panel (runs-redesign-plan.md §5.6): From and To in two columns, an optional note, a summary
 * box once the dates make a range, and Cancel / Add range in the footer. Validation is the screen's long-standing
 * (both dates; end not before start); a refusal from the server shows under its field.
 *
 * @param {object} props
 * @param {string} [props.initialFrom] the From date to open with (a draft that was dragged first), or ''
 * @param {string} [props.initialTo] the To date to open with, or ''
 * @param {number} [props.fillSeq] the count of fills from the calendar's draft; the panel applies a fill only when
 *   this differs from the value it mounted with, so a fill made for an earlier panel never lands here
 * @param {string} [props.fillStart] the From date of the latest fill
 * @param {string} [props.fillEnd] the To date of the latest fill
 * @param {Date} props.now the real clock, for the year on a date outside this one
 * @param {() => void} props.onClose closes the panel
 * @param {(created: object) => (void|Promise<void>)} props.onCreated told the created range once the server has made it; the
 *   save counts as in flight until it settles (the view reloads in it)
 * @param {(saving: boolean) => void} props.onSavingChange told when a save starts and ends
 */
export default function TravelAddPanel({
  initialFrom = '', initialTo = '', fillSeq = 0, fillStart = '', fillEnd = '', now, onClose, onCreated, onSavingChange,
}) {
  const { form, errors, formError, saving, set, submit } = useTravelRangeForm(
    { startDate: initialFrom, endDate: initialTo, note: '' },
    async (payload) => { await onCreated(await addTravelDay(payload)); },
    'Failed to add travel range',
    onSavingChange,
  );
  // A later fill from the calendar (state adjusted while rendering): the dates change, the typed note stays.
  const [seenSeq, setSeenSeq] = useState(fillSeq);
  if (fillSeq !== seenSeq) {
    setSeenSeq(fillSeq);
    set('startDate', fillStart);
    set('endDate', fillEnd);
  }
  const valid = form.startDate !== '' && form.endDate !== '' && form.endDate >= form.startDate;

  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onClose} disabled={saving} data-testid="travel-add-cancel">
        Cancel
      </button>
      <button
        type="submit"
        form="travel-day-form"
        className="btn-primary max-[639px]:min-h-11"
        disabled={saving}
        data-testid="travel-day-add"
      >
        {saving ? 'Adding…' : 'Add range'}
      </button>
    </div>
  );

  return (
    <PanelShell
      title="Add travel days"
      subtitle="No forecasts are generated for these dates."
      onClose={onClose}
      testIdPrefix="travel-panel"
      testId="travel-add-panel"
      footer={footer}
    >
      <div className="flex flex-col gap-4 px-[18px] py-4 max-[639px]:px-4" {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        <TravelRangeForm idPrefix="travel-day" form={form} errors={errors} onChange={set} onSubmit={submit} disabled={saving} />
        {valid && (
          <div
            className="rounded-md border border-plex-border bg-plex-surface-light px-3 py-2"
            data-testid="travel-add-summary"
          >
            <p className="font-mono text-[12.5px] text-plex-text">{lengthText(form.startDate, form.endDate)}</p>
            <p className="mt-0.5 text-xs text-plex-text-secondary">
              {formatDay(form.startDate, now)} to {formatDay(form.endDate, now)}, both included.
            </p>
          </div>
        )}
        {formError && (
          <p className="text-xs text-plex-error" role="alert" data-testid="travel-add-error">{formError}</p>
        )}
      </div>
    </PanelShell>
  );
}

TravelAddPanel.propTypes = {
  initialFrom: PropTypes.string,
  initialTo: PropTypes.string,
  fillSeq: PropTypes.number,
  fillStart: PropTypes.string,
  fillEnd: PropTypes.string,
  now: PropTypes.instanceOf(Date).isRequired,
  onClose: PropTypes.func.isRequired,
  onCreated: PropTypes.func.isRequired,
  onSavingChange: PropTypes.func.isRequired,
};
