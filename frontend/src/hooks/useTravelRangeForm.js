import { useCallback, useEffect, useRef, useState } from 'react';
import { placeServerError, validateRange } from '../utils/travelDays.js';

/**
 * The state both the Edit and the Add travel-range forms share: the three values, the message under each field, a
 * form-level message (a refusal that names no field), and the save. The dates are checked here first, with the
 * messages the screen has always used; a 400 from the server lands under the field it names.
 *
 * <p>The save is guarded by a ref set inside the call, so a double press never sends twice. Changing a field clears
 * that field's message.
 *
 * @param {{startDate: string, endDate: string, note: string}} initial the starting values
 * @param {(payload: {startDate: string, endDate: string, note: string|null}) => Promise<void>} save sends the range;
 *   rejects on failure
 * @param {string} failureText what to say when the server gave no usable sentence
 * @param {(saving: boolean) => void} [onSavingChange] told when a save starts and ends (and false if the form unmounts
 *   mid-save), so the view can refuse to close the panel on a save in flight
 * @returns {{form: object, errors: object, formError: string, saving: boolean,
 *   set: (field: string, value: string) => void, submit: () => Promise<boolean>}} the form
 */
export default function useTravelRangeForm(initial, save, failureText, onSavingChange = undefined) {
  const [form, setForm] = useState(initial);
  const [errors, setErrors] = useState({});
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);

  useEffect(() => {
    onSavingChange?.(saving);
    return () => onSavingChange?.(false);
  }, [saving, onSavingChange]);

  const set = useCallback((field, value) => {
    setForm((current) => ({ ...current, [field]: value }));
    setErrors((current) => (current[field] ? { ...current, [field]: undefined } : current));
    setFormError('');
  }, []);

  const submit = useCallback(async () => {
    if (inFlight.current) return false;
    const found = validateRange(form);
    if (Object.keys(found).length > 0) {
      setErrors(found);
      setFormError('');
      return false;
    }
    inFlight.current = true;
    setErrors({});
    setFormError('');
    setSaving(true);
    try {
      await save({ startDate: form.startDate, endDate: form.endDate, note: form.note.trim() || null });
      return true;
    } catch (err) {
      const placed = placeServerError(err, failureText);
      if (placed.field) setErrors({ [placed.field]: placed.message });
      else setFormError(placed.message);
      return false;
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }, [form, save, failureText]);

  return { form, errors, formError, saving, set, submit };
}
