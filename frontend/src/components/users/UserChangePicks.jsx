import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from './PanelShell.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import SubRegionPicker from '../subregions/SubRegionPicker.jsx';
import useConfirmDialog from '../../hooks/useConfirmDialog.js';
import { saveUserSubRegions } from '../../api/userApi.js';
import { apiErrorMessage } from '../../utils/apiError.js';

/**
 * Change picks (handoff B4): the panel body becomes the shared {@code SubRegionPicker} in edit mode at the
 * reader's limit. The admin's save goes through {@code PUT /api/users/{id}/sub-regions}, which skips the change
 * rule and clears the reader's lock, so the footer says so. Clear all picks confirms first, then PUTs an
 * empty list.
 *
 * @param {object} props
 * @param {{id: number, username: string}} props.user the reader
 * @param {import('../../api/subRegionApi.js').SubRegionState} props.state the admin GET (options, picks, limit)
 * @param {() => void} props.onClose closes the whole panel
 * @param {() => void} props.onCancel back to the reader view
 * @param {(state: object, toast: string) => void} props.onSaved told the new state and the toast to show
 */
export default function UserChangePicks({ user, state, onClose, onCancel, onSaved }) {
  const [value, setValue] = useState(() => state.picks.map((pick) => pick.areaId));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const { openDialog, closeDialog, dialogElement } = useConfirmDialog();

  async function persist(areaIds, toast) {
    setSaving(true);
    setError('');
    try {
      const next = await saveUserSubRegions(user.id, areaIds);
      onSaved(next, toast);
    } catch (err) {
      setError(apiErrorMessage(err, `Couldn't save ${user.username}'s picks. Try again.`));
    } finally {
      setSaving(false);
    }
  }

  function handleClearAll() {
    openDialog({
      title: `Clear all of ${user.username}'s picks?`,
      message: "They'll see no ratings until they choose again, and will be asked at their next sign-in.",
      confirmLabel: 'Clear picks',
      maxWidth: 'sm',
      onConfirm: () => {
        closeDialog();
        persist([], `Cleared all of ${user.username}'s picks.`);
      },
    });
  }

  const overLimit = value.length > state.limit;
  const footer = (
    <footer className="flex flex-col gap-3 border-t border-plex-border px-5 py-4">
      <p className="text-xs text-plex-text-secondary">
        Saving also clears their lock, so they can change their picks again straight away.
      </p>
      <div className="flex items-center justify-between gap-3">
        <button
          type="button"
          className="rounded-sm px-1 py-0.5 text-sm text-plex-coral hover:text-plex-coral-bright focus-visible:outline-2 focus-visible:outline-plex-gold disabled:opacity-40"
          onClick={handleClearAll}
          disabled={saving || state.picks.length === 0}
          data-testid="clear-all-picks"
        >
          Clear all picks
        </button>
        <div className="flex items-center gap-3">
          <button
            type="button"
            className="rounded-sm px-1 py-0.5 text-sm text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
            onClick={onCancel}
            disabled={saving}
            data-testid="change-picks-cancel"
          >
            Cancel
          </button>
          <button
            type="button"
            className="btn-primary"
            onClick={() => persist(value, `Saved ${user.username}'s picks. Their lock is cleared.`)}
            disabled={saving || value.length === 0 || overLimit}
            data-testid="save-picks"
          >
            {saving ? 'Saving…' : 'Save picks'}
          </button>
        </div>
      </div>
    </footer>
  );

  return (
    <>
      <PanelShell
        title={`Change ${user.username}'s picks`}
        subtitle={`${value.length} of ${state.limit} chosen`}
        onClose={onClose}
        testId="user-change-picks"
        footer={footer}
      >
        <div className="flex flex-col gap-3 px-5 py-4">
          <ErrorBanner message={error} data-testid="change-picks-error" />
          <SubRegionPicker
            options={state.options ?? []}
            value={value}
            limit={state.limit}
            onChange={setValue}
            showCount={false}
            testIdPrefix="admin-picker"
          />
        </div>
      </PanelShell>
      {dialogElement}
    </>
  );
}

UserChangePicks.propTypes = {
  user: PropTypes.shape({ id: PropTypes.number.isRequired, username: PropTypes.string.isRequired }).isRequired,
  state: PropTypes.shape({
    limit: PropTypes.number.isRequired,
    picks: PropTypes.arrayOf(PropTypes.object).isRequired,
    options: PropTypes.arrayOf(PropTypes.object),
  }).isRequired,
  onClose: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
};
