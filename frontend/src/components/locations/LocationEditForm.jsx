import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import LocationEditMore from './LocationEditMore.jsx';
import { Field, FieldError, RegionSelect, SubRegionSelect, ToggleGroup } from './LocationFormParts.jsx';
import { inputClass, textButtonClass } from '../users/userFormClasses.js';
import { LOCATION_TYPES, TIDE_TYPES } from '../../utils/locationEdit.js';
import { LIGHT_TOKENS, locationMeta } from '../../utils/locationAdmin.js';

const LIGHT_OPTIONS = LIGHT_TOKENS.map((t) => ({ value: t.value, label: t.word }));
const TIDE_OPTIONS = TIDE_TYPES.map((t) => ({ value: t.value, label: t.fullLabel }));
const BLOCK = 'flex flex-col gap-4 border-b border-plex-border px-[18px] py-4 max-[639px]:px-4';
const MONO_INPUT = `${inputClass} font-mono`;

/** The error of one field, when the form's current error belongs to it. */
function errorFor(editor, field) {
  return editor.errorField === field ? editor.error : '';
}

/**
 * The location edit form, in the panel (data-tabs-redesign-plan.md §6.4): Name; Latitude and Longitude; Type, Light and
 * Tides worth shooting (each a multi-select whose last choice is sticky); Region and Sub-region; then a More section
 * with the rest of what an admin can edit. The footer is pinned: Cancel and Save changes.
 *
 * <p>The state and the save sequence live in {@code useLocationEditor}; this only draws them. Each validation message
 * shows under its own field, and a refusal that belongs to no single field (a server error) shows under the form.
 * Esc in any field of this form cancels the edit (the {@link PANEL_EDIT_FORM_ATTR} marks it for {@code usePanelEscape}).
 *
 * @param {object} props
 * @param {object} props.location the location being edited, as the list holds it
 * @param {object} props.editor the {@code useLocationEditor} result (its {@code values} are non-null here)
 * @param {Array<object>} props.regions every region
 * @param {Array<object>} props.subRegions every SUB_REGION area
 * @param {boolean} props.aliasesFailed whether the other names could not be loaded
 * @param {() => void} props.onClose closes the whole panel
 * @param {() => void} props.onCancel cancels the edit, back to the view
 */
export default function LocationEditForm({ location, editor, regions, subRegions, aliasesFailed, onClose, onCancel }) {
  const v = editor.values;
  const coastal = v.locationTypes.includes('SEASCAPE');
  const regionOptions = regions.filter((r) => r.enabled || String(r.id) === v.regionId);

  // The message sits under its field, which may be scrolled out of sight behind the pinned footer: bring it in.
  const body = useRef(null);
  useEffect(() => {
    if (editor.error) body.current?.querySelector('[data-testid="location-edit-error"]')?.scrollIntoView?.({ block: 'nearest' });
  }, [editor.error, editor.errorField]);

  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onCancel} disabled={editor.saving} data-testid="location-edit-cancel">
        Cancel
      </button>
      <button
        type="button"
        className="btn-primary max-[639px]:min-h-11"
        onClick={editor.save}
        disabled={editor.saving || v.floorBusy === true}
        data-testid="location-edit-save"
      >
        {editor.saving ? 'Saving…' : 'Save changes'}
      </button>
    </div>
  );

  // An error with no field of its own (a refused save) shows under the whole form.
  const unplaced = editor.errorField == null ? editor.error : '';

  return (
    <PanelShell
      title={`Edit ${location.name}`}
      subtitle={locationMeta(location)}
      onClose={onClose}
      testIdPrefix="location-panel"
      testId="location-edit-panel"
      footer={footer}
    >
      <div ref={body} {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        <div className={BLOCK}>
          <Field label="Name" htmlFor="location-edit-name">
            <input
              id="location-edit-name"
              type="text"
              autoComplete="off"
              className={inputClass}
              value={v.name}
              onChange={(e) => editor.change('name', e.target.value)}
              aria-invalid={editor.errorField === 'name' || undefined}
              data-testid="location-edit-name"
            />
            <FieldError message={errorFor(editor, 'name')} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Latitude" htmlFor="location-edit-lat">
              <input
                id="location-edit-lat"
                type="number"
                step="any"
                className={MONO_INPUT}
                value={v.lat}
                onChange={(e) => editor.change('lat', e.target.value)}
                aria-invalid={editor.errorField === 'lat' || undefined}
                data-testid="location-edit-lat"
              />
              <FieldError message={errorFor(editor, 'lat')} />
            </Field>
            <Field label="Longitude" htmlFor="location-edit-lon">
              <input
                id="location-edit-lon"
                type="number"
                step="any"
                className={MONO_INPUT}
                value={v.lon}
                onChange={(e) => editor.change('lon', e.target.value)}
                aria-invalid={editor.errorField === 'lon' || undefined}
                data-testid="location-edit-lon"
              />
              <FieldError message={errorFor(editor, 'lon')} />
            </Field>
          </div>
        </div>

        <div className={BLOCK}>
          <Field label="Type">
            <ToggleGroup
              label="Type"
              options={LOCATION_TYPES}
              selected={v.locationTypes}
              onChange={(next) => editor.change('locationTypes', next)}
              testIdPrefix="type-chip"
            />
            <FieldError message={errorFor(editor, 'types')} />
          </Field>
          <Field label="Light">
            <ToggleGroup
              label="Light"
              options={LIGHT_OPTIONS}
              selected={v.solarEventTypes}
              onChange={(next) => editor.patch({ solarEventTypes: next })}
              variant="tint"
              testIdPrefix="solar-chip"
            />
          </Field>
          <Field label="Tides worth shooting" hint="Seascapes need at least one.">
            <ToggleGroup
              label="Tides worth shooting"
              options={TIDE_OPTIONS}
              selected={v.tideTypes}
              onChange={(next) => editor.patch({ tideTypes: next })}
              variant="tint"
              disabled={!coastal}
              testIdPrefix="tide-chip"
            />
            <FieldError message={errorFor(editor, 'tides')} />
          </Field>
        </div>

        <div className={BLOCK}>
          <Field label="Region" htmlFor="location-edit-region">
            <RegionSelect
              id="location-edit-region"
              regions={regionOptions}
              value={v.regionId}
              onChange={(value) => editor.change('regionId', value)}
              testId="location-edit-region"
            />
          </Field>
          <SubRegionSelect
            id="location-edit-sub-region"
            subRegions={subRegions}
            regionId={v.regionId}
            value={v.subRegionId}
            onChange={(value) => editor.change('subRegionId', value)}
            canBeEmpty={!v.subRegionBaseline || v.regionId !== v.regionBaseline}
            testId="location-edit-sub-region"
          />
        </div>

        <LocationEditMore editor={editor} aliasesFailed={aliasesFailed} />

        {unplaced && (
          <p className="px-[18px] pb-4 text-xs text-plex-error max-[639px]:px-4" role="alert" data-testid="location-edit-error">
            {unplaced}
          </p>
        )}
      </div>
    </PanelShell>
  );
}

LocationEditForm.propTypes = {
  location: PropTypes.object.isRequired,
  editor: PropTypes.object.isRequired,
  regions: PropTypes.arrayOf(PropTypes.object).isRequired,
  subRegions: PropTypes.arrayOf(PropTypes.object).isRequired,
  aliasesFailed: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
