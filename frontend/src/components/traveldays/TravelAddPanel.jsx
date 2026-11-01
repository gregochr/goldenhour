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
 * @param {string} [props.initialFrom] the From date to start with (a calendar day that was pressed), or ''
 * @param {() => void} props.onClose closes the panel
 * @param {(created: object) => void} props.onCreated told the created range once the server has made it
 */
export default function TravelAddPanel({ initialFrom = '', onClose, onCreated }) {
  const { form, errors, formError, saving, set, submit } = useTravelRangeForm(
    { startDate: initialFrom, endDate: '', note: '' },
    async (payload) => { onCreated(await addTravelDay(payload)); },
    'Failed to add travel range',
  );
  const valid = form.startDate !== '' && form.endDate !== '' && form.endDate >= form.startDate;

  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onClose} disabled={saving} data-testid="travel-add-cancel">
        Cancel
      </button>
      <button
        type="button"
        className="btn-primary max-[639px]:min-h-11"
        onClick={submit}
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
        <TravelRangeForm idPrefix="travel-day" form={form} errors={errors} onChange={set} disabled={saving} />
        {valid && (
          <div
            className="rounded-md border border-plex-border bg-plex-surface-light px-3 py-2"
            data-testid="travel-add-summary"
          >
            <p className="font-mono text-[12.5px] text-plex-text">{lengthText(form.startDate, form.endDate)}</p>
            <p className="mt-0.5 text-xs text-plex-text-secondary">
              {formatDay(form.startDate)} to {formatDay(form.endDate)}, both included.
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
  onClose: PropTypes.func.isRequired,
  onCreated: PropTypes.func.isRequired,
};
