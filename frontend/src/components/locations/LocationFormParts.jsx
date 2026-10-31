import PropTypes from 'prop-types';
import { labelClass, selectClass } from '../users/userFormClasses.js';
import { toggleSticky } from '../../utils/locationEdit.js';

/** A toggle button's shared box: 44px tall on a phone (the touch-target rule). */
const TOGGLE_BASE = 'inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-[13px] transition-colors focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 disabled:cursor-not-allowed disabled:opacity-40';

/** Selected looks per variant: a segment is filled ink on dark text; a tint is the tide colour with a tick. */
const TOGGLE_ON = {
  segment: 'border-plex-text bg-plex-text font-medium text-plex-bg',
  tint: 'border-tide/60 bg-tide/15 font-medium text-plex-text',
};
const TOGGLE_OFF = 'border-plex-border bg-plex-surface-light text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text';

/**
 * A multi-select toggle group (a location's types, its light, its tide preferences): each option is a pressed or
 * unpressed button, and the last one selected cannot be turned off (a location with no type routes to no evaluation
 * lane, with no light nothing is shot, and a seascape with no tide is rejected by the server).
 *
 * @param {object} props
 * @param {Array<{value: string, label: string}>} props.options the choices, in display order
 * @param {Array<string>} props.selected the values held
 * @param {(next: Array<string>) => void} props.onChange called with the new values
 * @param {'segment'|'tint'} [props.variant] the selected look: filled ink, or tide tint with a tick
 * @param {boolean} [props.disabled] mutes and disables every option
 * @param {string} props.testIdPrefix each option's test id is {@code {prefix}-{value}}, the group's is {@code {prefix}s}
 * @param {string} props.label the group's accessible name
 */
