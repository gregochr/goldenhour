import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import KvList from '../data/KvList.jsx';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import {
  CheckRow, Field, FieldError, RegionSelect, SubRegionSelect, ToggleGroup,
} from './LocationFormParts.jsx';
import { inputClass, textButtonClass } from '../users/userFormClasses.js';
import { LOCATION_TYPES, TIDE_TYPES } from '../../utils/locationEdit.js';
import { LIGHT_TOKENS, bortleBlurb } from '../../utils/locationAdmin.js';

const LIGHT_OPTIONS = LIGHT_TOKENS.map((t) => ({ value: t.value, label: t.word }));
const TIDE_OPTIONS = TIDE_TYPES.map((t) => ({ value: t.value, label: t.fullLabel }));
const BLOCK = 'flex flex-col gap-4 border-b border-plex-border px-[18px] py-4 max-[639px]:px-4';
const MONO_INPUT = `${inputClass} font-mono`;

/** A found value, or an em dash. */
function found(value, unit = '') {
  return value == null ? '—' : `${value}${unit}`;
}

/** The auto-detected facts for a looked-up place, or the pulsing line while they are still being found. */
function Detected({ enriching, data }) {
  if (enriching) {
    return (
      <p className="animate-pulse text-xs text-plex-text-muted" data-testid="enriching-spinner">
        Detecting elevation, Bortle, grid cell…
      </p>
    );
  }
  if (!data) return null;
  return (
    <div data-testid="enrichment-panel">
      <p className="mb-2 font-mono text-[10px] uppercase tracking-[0.18em] text-plex-text-muted">Auto-detected</p>
      <KvList
        items={[
          { label: 'Elevation', value: found(data.elevationMetres, ' m'), mono: true, testId: 'enrichment-elevation' },
          {
            label: 'Bortle',
            value: data.bortleClass == null ? '—' : `${data.bortleClass} ${bortleBlurb(data.bortleClass)}`,
            mono: true,
            testId: 'enrichment-bortle',
          },
          { label: 'Sky brightness', value: found(data.skyBrightnessSqm, data.skyBrightnessSqm == null ? '' : ' SQM'), mono: true, testId: 'enrichment-sqm' },
          {
            label: 'Grid cell',
            value: data.gridLat != null && data.gridLng != null ? `${data.gridLat}, ${data.gridLng}` : '—',
            mono: true,
            testId: 'enrichment-grid',
          },
        ]}
      />
    </div>
  );
}

Detected.propTypes = { enriching: PropTypes.bool.isRequired, data: PropTypes.object };

/**
 * The Add location form, in the panel (data-tabs-redesign-plan.md §6.5), with no confirm step. A place name and Look up
 * give a result box (coordinates and display name) and, in the background, the auto-detected Elevation, Bortle, sky
 * brightness and grid cell; "Enter coordinates instead" swaps to a name, latitude and longitude. Then Type, Light and
 * Tides worth shooting (as in Edit), the Overlooks water, Coastal tidal and Mist-prone checkboxes, and Region (enabled
 * regions only) with its Sub-region. Add location is disabled until there is a result or valid manual coordinates.
 *
 * @param {object} props
 * @param {object} props.add the {@code useLocationAdd} result
 * @param {Array<object>} props.regions every region (only the enabled ones are offered)
 * @param {Array<object>} props.subRegions every SUB_REGION area
 * @param {() => void} props.onSubmit adds the location
 * @param {() => void} props.onCancel closes the form
 */
