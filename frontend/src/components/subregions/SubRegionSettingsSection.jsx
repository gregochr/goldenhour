import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import PropTypes from 'prop-types';
import Modal from '../shared/Modal.jsx';
import BottomSheet from '../BottomSheet.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import ProTag from '../auth/ProTag.jsx';
import SubRegionPicker, { SubRegionCount } from './SubRegionPicker.jsx';
import SubRegionSaveConfirm from './SubRegionSaveConfirm.jsx';
import SubRegionToast from './SubRegionToast.jsx';
import { getSubRegions } from '../../api/subRegionApi.js';
import { persistSubRegions } from '../../utils/subRegionSave.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { useIsMobile } from '../../hooks/useIsMobile.js';
import { BUSY_BUTTON } from '../../utils/busyButton.js';
import {
  formatCivilDay, groupPicksByRegion, pickerApplies, SAVE_FAILED, savedToast, settingsStatusLine,
} from '../../utils/subRegionCopy.js';

const LOCKED_CODE = 'SUB_REGIONS_LOCKED';
const NOOP = () => {};

/** Whether two id lists hold the same ids, in any order. */
function sameSet(a, b) {
  return a.length === b.length && a.every((id) => b.includes(id));
}

/**
 * The Settings dialog's "Your sub-regions" section (sub-regions-plan §5.4 S4; design handoff A4, A5, A6).
 *
 * <p><b>Drawn only when the server says {@code enforced}</b> (plan D9): with the flag off it renders
 * nothing at all, for every role, so Settings is what it was. An Admin has no section and no request.
 * A Pro reader sees one line, "Pro covers every sub-region". A Lite reader sees their picks and the
 * status line for their status; Change (or View while locked) opens the picker, inline on desktop and in
 * a bottom sheet on a phone ({@code useIsMobile}), so desktop and tablet are unchanged by the phone work.
 *
 * <p>Every rule comes from the GET: status, dates, the limit, {@code overLimit}. The client only chooses
 * which of the server's states it is looking at. While locked with room under the limit the picker still
 * lets the reader add (plan §4.3: nothing is swapped out), passing the held picks as {@code lockedIds}.
 * Over a lowered limit it allows removals only.
 *
 * <p><b>The confirmation</b> appears only when the save will stamp a lock: status {@code OPEN} and at least
 * one pick removed. Its date is the server's {@code nextChangeOnIfSavedToday}. On desktop it is a stacked
 * {@code Modal} over Settings; on a phone it replaces the picker's own sheet content, so the page never
 * holds more than two dialogs.
 *
 * <p>After a successful PUT the SWR cache is cleared ({@code persistSubRegions}) and {@code onSaved} asks
 * the app to refetch the briefing and forecasts. A 409 {@code SUB_REGIONS_LOCKED} closes the dialog,
 * refetches, and the status line then states when the reader can next change.
 *
 * @param {object} props
 * @param {string} props.userRole the reader's role (from the settings read)
 * @param {(stacked: boolean) => void} [props.onStackedChange] told when a dialog opens over Settings, so
 *   Settings can mark itself stacked
 * @param {() => void} [props.onSaved] told after a successful save, so the app refetches
 */
