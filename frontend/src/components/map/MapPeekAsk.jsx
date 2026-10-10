import { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import { SETTLED_PHASES } from '../../utils/askModel.js';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';
import AskConversation from '../ask/AskConversation.jsx';
import AskInputRow from '../ask/AskInputRow.jsx';
import { objectRefShape, planActionsShape } from '../ask/askShapes.js';

/** The collapsed row's prompt (design `Map Ask in Peek.html`: "Ask about what’s on the map…"). */
export const PEEK_ASK_PROMPT = 'Ask about what’s on the map…';

/** The view chip's words before the Map pane has published a context (no briefing yet). */
const FALLBACK_VIEW_LABEL = 'Map · all regions';

/** "1 pick" / "3 picks". */
const picksWord = (n) => (n === 1 ? '1 pick' : `${n} picks`);

/**
 * Ask PhotoCast's row of the phone peek sheet, and everything that hangs off it
 * (`docs/engineering/ask-photocast-plan.md` §2.7's phone paragraph, F4): the top row of
 * {@code MapPeekSheet}, the Ask section's body, and the one-line minimised answer.
 *
 * <p>It is a sheet PART, not a surface of its own: {@code MapView} owns every open/closed decision
 * (the one gate, {@code openMapMenu}) and tells this component the {@code mode} the sheet is in
 * ({@code utils/askPeek.js}); this component draws that mode and reports the reader's presses.
 * <ul>
 *   <li><b>collapsed / section / minimised</b> — the row is a BUTTON, "ASK · Ask about what’s on the
 *       map… ↑", that opens the Ask section. A refusal's sentence ({@code inputError}) that arrived while
 *       the section was closed and has not been seen is shown in its place: the conversation is not
 *       mounted then ({@code AskConversation} renders the sentence itself while it is open, so the
 *       field never does). Minimised adds the one line under it.</li>
 *   <li><b>expanded</b> — the row holds the question field ({@code AskInputRow}, the same piece the
 *       sheet and the dock repeat) and a ✕; the conversation fills the body below it.</li>
 * </ul>
 * The ✕ ends what is settled and closes the section ("clear answer, restore the buttons"), or just
 * closes it while there is nothing settled to clear — never while an answer is still being fetched,
 * since clearing then would drop a charged answer (the rule {@code AskClearAnswer} keeps).
 *
 * <h2>Focus never falls to {@code <body>}</h2>
 * <p>Three controls here unmount when pressed — the entry button and the minimised line (the field
 * takes their place) and the field and ✕ (the entry button takes theirs) — and the map's Escape rule,
 * which runs only while focus is inside the pane, goes quiet once focus is on {@code <body>}: the
 * defect the Map's panels had five times. So: opening focuses the field when there is nothing to read
 * and the pane's own node ({@code tabIndex -1}, a programmatic-only target) when there is, only when
 * focus has actually been lost (a pick chip that was pressed keeps it); and everything that closes the
 * section from inside it first parks focus on that same node, and an effect hands it on to the entry
 * button once the button exists. Escape parks through a native listener, which runs before the pane's
 * own handler closes the section.
 *
 * <p>The field is mounted only while the section is open, so a typed draft does not outlive it — the
 * sheet's and the dock's rule too.
 *
 * @param {object} props
 * @param {'collapsed'|'expanded'|'minimised'|'section'} props.mode {@code askPeekMode}'s answer
 * @param {function(): void} props.onOpen opens the Ask section
 * @param {function(): void} props.onClose closes it (the answer, if any, is kept: minimised)
 * @param {{current: ?HTMLElement}} props.entryRef the entry button's node — the sheet's other
 *        focus-return routes (the Regions and Filters sheets, which close back onto the Layers button
 *        and find it gone while an answer has replaced the buttons) fall back to it
 * @param {{openInPlan: ?function(object): void, setPostcode: ?function(): void}} [props.planActions]
 *        the doors out of "Plan this" (F5): {@code MapView}'s, since the shell's own are out of the
 *        pane's reach. "Open in Plan ›" moves the tab, so the pane goes hidden and the section closes
 */
export default function MapPeekAsk({
  mode, onOpen, onClose, entryRef, planActions = undefined,
}) {
  const ask = useAsk();
  const wrapRef = useRef(null);
  const inputRef = useRef(null);
  const wasExpanded = useRef(false);
  const expanded = mode === 'expanded';
  const {
    phase, typedDisabled, availability, inputError,
  } = ask;
  const settled = SETTLED_PHASES.includes(phase);
  // ⚠️ A refusal's sentence is shown in the collapsed row only while the reader has not yet SEEN it. The
  // conversation, while the section is open, renders it itself (and announces it); `inputError` then
  // lives on in the provider until the next question, so a row that mirrored it unconditionally would
  // keep the sentence where the prompt belongs long after it was read, and announce it a second time on
  // every collapse. "Seen" is whatever the open conversation last showed, forgotten when the sentence
  // goes (so the same refusal twice is two refusals). State adjusted during render, the documented form.
  const [seenError, setSeenError] = useState(null);
  if (expanded && seenError !== inputError) setSeenError(inputError);
  if (!inputError && seenError !== null) setSeenError(null);
  const unseenError = !expanded && inputError && inputError !== seenError ? inputError : null;

  /** Focus to a node that survives the control being pressed. */
  const park = useCallback(() => wrapRef.current?.focus({ preventScroll: true }), []);

  // Opening: land somewhere deliberate when the pressed control has taken focus with it. Closing: hand
  // a parked focus on to the entry button, which exists by now.
  useEffect(() => {
    if (expanded && !wasExpanded.current) {
      const active = document.activeElement;
      if (!active || active === document.body) {
        if (phase === 'empty' && !typedDisabled) inputRef.current?.focus({ preventScroll: true });
        else park();
      }
    }
    if (!expanded && wasExpanded.current && document.activeElement === wrapRef.current) {
      entryRef.current?.focus({ preventScroll: true });
    }
    wasExpanded.current = expanded;
    // `phase`/`typedDisabled` are read at the moment of opening only; the transition is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded]);

  // A native listener, not a React prop: the a11y lint refuses key handlers on a non-interactive node,
  // and a native one runs before React's own root listener, i.e. before `MapView`'s pane handler has
  // closed the section and unmounted the field that holds focus.
  useEffect(() => {
    const node = wrapRef.current;
    if (!node) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape' || event.isComposing || !wasExpanded.current) return;
      // The pane's own handler stands down for a dialog over the map, and then nothing closes the section:
      // moving focus off the field for a close that is not coming would only take it from the reader.
      if (foreignModalOverPaneOf(node)) return;
      park();
    };
    node.addEventListener('keydown', onKeyDown);
    return () => node.removeEventListener('keydown', onKeyDown);
  }, [park]);

  const closeSection = () => {
    park();
    // Only what is settled is cleared: an answer still being fetched is a charged answer.
    if (settled) ask.clear();
    onClose();
  };

  return (
    <div
      ref={wrapRef}
      tabIndex={-1}
      className="wf-map-peek-ask"
      data-testid="wf-map-peek-ask"
      data-mode={mode}
    >
      <div className="wf-map-peek-ask-row">
        {expanded ? (
          <>
            <div className="wf-map-peek-ask-field" data-testid="wf-map-peek-ask-field">
              <AskInputRow inputRef={inputRef} view="map" viewLabel={FALLBACK_VIEW_LABEL} />
            </div>
            <button
              type="button"
              className="wf-map-peek-ask-x"
              data-testid="wf-map-peek-ask-x"
              aria-label={settled ? (ask.history.length > 0 ? 'Clear' : 'Clear answer') : 'Close Ask'}
              onClick={closeSection}
            >
              <span aria-hidden="true">✕</span>
            </button>
          </>
        ) : (
          <button
            ref={entryRef}
            type="button"
            className="wf-map-peek-ask-entry"
            data-testid="wf-map-peek-ask-entry"
            aria-expanded="false"
            aria-label={unseenError ? `${PEEK_ASK_PROMPT} ${unseenError}` : undefined}
            disabled={availability === 'down'}
            onClick={onOpen}
          >
            <span className="wf-map-peek-ask-k" aria-hidden="true">Ask</span>
            <span
              className="wf-map-peek-ask-q"
              data-error={unseenError ? 'true' : undefined}
              data-testid="wf-map-peek-ask-q"
            >
              {unseenError ?? PEEK_ASK_PROMPT}
            </span>
            <span className="wf-map-peek-ask-go" aria-hidden="true">↑</span>
          </button>
        )}
      </div>
      {expanded && (
        <div
          className="wf-map-peek-ask-body"
          data-testid="wf-map-peek-ask-body"
        >
          <AskConversation
            view="map"
            viewLabel={FALLBACK_VIEW_LABEL}
            planActions={planActions}
          />
        </div>
      )}
      {mode === 'minimised' && <MinimisedLine ask={ask} onOpen={onOpen} />}
      {/* The refusal's sentence, said once: while the section is open the conversation's own region
          carries it (and a sentence it showed is not said again here), so this one holds only a refusal
          that arrived while the section was closed. Mounted empty and always, so a fill is a change. */}
      <span role="status" className="sr-only" data-testid="wf-map-peek-ask-status">
        {unseenError ?? ''}
      </span>
    </div>
  );
}

MapPeekAsk.propTypes = {
  mode: PropTypes.oneOf(['collapsed', 'expanded', 'minimised', 'section']).isRequired,
  onOpen: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  entryRef: objectRefShape.isRequired,
  planActions: planActionsShape,
};

/**
 * The 112px state's one line (design State 6): the chosen pick's rank, spot and time and "N picks ▴"
 * — or, for an answer with no picks, the not-in-the-forecast reply or the failure, in a line of their
 * own words. A button: tapping it opens the answer. Every word is read off the cards the conversation
 * already holds (served facts, joined by {@code buildPickCards}); nothing is derived here.
 */
function MinimisedLine({ ask, onOpen }) {
  const {
    phase, pickCards, selectedPick, planPick, answer, error,
  } = ask;
  if ((phase === 'answer' || phase === 'plan') && pickCards.length > 0) {
    const shown = phase === 'plan' ? planPick : selectedPick;
    const card = pickCards.find((c) => c.rank === shown) ?? pickCards[0];
    const when = [card.shortWindow, card.eventTime].filter(Boolean).join(' ');
    // In the plan view the line says what the reader was doing, not how many picks there are (F5).
    const count = phase === 'plan' ? 'Plan this' : picksWord(pickCards.length);
    return (
      <button
        type="button"
        className="wf-map-peek-mini"
        data-testid="wf-map-peek-mini"
        aria-expanded="false"
        aria-label={`Pick ${card.rank}, ${card.name}${when ? `, ${when}` : ''}. ${count}. Show the ${phase === 'plan' ? 'plan' : 'answer'}`}
        onClick={onOpen}
      >
        <span className="wf-map-peek-mini-rk" aria-hidden="true">{card.rank}</span>
        <b className="wf-map-peek-mini-name" aria-hidden="true">{card.name}</b>
        {when && <span className="wf-map-peek-mini-when" aria-hidden="true">{when}</span>}
        <span className="wf-map-peek-mini-n" aria-hidden="true">{`${count} ▴`}</span>
      </button>
    );
  }
  let text = 'Answer';
  if (phase === 'cant') text = 'Not in the forecast';
  else if (phase === 'error') text = error.message;
  else if (typeof answer?.summary === 'string' && answer.summary !== '') text = answer.summary;
  return (
    <button
      type="button"
      className="wf-map-peek-mini"
      data-testid="wf-map-peek-mini"
      aria-expanded="false"
      onClick={onOpen}
    >
      <span className="wf-map-peek-mini-name wf-map-peek-mini-text">{text}</span>
      <span className="wf-map-peek-mini-n" aria-hidden="true">▴</span>
    </button>
  );
}

MinimisedLine.propTypes = {
  ask: PropTypes.shape({
    phase: PropTypes.string,
    pickCards: PropTypes.arrayOf(PropTypes.object),
    selectedPick: PropTypes.number,
    planPick: PropTypes.number,
    answer: PropTypes.object,
    error: PropTypes.object,
  }).isRequired,
  onOpen: PropTypes.func.isRequired,
};
