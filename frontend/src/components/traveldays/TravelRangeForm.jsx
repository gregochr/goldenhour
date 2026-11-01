import PropTypes from 'prop-types';
import { Field, FieldError } from '../locations/LocationFormParts.jsx';
import { inputClass } from '../users/userFormClasses.js';
import { NOTE_MAX_LENGTH } from '../../utils/travelDays.js';

/** Opens the native calendar wherever the field is clicked, not just on its small icon (showPicker needs a gesture). */
function openPicker(event) {
  try {
    event.currentTarget.showPicker?.();
  } catch {
    // showPicker is unsupported here, or the picker is already open.
  }
}

/**
 * The From, To and Note fields the Edit and Add panels share. From and To sit in two columns, each opening the
 * native picker on a click; the note is optional (200 characters at most). A field's error sits under it.
 *
 * @param {object} props
 * @param {string} props.idPrefix prefix for the inputs' ids and test ids ({@code travel-edit}, {@code travel-add})
 * @param {{startDate: string, endDate: string, note: string}} props.form the values
 * @param {{startDate?: string, endDate?: string, note?: string}} props.errors the message under each field
 * @param {(field: string, value: string) => void} props.onChange called with the field and its new value
 * @param {() => void} props.onSubmit called on Enter or the submit button (a button elsewhere points at the form's id,
 *   {@code {idPrefix}-form}, with its {@code form} attribute)
 * @param {boolean} [props.disabled] true while a save is in flight
 */
export default function TravelRangeForm({ idPrefix, form, errors, onChange, onSubmit, disabled = false }) {
  return (
    <form
      id={`${idPrefix}-form`}
      noValidate
      className="flex flex-col gap-4"
      onSubmit={(e) => { e.preventDefault(); onSubmit(); }}
      data-testid={`${idPrefix}-form`}
    >
      <div className="grid grid-cols-2 gap-3">
        <Field label="From (inclusive)" htmlFor={`${idPrefix}-start`}>
          <input
            id={`${idPrefix}-start`}
            type="date"
            value={form.startDate}
            onChange={(e) => onChange('startDate', e.target.value)}
            onClick={openPicker}
            disabled={disabled}
            aria-invalid={errors.startDate ? 'true' : undefined}
            className={`${inputClass} date-field cursor-pointer`}
            data-testid={`${idPrefix}-start`}
          />
          <FieldError message={errors.startDate} testId={`${idPrefix}-start-error`} />
        </Field>
        <Field label="To (inclusive)" htmlFor={`${idPrefix}-end`}>
          <input
            id={`${idPrefix}-end`}
            type="date"
            value={form.endDate}
            onChange={(e) => onChange('endDate', e.target.value)}
            onClick={openPicker}
            disabled={disabled}
            aria-invalid={errors.endDate ? 'true' : undefined}
            className={`${inputClass} date-field cursor-pointer`}
            data-testid={`${idPrefix}-end`}
          />
          <FieldError message={errors.endDate} testId={`${idPrefix}-end-error`} />
        </Field>
      </div>
      <Field label="Note (optional)" htmlFor={`${idPrefix}-note`}>
        <input
          id={`${idPrefix}-note`}
          type="text"
          value={form.note}
          maxLength={NOTE_MAX_LENGTH}
          onChange={(e) => onChange('note', e.target.value)}
          disabled={disabled}
          placeholder={'London — work'}
          aria-invalid={errors.note ? 'true' : undefined}
          className={inputClass}
          data-testid={`${idPrefix}-note`}
        />
        <FieldError message={errors.note} testId={`${idPrefix}-note-error`} />
      </Field>
    </form>
  );
}

TravelRangeForm.propTypes = {
  idPrefix: PropTypes.string.isRequired,
  form: PropTypes.shape({
    startDate: PropTypes.string.isRequired,
    endDate: PropTypes.string.isRequired,
    note: PropTypes.string.isRequired,
  }).isRequired,
  errors: PropTypes.shape({
    startDate: PropTypes.string,
    endDate: PropTypes.string,
    note: PropTypes.string,
  }).isRequired,
  onChange: PropTypes.func.isRequired,
  onSubmit: PropTypes.func.isRequired,
  disabled: PropTypes.bool,
};