export default function SubRegionSettingsSection({ userRole, onStackedChange = NOOP, onSaved = NOOP }) {
  const isMobile = useIsMobile();
  const [state, setState] = useState(null);
  const [expanded, setExpanded] = useState(false);
  const [draft, setDraft] = useState([]);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState(null);
  const openerRef = useRef(null);
  const returnFocus = useRef(false);
  const clearToast = useCallback(() => setToast(null), []);

  // One read on mount. An Admin has no section, so no request either.
  useEffect(() => {
    if (userRole === 'ADMIN') return undefined;
    let cancelled = false;
    getSubRegions()
      .then((answer) => {
        if (cancelled) return;
        setState(answer);
        setDraft(answer.status === 'FIRST_CHOICE' ? answer.suggested : answer.picks.map((p) => p.areaId));
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [userRole]);

  const applies = pickerApplies(state);
  const firstChoice = applies && state.status === 'FIRST_CHOICE';
  const sheetOpen = applies && isMobile && expanded && !firstChoice;
  const overlayOpen = confirming || sheetOpen;
  // Settings is told when a dialog is over it, so it can mark itself stacked. Through a ref: the callback
  // is the parent's and must not re-run this effect, which would flap the flag.
  const stackedRef = useRef(onStackedChange);
  useEffect(() => { stackedRef.current = onStackedChange; }, [onStackedChange]);
  useEffect(() => {
    stackedRef.current(overlayOpen);
    return () => stackedRef.current(false);
  }, [overlayOpen]);

  // Hand focus back to the opener once an inline picker closes.
  useEffect(() => {
    if (!expanded && returnFocus.current) {
      returnFocus.current = false;
      openerRef.current?.focus();
    }
  }, [expanded]);

  if (userRole === 'ADMIN' || !state || state.enforced !== true) return null;

  if (state.status === 'NOT_APPLICABLE') {
    return (
      <section data-testid="sub-regions-section">
        <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Your sub-regions</h3>
        <p className="flex items-baseline gap-2 text-sm text-plex-text-secondary" data-testid="sub-regions-pro-line">
          <ProTag />
          <span>Pro covers every sub-region.</span>
        </p>
      </section>
    );
  }

  const { picks, limit, options } = state;
  const pickIds = picks.map((p) => p.areaId);
  const overLimit = state.overLimit === true;
  const locked = state.status === 'LOCKED';
  const lockedAdd = locked && !overLimit && picks.length < limit;
  const readOnly = locked && !lockedAdd && !overLimit;
  const nextDay = formatCivilDay(state.nextChangeOn);
  const names = new Map(options.flatMap((r) => r.subRegions.map((s) => [s.areaId, s.name])));

  const removed = pickIds.filter((id) => !draft.includes(id));
  const added = draft.filter((id) => !pickIds.includes(id));
  // Over a lowered limit a save is a removal down to the limit or below; the server refuses more than the
  // limit, so Save waits until the draft is within it.
  const withinLimit = !overLimit || draft.length <= limit;
  const canSave = draft.length > 0 && withinLimit && !sameSet(draft, pickIds);
  // A save stamps a lock only from OPEN, and only when it swaps something out (plan §4.3).
  const needsConfirm = state.status === 'OPEN' && !overLimit && removed.length > 0;

  let statusLine = settingsStatusLine(state);
  if (expanded && lockedAdd && nextDay) {
    const more = limit - picks.length;
    statusLine = `You can add up to ${more} more now. Removing one waits until ${nextDay}.`;
  }

  const pickerOpen = firstChoice || expanded;
  // A locked reader's button is View even when adding is still allowed inside (the handoff's table).
  const buttonLabel = locked && !overLimit ? 'View' : 'Change';

  function openPicker() {
    setDraft(pickIds);
    setError('');
    setExpanded(true);
  }

  function closePicker() {
    setConfirming(false);
    setDraft(pickIds);
    setError('');
    returnFocus.current = true;
    setExpanded(false);
  }

  async function reread() {
    try {
      const answer = await getSubRegions();
      setState(answer);
      setDraft(answer.picks.map((p) => p.areaId));
      return true;
    } catch {
      // The status line stays as it was; the next open of Settings reads again.
      return false;
    }
  }

  async function doSave() {
    setBusy(true);
    setError('');
    try {
      const next = await persistSubRegions(draft);
      setState(next);
      setDraft(next.picks.map((p) => p.areaId));
      setConfirming(false);
      returnFocus.current = true;
      setExpanded(false);
      setToast(savedToast(next));
      onSaved();
    } catch (err) {
      setConfirming(false);
      const status = err?.response?.status;
      if (status === 409) {
        returnFocus.current = true;
        setExpanded(false);
        // Locked since the page loaded, or a concurrent change: read the state again. For the lock the
        // status line then says when the reader can next change; its own words stand in only if the
        // read fails too.
        const day = formatCivilDay(err.response.data?.nextChangeOn);
        const lockedNow = err.response.data?.code === LOCKED_CODE && day;
        const rereadOk = await reread();
        if (!lockedNow) setError(apiErrorMessage(err, SAVE_FAILED));
        else if (!rereadOk) setError(`You can next change these on ${day}.`);
      } else {
        setError(apiErrorMessage(err, SAVE_FAILED));
      }
    } finally {
      setBusy(false);
    }
  }

  function save() {
    if (!canSave || busy) return;
    if (needsConfirm) {
      setConfirming(true);
    } else {
      doSave();
    }
  }

  const picker = (
    <SubRegionPicker
      showCount={!sheetOpen}
      options={options}
      value={draft}
      limit={limit}
      onChange={setDraft}
      mode={readOnly ? 'read' : 'edit'}
      lockedIds={lockedAdd ? pickIds : []}
      lockedReason={nextDay ? `Removing one waits until ${nextDay}.` : ''}
      removalsOnly={overLimit}
      testIdPrefix="sub-regions-picker"
    />
  );

  const confirmContent = (stacked) => (
    <SubRegionSaveConfirm
      date={formatCivilDay(state.nextChangeOnIfSavedToday)}
      adding={added.map((id) => names.get(id)).filter(Boolean)}
      removing={removed.map((id) => names.get(id)).filter(Boolean)}
      onConfirm={doSave}
      onKeepEditing={() => setConfirming(false)}
      busy={busy}
      stacked={stacked}
    />
  );

  const actions = (
    <>
      {!firstChoice && (
        <button
          type="button"
          className="text-sm text-plex-text-secondary hover:text-plex-text rounded-sm py-1.5 focus-visible:outline-2 focus-visible:outline-plex-gold"
          onClick={closePicker}
          data-testid="sub-regions-cancel"
        >
          Cancel
        </button>
      )}
      <button
        type="button"
        className={`btn-primary text-sm ${BUSY_BUTTON}`}
        disabled={!canSave}
        aria-disabled={busy || undefined}
        onClick={save}
        data-testid="sub-regions-save"
      >
        {busy ? 'Saving…' : 'Save'}
      </button>
    </>
  );

  return (
    <section data-testid="sub-regions-section">
      <div className="flex items-center justify-between gap-3 mb-2">
        <h3 id="sub-regions-heading" className="text-xs font-medium text-plex-text-muted uppercase tracking-wide">
          Your sub-regions
        </h3>
        {!firstChoice && !(expanded && !isMobile) && (
          <button
            ref={openerRef}
            type="button"
            className="btn-secondary text-sm"
            onClick={openPicker}
            data-testid="sub-regions-open"
          >
            {buttonLabel}
          </button>
        )}
        {!firstChoice && expanded && !isMobile && readOnly && (
          <button type="button" className="btn-secondary text-sm" onClick={closePicker} data-testid="sub-regions-close">
            Close
          </button>
        )}
      </div>

      {!pickerOpen && picks.length > 0 && (
        <dl className="flex flex-col gap-1.5 mb-2" data-testid="sub-regions-picks">
          {groupPicksByRegion(picks).map((group) => (
            <div key={group.regionName} className="flex gap-4">
              <dt className="w-32 shrink-0 text-xs text-plex-text-muted pt-0.5">{group.regionName}</dt>
              <dd className="text-sm text-plex-text">{group.names.join(', ')}</dd>
            </div>
          ))}
        </dl>
      )}

      <div aria-live="polite">
        {statusLine && (
          <p className="text-xs text-plex-text-secondary" data-testid="sub-regions-status-line">{statusLine}</p>
        )}
      </div>

      {error && !sheetOpen && (
        <div role="alert" className="mt-2">
          <ErrorBanner message={error} data-testid="sub-regions-error" />
        </div>
      )}

      {pickerOpen && !isMobile && (
        <div className="mt-3 flex flex-col gap-3" data-testid="sub-regions-inline">
          {picker}
          {!readOnly && <div className="flex items-center justify-end gap-3">{actions}</div>}
        </div>
      )}
      {firstChoice && isMobile && (
        <div className="mt-3 flex flex-col gap-3" data-testid="sub-regions-inline">
          {picker}
          <div className="flex items-center justify-end gap-3">{actions}</div>
        </div>
      )}

      {sheetOpen && (
        <BottomSheet
          open
          onClose={closePicker}
          label="Your sub-regions"
          // The picker sheet is the house tall sheet (the viewport less 24px); the confirmation is a short
          // sheet sized to its content, at the bottom.
          size={confirming ? 'default' : 'tall'}
          closeOnEscape
          header={confirming ? null : (
            <div className="flex flex-col gap-1" data-testid="sub-regions-sheet-header">
              <h2 className="text-base font-semibold text-plex-text">Your sub-regions</h2>
              {statusLine && <p className="text-xs text-plex-text-secondary">{statusLine}</p>}
              <SubRegionCount count={draft.length} limit={limit} read={readOnly} testId="sub-regions-sheet-count" />
            </div>
          )}
          footer={confirming ? null : (
            <div className="flex gap-2 px-4 py-3 border-t border-plex-border" data-testid="sub-regions-sheet-footer">
              {readOnly ? (
                <button type="button" className="btn-secondary flex-1" onClick={closePicker} data-testid="sub-regions-close">
                  Close
                </button>
              ) : (
                <>
                  <button type="button" className="btn-secondary flex-1" onClick={closePicker} data-testid="sub-regions-cancel">
                    Cancel
                  </button>
                  <button
                    type="button"
                    className={`btn-primary flex-1 ${BUSY_BUTTON}`}
                    disabled={!canSave}
                    aria-disabled={busy || undefined}
                    onClick={save}
                    data-testid="sub-regions-save"
                  >
                    {busy ? 'Saving…' : 'Save'}
                  </button>
                </>
              )}
            </div>
          )}
        >
          {confirming ? (
            <div className="pt-2" data-testid="sub-regions-sheet-confirm">{confirmContent(true)}</div>
          ) : (
            <div className="flex flex-col gap-3 pt-2" data-testid="sub-regions-sheet">
              {error && (
                <div role="alert">
                  <ErrorBanner message={error} data-testid="sub-regions-sheet-error" />
                </div>
              )}
              {picker}
            </div>
          )}
        </BottomSheet>
      )}

      {/* Portalled to <body>: Settings goes inert while this is up, and a dialog inside its subtree would
          go inert with it. */}
      {confirming && !isMobile && createPortal(
        <Modal
          label="Save and lock"
          onClose={() => setConfirming(false)}
          closeOnEscape
          maxWidth="md"
          data-testid="sub-regions-confirm-modal"
        >
          {confirmContent(false)}
        </Modal>,
        document.body,
      )}

      <SubRegionToast message={toast} onDone={clearToast} />
    </section>
  );
}

SubRegionSettingsSection.propTypes = {
  userRole: PropTypes.string,
  onStackedChange: PropTypes.func,
  onSaved: PropTypes.func,
};
