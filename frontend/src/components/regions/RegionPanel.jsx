import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import PanelSection from '../data/PanelSection.jsx';
import KvList from '../data/KvList.jsx';
import { textButtonClass } from '../users/userFormClasses.js';
import { locationsText, regionMeta } from '../../utils/regionAdmin.js';

const ACTION_BUTTON = 'btn-secondary text-xs shrink-0 max-[639px]:min-h-11';

/** A Details value with its own text button on the right (Rename, Edit). */
function EditableValue({ children, actionLabel, onAction, testId }) {
  return (
    <span className="flex items-start justify-between gap-3">
      <span className="min-w-0">{children}</span>
      <button type="button" className={`${textButtonClass} shrink-0 py-0 text-xs`} onClick={onAction} data-testid={testId}>
        {actionLabel}
      </button>
    </span>
  );
}

EditableValue.propTypes = {
  children: PropTypes.node.isRequired,
  actionLabel: PropTypes.string.isRequired,
  onAction: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/** The base town as one line: the name and its coordinates, or "Not set" (a region without a base is ordinary). */
function BaseValue({ region }) {
  if (!region.baseName) return <span className="text-plex-text-muted">Not set</span>;
  return (
    <>
      {region.baseName}
      {region.baseLat != null && region.baseLon != null && (
        <span className="ml-2 font-mono text-[11.5px] text-plex-text-secondary">
          {region.baseLat}, {region.baseLon}
        </span>
      )}
    </>
  );
}

BaseValue.propTypes = { region: PropTypes.object.isRequired };

/** The one Region actions row: what the action does, and its button. */
function ActionRow({ title, hint, children }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <div className="min-w-0">
        <p className="text-sm text-plex-text">{title}</p>
        <p className="text-xs text-plex-text-secondary">{hint}</p>
      </div>
      {children}
    </div>
  );
}

ActionRow.propTypes = { title: PropTypes.string.isRequired, hint: PropTypes.string.isRequired, children: PropTypes.node };

/**
 * The region panel (plan §5.4): Details (Name, Base), Sub-regions and Region actions.
 *
 * <p>Region actions are honest about the server rule (D3): a region with switched-on locations cannot be turned
 * off, so the button is disabled and says why, with a link to those locations. A region with none turns off at
 * once, with no dialog (nothing is hidden from a reader that was not already). A failed toggle has already
 * reverted in the parent; its sentence (the server's, e.g. a 409 race) shows under the action.
 *
 * @param {object} props
 * @param {object} props.region the region row with its counts ({@code buildRegionRows})
 * @param {() => void} props.onClose closes the panel
 * @param {() => void} props.onRename opens the Rename form
 * @param {() => void} props.onEditBase opens the Edit base form
 * @param {(region: object) => void} props.onToggle turns the region on or off
 * @param {boolean} props.toggling a toggle is in flight
 * @param {string} props.toggleError the server's sentence for the last refused toggle of this region
 */
export default function RegionPanel({ region, onClose, onRename, onEditBase, onToggle, toggling, toggleError }) {
  const blocked = region.enabled && region.enabledLocationCount > 0;
  const n = region.enabledLocationCount;

  let action;
  if (!region.enabled) {
    action = (
      <ActionRow title="Turn on" hint="It comes back to the region pickers, Ask and the Plan's starting points.">
        <button type="button" className={ACTION_BUTTON} onClick={() => onToggle(region)} disabled={toggling} data-testid={`toggle-region-enabled-${region.id}`}>
          Turn on
        </button>
      </ActionRow>
    );
  } else if (blocked) {
    action = (
      <>
        <ActionRow title="Turn off" hint={`Turn its ${n} ${n === 1 ? 'location' : 'locations'} off first.`}>
          <button type="button" className={ACTION_BUTTON} disabled data-testid={`toggle-region-enabled-${region.id}`}>
            Turn off
          </button>
        </ActionRow>
        <a
          href={`#manage/locations?region=${region.id}`}
          className="inline-flex min-h-9 items-center text-xs text-plex-gold underline hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
          data-testid="region-show-locations"
        >
          Show them
        </a>
      </>
    );
  } else {
    action = (
      <ActionRow title="Turn off" hint="It leaves the region pickers, Ask and the Plan's starting points.">
        <button type="button" className={ACTION_BUTTON} onClick={() => onToggle(region)} disabled={toggling} data-testid={`toggle-region-enabled-${region.id}`}>
          Turn off
        </button>
      </ActionRow>
    );
  }

  return (
    <PanelShell
      title={region.name}
      subtitle={regionMeta(region)}
      onClose={onClose}
      testIdPrefix="region-panel"
      testId="region-reader-panel"
    >
      <PanelSection label="Details" testId="region-details">
        <KvList
          items={[
            {
              label: 'Name',
              value: (
                <EditableValue actionLabel="Rename" onAction={onRename} testId={`rename-region-${region.id}`}>
                  {region.name}
                </EditableValue>
              ),
            },
            {
              label: 'Base',
              testId: `region-base-${region.id}`,
              value: (
                <EditableValue actionLabel="Edit" onAction={onEditBase} testId={`edit-region-base-${region.id}`}>
                  <BaseValue region={region} />
                </EditableValue>
              ),
            },
          ]}
        />
      </PanelSection>

      <PanelSection
        label="Sub-regions"
        testId="region-sub-regions"
        action={(
          <span className="font-mono text-[11px] text-plex-text-secondary" data-testid="region-sub-regions-summary">
            {region.subRegionCount} · {locationsText(region.locationCount)}
          </span>
        )}
      >
        {region.subRegions.length === 0 ? (
          <p className="text-sm text-plex-text-muted" data-testid="region-no-sub-regions">No sub-regions.</p>
        ) : (
          <ul className="flex flex-col">
            {region.subRegions.map((sub) => (
              <li key={sub.id} className="flex items-baseline justify-between gap-3 py-1 text-[13px] text-plex-text" data-testid={`region-sub-region-${sub.id}`}>
                <span className="min-w-0 truncate">{sub.name}</span>
                <span className="shrink-0 font-mono text-[11.5px] text-plex-text-secondary">{locationsText(sub.locationCount)}</span>
              </li>
            ))}
          </ul>
        )}
      </PanelSection>

      <PanelSection label="Region actions" testId="region-actions">
        {action}
        {toggleError && (
          <p role="alert" className="mt-1 text-xs text-plex-error" data-testid="region-toggle-error">{toggleError}</p>
        )}
      </PanelSection>
    </PanelShell>
  );
}

RegionPanel.propTypes = {
  region: PropTypes.object.isRequired,
  onClose: PropTypes.func.isRequired,
  onRename: PropTypes.func.isRequired,
  onEditBase: PropTypes.func.isRequired,
  onToggle: PropTypes.func.isRequired,
  toggling: PropTypes.bool,
  toggleError: PropTypes.string,
};