export function ToggleGroup({
  options, selected, onChange, variant = 'segment', disabled = false, testIdPrefix, label,
}) {
  function press(value) {
    if (disabled) return;
    const next = toggleSticky(selected, value);
    if (next) onChange(next);
  }
  return (
    <div className="flex flex-wrap gap-1.5" role="group" aria-label={label} data-testid={`${testIdPrefix}s`}>
      {options.map((option) => {
        const on = selected.includes(option.value);
        return (
          <button
            key={option.value}
            type="button"
            title={option.label}
            aria-pressed={on}
            disabled={disabled}
            onClick={() => press(option.value)}
            className={`${TOGGLE_BASE} ${on ? TOGGLE_ON[variant] : TOGGLE_OFF}`}
            data-testid={`${testIdPrefix}-${option.value}`}
          >
            {on && variant === 'tint' && <span className="text-tide" aria-hidden="true">&#10003;</span>}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

ToggleGroup.propTypes = {
  options: PropTypes.arrayOf(PropTypes.shape({ value: PropTypes.string, label: PropTypes.string })).isRequired,
  selected: PropTypes.arrayOf(PropTypes.string).isRequired,
  onChange: PropTypes.func.isRequired,
  variant: PropTypes.oneOf(['segment', 'tint']),
  disabled: PropTypes.bool,
  testIdPrefix: PropTypes.string.isRequired,
  label: PropTypes.string.isRequired,
};

/** A form field: its label, the control, then a hint and the field's error. */
export function Field({ label, htmlFor = undefined, hint = null, hintTestId = undefined, children }) {
  return (
    <div>
      {htmlFor ? (
        <label htmlFor={htmlFor} className={labelClass}>{label}</label>
      ) : (
        <span className={labelClass}>{label}</span>
      )}
      {children}
      {hint && <p className="mt-1.5 text-xs text-plex-text-muted" data-testid={hintTestId}>{hint}</p>}
    </div>
  );
}

Field.propTypes = {
  label: PropTypes.string.isRequired,
  htmlFor: PropTypes.string,
  hint: PropTypes.node,
  hintTestId: PropTypes.string,
  children: PropTypes.node,
};

/** A field's error line, under its control. */
export function FieldError({ message, children = null, testId = 'location-edit-error' }) {
  if (!message) return null;
  return (
    <p className="mt-1.5 text-xs text-plex-error" role="alert" data-testid={testId}>
      {message}
      {children}
    </p>
  );
}

FieldError.propTypes = { message: PropTypes.string, children: PropTypes.node, testId: PropTypes.string };

/**
 * The region select shared by the edit and add forms. The caller decides which regions are offered (Add offers enabled
 * regions only; Edit those plus the location's current one).
 *
 * @param {object} props
 * @param {string} props.id the select's id (its label points at it)
 * @param {Array<{id: number, name: string}>} props.regions the regions to offer
 * @param {string} props.value the chosen region id as text, or ''
 * @param {(value: string) => void} props.onChange called with the new id as text
 * @param {string} props.testId the select's test id
 */
export function RegionSelect({ id, regions, value, onChange, testId }) {
  return (
    <select id={id} className={selectClass} value={value} onChange={(e) => onChange(e.target.value)} data-testid={testId}>
      <option value="">— None —</option>
      {regions.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
    </select>
  );
}

RegionSelect.propTypes = {
  id: PropTypes.string.isRequired,
  regions: PropTypes.arrayOf(PropTypes.shape({ id: PropTypes.number, name: PropTypes.string })).isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/**
 * The sub-region select shared by the edit and add forms: only the sub-regions of the chosen region, and nothing at
 * all when that region has none (or the areas have not loaded). The empty choice is offered only when it means
 * something (a location with a sub-region cannot be left with none by a plain pick, since the API has no "clear").
 *
 * @param {object} props
 * @param {string} props.id the select's id
 * @param {Array<object>} props.subRegions every SUB_REGION area
 * @param {string|number} props.regionId the chosen region
 * @param {string} props.value the chosen sub-region id as text, or ''
 * @param {(value: string) => void} props.onChange called with the new id as text
 * @param {boolean} props.canBeEmpty whether "No sub-region" is a choice
 * @param {string} props.testId the select's test id
 */
export function SubRegionSelect({ id, subRegions, regionId, value, onChange, canBeEmpty, testId }) {
  const options = subRegions.filter((a) => String(a.regionId) === String(regionId));
  if (options.length === 0) return null;
  return (
    <div>
      <label htmlFor={id} className={labelClass}>Sub-region</label>
      <select
        id={id}
        className={selectClass}
        aria-label="Sub-region"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        data-testid={testId}
      >
        {canBeEmpty && <option value="">— No sub-region —</option>}
        {options.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
      </select>
    </div>
  );
}

SubRegionSelect.propTypes = {
  id: PropTypes.string.isRequired,
  subRegions: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.number.isRequired,
    name: PropTypes.string.isRequired,
    regionId: PropTypes.number,
  })).isRequired,
  regionId: PropTypes.oneOfType([PropTypes.string, PropTypes.number]).isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  canBeEmpty: PropTypes.bool.isRequired,
  testId: PropTypes.string.isRequired,
};

/** A labelled checkbox row. */
export function CheckRow({ label, checked, onChange, testId }) {
  return (
    <label className="flex cursor-pointer items-start gap-2 text-[13px] text-plex-text max-[639px]:min-h-11 max-[639px]:items-center">
      <input
        className="mt-0.5 shrink-0 max-[639px]:mt-0 max-[639px]:size-5"
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        data-testid={testId}
      />
      {label}
    </label>
  );
}

CheckRow.propTypes = {
  label: PropTypes.string.isRequired,
  checked: PropTypes.bool.isRequired,
  onChange: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/** A small secondary button inside a form (Suggest, Clear), 44px on a phone. */
export const miniButtonClass = 'rounded-md border border-plex-border bg-plex-surface-light px-2.5 py-1 text-xs text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold disabled:cursor-not-allowed disabled:opacity-50 max-[639px]:min-h-11';
