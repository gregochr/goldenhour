import PropTypes from 'prop-types';
import { BUSY_BUTTON } from '../../utils/busyButton.js';

/** Keep editing, as a full-width secondary button (phone) or a text button beside Save and lock (desktop). */
const STACKED_KEEP = 'btn-secondary order-2 w-full';
const INLINE_KEEP = 'text-sm text-plex-text-secondary hover:text-plex-text rounded-sm py-1.5 focus-visible:outline-2 focus-visible:outline-plex-gold order-1';

/**
 * The save confirmation's content (handoff A5): the title naming the date the lock would end, the
 * body, the change list and the two buttons. Rendered inside a {@code Modal} on desktop and inside the
 * picker's own bottom sheet on a phone (so a phone never stacks a third dialog), hence the {@code stacked}
 * layout switch for full-width buttons.
 *
 * <p>The date is the server's ({@code nextChangeOnIfSavedToday}); nothing here adds days to a date.
 *
 * @param {object} props
 * @param {string|null} props.date the "D Month" the lock would end, or null if the server gave none
 * @param {string[]} props.adding names being added
 * @param {string[]} props.removing names being removed
 * @param {() => void} props.onConfirm
 * @param {() => void} props.onKeepEditing
 * @param {boolean} [props.busy]
 * @param {boolean} [props.stacked] full-width stacked buttons (phone)
 */
export default function SubRegionSaveConfirm({
  date, adding, removing, onConfirm, onKeepEditing, busy = false, stacked = false,
}) {
  const title = date ? `Save and lock until ${date}?` : 'Save and lock your sub-regions?';
  return (
    <div className="flex flex-col gap-4" data-testid="sub-region-confirm">
      <h2 className="font-serif font-semibold text-xl text-plex-text" data-testid="sub-region-confirm-title">
        {title}
      </h2>
      <p className="text-sm text-plex-text-secondary">
        You won&apos;t be able to change your sub-regions again until then.
      </p>
      <dl className="flex flex-col gap-1.5 text-sm" data-testid="sub-region-confirm-changes">
        {adding.length > 0 && (
          <div className="flex gap-4">
            <dt className="w-20 shrink-0 font-mono text-[11px] uppercase tracking-wider text-plex-text-muted pt-0.5">Adding</dt>
            <dd className="text-plex-text">{adding.join(', ')}</dd>
          </div>
        )}
        {removing.length > 0 && (
          <div className="flex gap-4">
            <dt className="w-20 shrink-0 font-mono text-[11px] uppercase tracking-wider text-plex-text-muted pt-0.5">Removing</dt>
            <dd className="text-plex-text">{removing.join(', ')}</dd>
          </div>
        )}
      </dl>
      <div className={stacked ? 'flex flex-col gap-2' : 'flex justify-end items-center gap-3'}>
        <button
          type="button"
          className={`btn-primary ${BUSY_BUTTON} ${stacked ? 'order-1 w-full' : 'order-2'}`}
          aria-disabled={busy || undefined}
          onClick={() => { if (!busy) onConfirm(); }}
          data-testid="sub-region-confirm-save"
        >
          {busy ? 'Saving…' : 'Save and lock'}
        </button>
        <button
          type="button"
          className={stacked ? STACKED_KEEP : INLINE_KEEP}
          onClick={onKeepEditing}
          data-testid="sub-region-confirm-keep"
        >
          Keep editing
        </button>
      </div>
    </div>
  );
}

SubRegionSaveConfirm.propTypes = {
  date: PropTypes.string,
  adding: PropTypes.arrayOf(PropTypes.string).isRequired,
  removing: PropTypes.arrayOf(PropTypes.string).isRequired,
  onConfirm: PropTypes.func.isRequired,
  onKeepEditing: PropTypes.func.isRequired,
  busy: PropTypes.bool,
  stacked: PropTypes.bool,
};
