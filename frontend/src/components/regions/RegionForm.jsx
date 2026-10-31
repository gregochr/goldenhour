import { useRef, useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import { PANEL_EDIT_FORM_ATTR } from '../../hooks/usePanelEscape.js';
import { addRegion, updateRegion, setRegionBase } from '../../api/regionApi.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { inputClass, labelClass, textButtonClass } from '../users/userFormClasses.js';

const PARTIAL_BASE = 'A base town needs a name, a latitude and a longitude — or leave all three blank.';

const TITLES = { add: 'Add region', rename: 'Rename region', base: 'Edit base' };

/**
 * The base form as the endpoint takes it, plus whether it differs from what is stored.
 *
 * <p>All three empty means "clear the base", which is a legitimate save. A partial base is rejected here as well as
 * on the backend, so the admin gets the message beside the fields rather than as a 400 after a round trip. The
 * values are STRINGS (including the two coordinates) because an empty field and a zero are different answers:
 * {@code Number('')} is 0, which would drop a base on the Gulf of Guinea the moment someone cleared a field.
 *
 * @param {{name: string, lat: string, lon: string}} fields the three inputs
 * @param {object} region the stored region
 */
function readBaseForm({ name: rawName, lat: rawLat, lon: rawLon }, region) {
  const name = rawName.trim();
  const latText = rawLat.trim();
  const lonText = rawLon.trim();
  if (!name && !latText && !lonText) {
    const changed = Boolean(region.baseName) || region.baseLat != null || region.baseLon != null;
    return { changed, body: { baseName: null, baseLat: null, baseLon: null } };
  }
  const lat = Number(latText);
  const lon = Number(lonText);
  if (!name || latText === '' || lonText === '' || !Number.isFinite(lat) || !Number.isFinite(lon)) {
    return { invalid: true };
  }
  const changed = name !== (region.baseName ?? '') || lat !== region.baseLat || lon !== region.baseLon;
  return { changed, body: { baseName: name, baseLat: lat, baseLon: lon } };
}

/**
 * The panel body for Add region, Rename and Edit base (plan §5.4): one purpose each, a pinned Cancel / Save footer.
 * Rename sends only the name, so a rename can never clear a base by omission; Edit base sends only the base, and only
 * when it changed, because moving the base discards that region's whole shared drive-time matrix (an ORS sweep), a
 * cost worth paying when the town moves and not worth paying to re-save. A base has to be all three fields or none.
 *
 * <p>⚠️ The list is re-read even when a save fails: the region may have changed under the form (a rename that landed
 * before a refusal, another admin's edit), and the list must not keep showing the old name under an error. The form
 * stays open on the failed field.
 *
 * @param {object} props
 * @param {'add'|'rename'|'base'} props.mode which form
 * @param {object} [props.region] the region being edited (rename, base)
 * @param {() => void} props.onClose closes the whole panel
 * @param {() => void} props.onCancel back to the region (or closes Add region)
 * @param {(saved: object|null) => (Promise<void>|void)} props.onSaved told after a successful save, with the saved
 *   region the server returned (add) so the new one can be selected
 * @param {() => Promise<void>} props.onRefresh re-reads the region list
 */
export default function RegionForm({ mode, region = undefined, onClose, onCancel, onSaved, onRefresh }) {
  const [name, setName] = useState(mode === 'rename' ? region.name : '');
  // `?? ''` rather than `|| ''`: a longitude of exactly 0 is Greenwich, and `||` would blank it.
  const [baseName, setBaseName] = useState(region?.baseName ?? '');
  const [baseLat, setBaseLat] = useState(region?.baseLat == null ? '' : String(region.baseLat));
  const [baseLon, setBaseLon] = useState(region?.baseLon == null ? '' : String(region.baseLon));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  // A ref as well as state: a second Enter lands before the re-render that would disable Save.
  const savingRef = useRef(false);

  const nameForm = mode !== 'base';
  const saveDisabled = saving || (nameForm && !name.trim());

  async function handleSave() {
    if (savingRef.current) return;
    if (nameForm && !name.trim()) {
      setError('Region name is required.');
      return;
    }
    let base = null;
    if (mode === 'base') {
      base = readBaseForm({ name: baseName, lat: baseLat, lon: baseLon }, region);
      if (base.invalid) {
        setError(PARTIAL_BASE);
        return;
      }
    }
    savingRef.current = true;
    setSaving(true);
    setError('');
    try {
      let saved = null;
      if (mode === 'add') saved = await addRegion({ name: name.trim() });
      else if (mode === 'rename') await updateRegion(region.id, { name: name.trim() });
      else if (base.changed) await setRegionBase(region.id, base.body);
      await onRefresh();
      await onSaved(saved);
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to save region.'));
      await onRefresh().catch(() => {});
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  const onEnter = (e) => { if (e.key === 'Enter') handleSave(); };

  const footer = (
    <div className="flex items-center justify-end gap-3 px-[18px] py-3 max-[639px]:px-4">
      <button type="button" className={textButtonClass} onClick={onCancel} disabled={saving} data-testid="region-form-cancel">
        Cancel
      </button>
      <button
        type="button"
        className="btn-primary max-[639px]:min-h-11"
        onClick={handleSave}
        disabled={saveDisabled}
        data-testid="save-region-btn"
      >
        {saving ? 'Saving…' : 'Save'}
      </button>
    </div>
  );

  return (
    <PanelShell
      title={TITLES[mode]}
      subtitle={mode === 'add' ? null : region.name}
      onClose={onClose}
      testIdPrefix="region-panel"
      testId={`region-form-${mode}`}
      footer={footer}
    >
      <div className="flex flex-col gap-4 px-[18px] py-4 max-[639px]:px-4" {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
        {nameForm && (
          <div>
            <label htmlFor="region-name" className={labelClass}>Region name</label>
            <input
              id="region-name"
              type="text"
              autoComplete="off"
              className={inputClass}
              placeholder="e.g. Northumberland"
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={onEnter}
              data-testid="region-name-input"
            />
          </div>
        )}

        {mode === 'base' && (
          <fieldset className="flex flex-col gap-2" data-testid="region-base-fields">
            <legend className={labelClass}>Base town — the origin the At a Glance tab can plan from</legend>
            <p className="text-xs text-plex-text-muted">
              The town a visitor would stay in, not the region&apos;s centre — a centroid is often offshore, and every
              drive time would then measure a journey nobody can make. Leave all three blank and the region cannot be
              an origin. Changing the coordinates discards this region&apos;s stored drive times; the nightly job
              recalculates them.
            </p>
            <input
              id="region-base-name"
              type="text"
              className={inputClass}
              placeholder="Base town, e.g. Keswick"
              aria-label="Base town name"
              value={baseName}
              onChange={(e) => setBaseName(e.target.value)}
              onKeyDown={onEnter}
              data-testid="region-base-name-input"
            />
            <div className="flex gap-2 max-[639px]:flex-col">
              <input
                id="region-base-lat"
                type="text"
                inputMode="decimal"
                className={inputClass}
                placeholder="Latitude, e.g. 54.6013"
                aria-label="Base latitude"
                value={baseLat}
                onChange={(e) => setBaseLat(e.target.value)}
                onKeyDown={onEnter}
                data-testid="region-base-lat-input"
              />
              <input
                id="region-base-lon"
                type="text"
                inputMode="decimal"
                className={inputClass}
                placeholder="Longitude, e.g. -3.1347"
                aria-label="Base longitude"
                value={baseLon}
                onChange={(e) => setBaseLon(e.target.value)}
                onKeyDown={onEnter}
                data-testid="region-base-lon-input"
              />
            </div>
          </fieldset>
        )}

        {error && <p className="text-xs text-plex-error" role="alert" data-testid="region-form-error">{error}</p>}
      </div>
    </PanelShell>
  );
}

RegionForm.propTypes = {
  mode: PropTypes.oneOf(['add', 'rename', 'base']).isRequired,
  region: PropTypes.object,
  onClose: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
  onRefresh: PropTypes.func.isRequired,
};
