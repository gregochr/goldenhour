import PropTypes from 'prop-types';
import AuthHeading from '../auth/AuthHeading.jsx';
import ProTag from '../auth/ProTag.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import SubRegionPicker from './SubRegionPicker.jsx';
import { BUSY_BUTTON } from '../../utils/busyButton.js';
import { firstChoiceRuleLine, limitAsWord } from '../../utils/subRegionCopy.js';

/**
 * The info row's sentence (handoff A2, verbatim): "the three nearest to you" for a full set of
 * suggestions, "the nearest to you" when there are fewer than the limit.
 */
function suggestionLine(suggestedCount, limit) {
  const which = suggestedCount === limit ? `the ${limitAsWord(limit)} nearest` : 'the nearest';
  return `We've picked ${which} to you. Change them if you like.`;
}

/**
 * The first-choice card, shared by the registration {@code SUB_REGIONS} step (handoff A2) and the
 * sign-in prompt (A3): kicker, heading, sub, the suggestion row, the {@link SubRegionPicker}, the rule
 * line, the Pro line and the actions. It saves nothing: the owner PUTs on Continue.
 *
 * <p>Everything it says about dates comes from the server's state ({@code freeUntilIfChosenToday}); the
 * client does no date arithmetic. There is no link and no "upgrade" anywhere on it: the Pro line is a
 * tag and words.
 *
 * @param {object} props
 * @param {import('../../api/subRegionApi.js').SubRegionState} props.state the GET's answer
 * @param {number[]} props.selected the ids currently chosen (client state; nothing is saved yet)
 * @param {(ids: number[]) => void} props.onChange
 * @param {string} props.sub the sentence under the heading
 * @param {() => void} props.onContinue
 * @param {() => void} [props.onBack] omitted on the sign-in prompt: with no picks a Lite reader sees no
 *   ratings, so there is nothing to go back to
 * @param {string|null} [props.kicker] "Step 5 of 6" in the registration flow; absent on the prompt
 * @param {string} [props.error] a server refusal, shown above the buttons
 * @param {boolean} [props.busy] a save is in flight
 * @param {string} [props.testIdPrefix]
 */
export default function SubRegionStepCard({
  state, selected, onChange, sub, onContinue, onBack = null, kicker = null, error = '', busy = false,
  testIdPrefix = 'sub-region-step',
}) {
  const rule = firstChoiceRuleLine(state);
  const none = selected.length === 0;
  const hintId = `${testIdPrefix}-hint`;
  return (
    <div className="card p-5 flex flex-col gap-4" data-testid={testIdPrefix}>
      <div>
        {kicker && (
          <p
            className="font-mono text-[10px] uppercase tracking-[0.12em] text-plex-text-muted mb-1.5"
            data-testid={`${testIdPrefix}-kicker`}
          >
            {kicker}
          </p>
        )}
        <AuthHeading>Choose your sub-regions</AuthHeading>
        <p className="text-xs text-plex-text-secondary leading-normal mt-1">{sub}</p>
      </div>

      {/* Shown while the selection still holds a suggestion; once the reader has moved off all of them the
          line would be false. */}
      {state.suggested.some((id) => selected.includes(id)) && (
        <p
          className="flex items-start gap-2 rounded-lg border border-plex-border bg-plex-surface-light px-3 py-2 text-xs text-plex-text"
          data-testid={`${testIdPrefix}-suggested`}
        >
          <span className="text-plex-ok" aria-hidden="true">&#10003;</span>
          <span>{suggestionLine(state.suggested.length, state.limit)}</span>
        </p>
      )}

      <SubRegionPicker
        options={state.options}
        value={selected}
        limit={state.limit}
        onChange={onChange}
        testIdPrefix={`${testIdPrefix}-picker`}
      />

      {rule && (
        <p className="text-xs text-plex-text-secondary" data-testid={`${testIdPrefix}-rule`}>{rule}</p>
      )}

      <p className="flex items-baseline gap-2 text-xs text-plex-text-secondary" data-testid={`${testIdPrefix}-pro-line`}>
        <ProTag />
        <span>Pro covers every sub-region.</span>
      </p>

      <div role="alert">
        <ErrorBanner message={error} data-testid={`${testIdPrefix}-error`} />
      </div>

      <div className="flex flex-col gap-2.5">
        <button
          type="button"
          className={`btn-primary ${BUSY_BUTTON}`}
          disabled={none}
          aria-disabled={busy || undefined}
          aria-describedby={none ? hintId : undefined}
          onClick={() => { if (!busy) onContinue(); }}
          data-testid={`${testIdPrefix}-continue`}
        >
          {busy ? 'Saving…' : 'Continue'}
        </button>
        {none && (
          <p id={hintId} className="text-xs text-plex-text-secondary text-center -mt-1">
            Choose at least one to continue
          </p>
        )}
        {onBack && (
          <button
            type="button"
            className="text-xs text-plex-text-secondary hover:text-plex-text text-center py-1.5 -my-1.5"
            onClick={onBack}
            data-testid={`${testIdPrefix}-back`}
          >
            Back
          </button>
        )}
      </div>
    </div>
  );
}

SubRegionStepCard.propTypes = {
  state: PropTypes.shape({
    limit: PropTypes.number.isRequired,
    options: PropTypes.array.isRequired,
    suggested: PropTypes.arrayOf(PropTypes.number).isRequired,
  }).isRequired,
  selected: PropTypes.arrayOf(PropTypes.number).isRequired,
  onChange: PropTypes.func.isRequired,
  sub: PropTypes.string.isRequired,
  onContinue: PropTypes.func.isRequired,
  onBack: PropTypes.func,
  kicker: PropTypes.string,
  error: PropTypes.string,
  busy: PropTypes.bool,
  testIdPrefix: PropTypes.string,
};