export default function LocationAddForm({ add, regions, subRegions, onSubmit, onCancel }) {
  const { form } = add;
  const coastal = form.locationTypes.includes('SEASCAPE');
  // A validation message sits under its field; only a refusal from the server (no field) sits under the form.
  const errorFor = (field) => (add.errorField === field ? add.error : '');
  const unplaced = add.errorField == null ? add.error : '';

  // The message may be out of sight behind the pinned footer: bring it in, as the edit form does.
  const body = useRef(null);
  useEffect(() => {
    if (add.error) body.current?.querySelector('[data-testid="location-add-error"]')?.scrollIntoView?.({ block: 'nearest' });
  }, [add.error, add.errorField]);

  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onCancel} disabled={add.saving} data-testid="location-add-cancel">
        Cancel
      </button>
      <button
        type="button"
        className="btn-primary max-[639px]:min-h-11"
        onClick={onSubmit}
        disabled={!add.canSubmit || add.saving}
        data-testid="location-add-submit"
      >
        {add.saving ? 'Adding…' : 'Add location'}
      </button>
    </div>
  );

  return (
    <PanelShell
      title="Add location"
      subtitle="Look up a place, then check the details."
      onClose={onCancel}
      testIdPrefix="location-panel"
      testId="location-add-panel"
      footer={footer}
    >
      <div ref={body} {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        <div className={BLOCK}>
          {!form.manualEntry ? (
            <>
              <Field label="Place name" htmlFor="place-name">
                <div className="flex gap-2">
                  <input
                    id="place-name"
                    type="text"
                    autoComplete="off"
                    className={inputClass}
                    placeholder="e.g. Bamburgh, Northumberland"
                    value={form.placeName}
                    onChange={(e) => add.set('placeName', e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') add.geocode(); }}
                    data-testid="place-name-input"
                  />
                  <button
                    type="button"
                    className="btn-secondary shrink-0 text-xs max-[639px]:min-h-11"
                    onClick={add.geocode}
                    disabled={add.geocoding}
                    data-testid="geocode-btn"
                  >
                    {add.geocoding ? 'Looking up...' : 'Look up'}
                  </button>
                </div>
                <FieldError message={form.geocodeError} testId="location-geocode-error" />
              </Field>

              {form.geocodeResult && (
                <div className="rounded-md border border-plex-border bg-plex-surface-light px-3 py-2" data-testid="location-geocode-result">
                  <p className="font-mono text-[12.5px] text-plex-text">
                    {form.geocodeResult.lat.toFixed(4)}, {form.geocodeResult.lon.toFixed(4)}
                  </p>
                  <p className="mt-0.5 text-xs text-plex-text-secondary">{form.geocodeResult.displayName}</p>
                  <p className="mt-1 text-xs text-plex-text-muted" data-testid="location-add-save-name">
                    Added as <span className="text-plex-text">{form.placeName.trim() || '—'}</span>
                  </p>
                </div>
              )}

              <Detected enriching={add.enriching} data={form.enrichmentData} />

              {!form.geocodeResult && (
                <p className="text-xs text-plex-text-muted" data-testid="location-add-hint">
                  Search for a place above, or enter its coordinates instead.
                </p>
              )}
              <button
                type="button"
                className={`${textButtonClass} self-start underline`}
                onClick={add.enterManually}
                data-testid="manual-entry-btn"
              >
                Enter coordinates instead
              </button>
            </>
          ) : (
            <>
              <Field label="Name" htmlFor="manual-name">
                <input
                  id="manual-name"
                  type="text"
                  autoComplete="off"
                  className={inputClass}
                  placeholder="e.g. Bamburgh Castle"
                  value={form.manualName}
                  onChange={(e) => add.set('manualName', e.target.value)}
                  data-testid="manual-name-input"
                />
                <FieldError message={errorFor('name')} testId="location-add-error" />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Latitude" htmlFor="manual-lat">
                  <input
                    id="manual-lat"
                    type="number"
                    step="any"
                    className={MONO_INPUT}
                    placeholder="e.g. 55.6089"
                    value={form.manualLat}
                    onChange={(e) => add.set('manualLat', e.target.value)}
                    data-testid="manual-lat-input"
                  />
                  <FieldError message={errorFor('lat')} testId="location-add-error" />
                </Field>
                <Field label="Longitude" htmlFor="manual-lon">
                  <input
                    id="manual-lon"
                    type="number"
                    step="any"
                    className={MONO_INPUT}
                    placeholder="e.g. -1.7099"
                    value={form.manualLon}
                    onChange={(e) => add.set('manualLon', e.target.value)}
                    data-testid="manual-lon-input"
                  />
                  <FieldError message={errorFor('lon')} testId="location-add-error" />
                </Field>
              </div>
              <button
                type="button"
                className={`${textButtonClass} self-start underline`}
                onClick={add.backToLookup}
                data-testid="back-to-geocode-btn"
              >
                Back to place lookup
              </button>
            </>
          )}
        </div>

        <div className={BLOCK}>
          <Field label="Type">
            <ToggleGroup
              label="Type"
              options={LOCATION_TYPES}
              selected={form.locationTypes}
              onChange={add.changeTypes}
              testIdPrefix="type-chip"
            />
          </Field>
          <Field label="Light">
            <ToggleGroup
              label="Light"
              options={LIGHT_OPTIONS}
              selected={form.solarEventTypes}
              onChange={(next) => add.set('solarEventTypes', next)}
              variant="tint"
              testIdPrefix="solar-chip"
            />
          </Field>
          <Field label="Tides worth shooting" hint="Seascapes need at least one.">
            <ToggleGroup
              label="Tides worth shooting"
              options={TIDE_OPTIONS}
              selected={form.tideTypes}
              onChange={(next) => add.set('tideTypes', next)}
              variant="tint"
              disabled={!coastal}
              testIdPrefix="tide-chip"
            />
            <FieldError message={errorFor('tides')} testId="location-add-error" />
          </Field>
          <div className="flex flex-col" data-testid="manual-toggles-panel">
            <CheckRow
              label="Overlooks water"
              checked={form.overlooksWater}
              onChange={(checked) => add.set('overlooksWater', checked)}
              testId="overlooks-water-checkbox"
            />
            <CheckRow
              label="Coastal tidal"
              checked={form.coastalTidal}
              onChange={(checked) => add.set('coastalTidal', checked)}
              testId="coastal-tidal-checkbox"
            />
            <CheckRow
              label="Mist-prone"
              hint="Mist forms here: valley floor, riverside, lake shore"
              checked={form.mistProne}
              onChange={(checked) => add.set('mistProne', checked)}
              testId="mist-prone-checkbox"
            />
          </div>
        </div>

        <div className={`${BLOCK} border-b-0`}>
          <div className="grid grid-cols-2 gap-3 max-[639px]:grid-cols-1">
            <Field label="Region" htmlFor="add-region">
              <RegionSelect
                id="add-region"
                regions={regions.filter((r) => r.enabled)}
                value={form.regionId}
                onChange={add.changeRegion}
                testId="add-region"
              />
            </Field>
            <SubRegionSelect
              id="add-sub-region"
              subRegions={subRegions}
              regionId={form.regionId}
              value={form.subRegionId}
              onChange={(value) => add.set('subRegionId', value)}
              canBeEmpty
              testId="add-sub-region"
            />
          </div>
          <FieldError message={unplaced} testId="location-add-error" />
        </div>
      </div>
    </PanelShell>
  );
}

LocationAddForm.propTypes = {
  add: PropTypes.object.isRequired,
  regions: PropTypes.arrayOf(PropTypes.object).isRequired,
  subRegions: PropTypes.arrayOf(PropTypes.object).isRequired,
  onSubmit: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
};
