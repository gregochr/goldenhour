import {
  useCallback, useEffect, useId, useLayoutEffect, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import { useAsk } from '../../context/AskContext.jsx';
import { ASK_PROMPT, SETTLED_PHASES } from '../../utils/askModel.js';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';
import { NEW_CONVERSATION_HINT, NEW_CONVERSATION_LABEL } from '../ask/AskClearAnswer.jsx';
import { AskUpsellContent } from '../ask/AskUpsell.jsx';
import AskConversation from '../ask/AskConversation.jsx';
import AskInputRow from '../ask/AskInputRow.jsx';
import { objectRefShape, planActionsShape } from '../ask/askShapes.js';

/** The Ask button's accessible name: the one Ask prompt, without its ellipsis. */
export const PEEK_ASK_LABEL = ASK_PROMPT.replace('…', '');

/** The id the Ask button's {@code aria-controls} names (S6): the Ask section's body. */
export const PEEK_ASK_BODY_ID = 'wf-map-peek-ask-body';

/** The view chip's words before the Map pane has published a context (no briefing yet). */
const FALLBACK_VIEW_LABEL = 'Map · all regions';

/** "1 pick" / "3 picks". */
const picksWord = (n) => (n === 1 ? '1 pick' : `${n} picks`);

/**
 * A refusal's sentence that arrived while the Ask section was closed and has not been seen — shared by
 * the Ask button (its dot and name) and the status region, which live in different parts of the sheet.
 *
 * <p>⚠️ The conversation, while the section is open, renders the sentence itself (and announces it);
 * {@code inputError} then lives on in the provider until the next question, so a button that mirrored it
 * unconditionally would keep the dot long after it was read, and announce it a second time on every
 * collapse. "Seen" is whatever the open conversation last showed, forgotten when the sentence goes (so the
 * same refusal twice is two refusals). State adjusted during render, the documented form; both callers
 * receive the same {@code expanded} and the same context, so their copies never disagree.
 *
 * @param {boolean} expanded whether the Ask section is open
 * @param {?string} inputError {@code AskContext.inputError}
 * @returns {?string} the unseen sentence, or null
 */
function useUnseenRefusal(expanded, inputError) {
  const [seenError, setSeenError] = useState(null);
  if (expanded && seenError !== inputError) setSeenError(inputError);
  if (!inputError && seenError !== null) setSeenError(null);
  return !expanded && inputError && inputError !== seenError ? inputError : null;
}

/**
 * The Ask peek button — the third of the sheet's four (Other windows · Tide · Ask · Layers;
 * `docs/engineering/map-mobile-compact-plan.md` C2, D-6, S4–S8). 44px wide, key {@code ASK}, glyph ✦.
 *
 * <p>Drawn by this module (it reads Ask's availability, lock and refusal) and placed by
 * {@code MapPeekSheet} through its {@code askButton} slot. It toggles: closed it opens the Ask section, open
 * it closes it ({@code MapView}'s {@code onPress}), like its siblings. It focuses itself on press (S2).
 * <ul>
 *   <li>{@code disabled} while Ask is unreachable ({@code 'down'}), as the old entry row was (S5);</li>
 *   <li>a refusal that arrived while the section was closed: {@code data-error="true"} (a small coral dot)
 *       and the sentence in its {@code aria-label} (S4); the status region still announces it once;</li>
 *   <li>LITE (locked): a 44px box cannot carry the "Pro" pill, so it is drawn muted ({@code data-locked})
 *       yet stays pressable and opens the worded upsell section; {@code aria-label} "Ask PhotoCast, Pro"
 *       (S8).</li>
 * </ul>
 *
 * @param {object} props
 * @param {'collapsed'|'expanded'|'minimised'|'section'} props.mode {@code askPeekMode}'s answer
 * @param {function(): void} props.onPress toggles the Ask section
 * @param {{current: ?HTMLElement}} props.buttonRef the button's node — where the Ask section hands focus
 *        back to when it closes onto the four buttons, and the sheet's focus fallback
 */
export function MapPeekAskButton({ mode, onPress, buttonRef }) {
  const { availability, inputError } = useAsk();
  const expanded = mode === 'expanded';
  const locked = availability === 'locked';
  const unseenError = useUnseenRefusal(expanded, inputError);
  let label = PEEK_ASK_LABEL;
  if (locked) label = `${PEEK_ASK_LABEL}, Pro`;
  else if (unseenError) label = `${PEEK_ASK_LABEL}. ${unseenError}`;
  return (
    <button
      ref={buttonRef}
      type="button"
      data-testid="wf-map-peek-btn-ask"
      className={`wf-map-peek-btn wf-map-peek-btn-ask${expanded ? ' wf-map-peek-btn-on' : ''}`}
      aria-expanded={expanded}
      aria-controls={PEEK_ASK_BODY_ID}
      aria-label={label}
      data-locked={locked ? 'true' : undefined}
      data-error={unseenError && !locked ? 'true' : undefined}
      disabled={availability === 'down'}
      onClick={(event) => {
        // Safari (and iOS) do not focus a button a pointer pressed (S2): take focus, so the field that an
        // expanded section is about to unmount does not take it to <body>, and a close lands here.
        event.currentTarget.focus({ preventScroll: true });
        onPress();
      }}
    >
      <span className="wf-map-peek-k">ASK</span>
      <span className="wf-map-peek-v" aria-hidden="true">&#10022;</span>
    </button>
  );
}

MapPeekAskButton.propTypes = {
  mode: PropTypes.oneOf(['collapsed', 'expanded', 'minimised', 'section']).isRequired,
  onPress: PropTypes.func.isRequired,
  buttonRef: objectRefShape.isRequired,
};

/**
 * Reports, from its layout-effect CLEANUP, that the expanded Ask content unmounted while a control inside it
 * held focus (S2): the field, the ✕, a pick card, a link. The cleanup of a component that is being deleted
 * runs while its host nodes are still in the document, so {@code document.activeElement} is still the
 * control; a cleanup on {@code MapPeekAsk} itself would run after the deletion. Focus parked on the Ask
 * pane's own node is not "lost" (it is the deliberate hand-off), nor is a peek button the reader pressed.
 */
function FocusLossGuard({ onLost }) {
  const report = useRef(onLost);
  useEffect(() => { report.current = onLost; });
  useLayoutEffect(() => () => {
    const active = document.activeElement;
    const pane = active?.closest?.('.wf-map-peek-ask');
    if (pane && active !== pane) report.current?.();
  }, []);
  return null;
}

FocusLossGuard.propTypes = {
  onLost: PropTypes.func.isRequired,
};

/**
 * Ask PhotoCast's part of the phone peek sheet, under the button row
 * (`docs/engineering/ask-photocast-plan.md` §2.7's phone paragraph, F4; `map-mobile-compact-plan.md` C2):
 * the Ask section's body and the one-line minimised answer. The Ask BUTTON is {@link MapPeekAskButton}.
 *
 * <p>It is a sheet PART, not a surface of its own: {@code MapView} owns every open/closed decision
 * (the one gate, {@code openMapMenu}) and tells this component the {@code mode} the sheet is in
 * ({@code utils/askPeek.js}); this component draws that mode and reports the reader's presses.
 * <ul>
 *   <li><b>collapsed / section</b> — nothing but the status region, which stays mounted. A refusal's
 *       sentence ({@code inputError}) that arrived while the section was closed and has not been seen is said
 *       there once, and shown on the Ask button as a dot: the conversation is not mounted then
 *       ({@code AskConversation} renders the sentence itself while it is open, so the field never does).</li>
 *   <li><b>expanded</b> — under the four buttons, the field row ({@code AskInputRow}, the same piece the
 *       sheet and the dock repeat, and a ✕), then the conversation filling the body (D-5).</li>
 *   <li><b>minimised</b> — the one line, in place of the four buttons (D-4).</li>
 * </ul>
 * The ✕ ends what is settled ("New conversation") and closes the section, or just
 * closes it while there is nothing settled to clear — never while an answer is still being fetched,
 * since clearing then would drop a charged answer (the rule {@code AskClearAnswer} keeps).
 *
 * <h2>Focus never falls to {@code <body>}</h2>
 * <p>Controls here unmount when pressed or when the section closes — the field, the ✕ and the pick cards
 * (the four buttons are already there), the minimised line and its Layers door (the field or the Layers
 * section takes their place) — and the map's Escape rule, which runs only while focus is inside the pane,
 * goes quiet once focus is on {@code <body>}: the defect the Map's panels had five times. So:
 * <ul>
 *   <li><b>Opening</b> focuses the field whatever holds focus when there is nothing to read (S3: the Ask
 *       button stays mounted and pressed, so "only when focus was lost" would never fire), and the pane's
 *       own node ({@code tabIndex -1}, a programmatic-only target) when there is something settled, only
 *       when focus has actually been lost. Locked: the upsell's heading.</li>
 *   <li><b>Closing from inside</b> (✕, Not now, Escape) first parks focus on the pane's node, and an effect
 *       hands it on by DESTINATION (S1): to the minimised line's Layers door when the answer is what remains
 *       (the Ask button is not mounted then), to the Ask button when the four buttons are. Escape parks
 *       through a native listener, which runs before the pane's own handler closes the section.</li>
 *   <li><b>Switching away</b> (a Windows, Tide or Layers press, S2) is covered by each peek button focusing
 *       itself on press; the {@code FocusLossGuard} is the net for every route that is not a press (a pick
 *       chip, a tab switch), handing focus on by the same destination.</li>
 * </ul>
 *
 * <p>The door is the one control whose focus goes ELSEWHERE: pressed, it hands focus to the Layers peek
 * button (the section's own button, mounted in the same commit — a later sibling, so its ref is not yet
 * attached when this component's LAYOUT effects run, hence a passive effect), and a door unmounted by anything
 * else while it held focus (a typed question going busy) hands it to the Ask button. Both only when focus has
 * actually been lost.
 *
 * <p>The field is mounted only while the section is open, so a typed draft does not outlive it — the
 * sheet's and the dock's rule too.
 *
 * <h2>Locked (a LITE reader): the section is the upsell</h2>
 * <p>The Ask button is live like PRO's (drawn muted, S8) and pressing it opens the same
 * section ({@code 'peek:ask'}); {@code MapPeekAsk} then draws {@code AskUpsellContent} inline, as the phone
 * Tide section does, in place of the field and the conversation (none of those mounts, so no
 * {@code /api/ask*} request can be made) and a {@code Not now} that closes it. There is no ✕. Focus goes
 * to the heading on open, and every close parks it on the pane's node so it ends on the Ask button.
 * (`docs/engineering/ask-upsell-plan.md` §4.4.)
 *
 * @param {object} props
 * @param {'collapsed'|'expanded'|'minimised'|'section'} props.mode {@code askPeekMode}'s answer
 * @param {function(): void} props.onOpen opens the Ask section (the minimised line's press)
 * @param {function(): void} props.onClose closes it (the answer, if any, is kept: minimised)
 * @param {function(): void} props.onOpenLayers the minimised line's Layers door: opens the Layers section
 *        with the answer kept ({@code MapView} clears a standing callout first, as the Layers button does)
 * @param {{current: ?HTMLElement}} props.doorRef the door's node — where focus returns when Layers,
 *        Regions or Filters close back onto the minimised line
 * @param {{current: ?HTMLElement}} props.layersRef the Layers peek button's node — where focus goes when
 *        the door is pressed
 * @param {{current: ?HTMLElement}} props.buttonRef the Ask peek button's node ({@link MapPeekAskButton}) —
 *        where focus goes when the section closes onto the four buttons
 * @param {{openInPlan: ?function(object): void, setPostcode: ?function(): void}} [props.planActions]
 *        the doors out of "Plan this" (F5): {@code MapView}'s, since the shell's own are out of the
 *        pane's reach. "Open in Plan ›" moves the tab, so the pane goes hidden and the section closes
 */
export default function MapPeekAsk({
  mode, onOpen, onClose, onOpenLayers, buttonRef, doorRef, layersRef, planActions = undefined,
}) {
  const ask = useAsk();
  const wrapRef = useRef(null);
  const hintId = useId();
  const inputRef = useRef(null);
  const headingRef = useRef(null);
  const wasExpanded = useRef(false);
  const expanded = mode === 'expanded';
  const {
    phase, typedDisabled, availability, inputError,
  } = ask;
  const locked = availability === 'locked';
  const settled = SETTLED_PHASES.includes(phase);
  const unseenError = useUnseenRefusal(expanded, inputError);

  /** Focus to a node that survives the control being pressed. */
  const park = useCallback(() => wrapRef.current?.focus({ preventScroll: true }), []);

  /**
   * Where focus goes when the section is gone (S1), by DESTINATION: the minimised line's door when the answer
   * is what remains (the Ask button is not mounted then), the Ask button when the four buttons are (the
   * Layers button when Ask is unreachable and so disabled, which cannot take focus).
   */
  const handOnTarget = () => {
    if (mode === 'minimised') return doorRef.current;
    const button = buttonRef.current;
    return button && !button.disabled ? button : layersRef.current;
  };

  // Opening: land somewhere deliberate (S3). Closing: hand a parked focus on to the destination.
  useEffect(() => {
    if (expanded && !wasExpanded.current) {
      const active = document.activeElement;
      if (locked) {
        // The upsell's heading, whatever held focus: the reader asked to read the upsell.
        headingRef.current?.focus({ preventScroll: true });
      } else if (phase === 'empty' && !typedDisabled) {
        // Whatever holds focus: the pressed Ask button stays mounted, so "only when it was lost" would
        // never fire. Something settled keeps the old rule below.
        inputRef.current?.focus({ preventScroll: true });
      } else if (!active || active === document.body) {
        park();
      }
    }
    if (!expanded && wasExpanded.current && document.activeElement === wrapRef.current) {
      handOnTarget()?.focus({ preventScroll: true });
    }
    wasExpanded.current = expanded;
    // `phase`/`typedDisabled` are read at the moment of opening only; the transition is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded]);

  // The expanded content unmounted with focus inside it (S2), or the door unmounted under its own press (it
  // becomes the Layers section) or when a typed question went busy; each reports, from a layout-effect
  // cleanup, that it held focus. Acted on after the commit that removed it, and only if focus was truly lost:
  // Layers when the section is what replaced the line, the destination of the close otherwise.
  const contentLostFocus = useRef(false);
  const reportContentLost = useCallback(() => { contentLostFocus.current = true; }, []);
  const doorLostFocus = useRef(false);
  const reportDoorLost = useCallback(() => { doorLostFocus.current = true; }, []);
  useEffect(() => {
    if (!doorLostFocus.current && !contentLostFocus.current) return;
    const doorLost = doorLostFocus.current;
    doorLostFocus.current = false;
    contentLostFocus.current = false;
    // Defensive: the node is gone, so focus is on <body> unless something else in the same commit has already
    // taken it (a child effect that focused somewhere deliberate), which this must not undo.
    const active = document.activeElement;
    if (active && active !== document.body) return;
    const target = doorLost && mode === 'section' ? layersRef.current : handOnTarget();
    target?.focus({ preventScroll: true });
  });

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
      {expanded && <FocusLossGuard onLost={reportContentLost} />}
      {/* ⚠️ FIRST branch: a locked reader's expanded section is the upsell, never the field or the conversation. */}
      {expanded && locked && (
        <div className="wf-map-peek-ask-body" id={PEEK_ASK_BODY_ID} data-testid="wf-map-peek-ask-body">
          <div className="wf-ask-upsell-inline" data-testid="wf-map-peek-ask-upsell-inline">
            <AskUpsellContent
              headingId="wf-map-peek-ask-upsell-heading"
              headingRef={headingRef}
              testIdPrefix="wf-map-peek-ask-upsell"
            />
            <button
              type="button"
              className="wf-ask-upsell-dismiss"
              data-testid="wf-map-peek-ask-upsell-dismiss"
              onClick={() => { park(); onClose(); }}
            >
              Not now
            </button>
          </div>
        </div>
      )}
      {expanded && !locked && (
        <>
          <div className="wf-map-peek-ask-row">
            <div className="wf-map-peek-ask-field" data-testid="wf-map-peek-ask-field">
              <AskInputRow inputRef={inputRef} view="map" viewLabel={FALLBACK_VIEW_LABEL} />
            </div>
            <button
              type="button"
              className="wf-map-peek-ask-x"
              data-testid="wf-map-peek-ask-x"
              aria-label={settled ? NEW_CONVERSATION_LABEL : 'Close Ask'}
              aria-describedby={settled ? hintId : undefined}
              onClick={closeSection}
            >
              <span aria-hidden="true">✕</span>
            </button>
            {settled && <span id={hintId} className="sr-only">{NEW_CONVERSATION_HINT}</span>}
          </div>
          <div
            className="wf-map-peek-ask-body"
            id={PEEK_ASK_BODY_ID}
            data-testid="wf-map-peek-ask-body"
          >
            <AskConversation
              view="map"
              viewLabel={FALLBACK_VIEW_LABEL}
              planActions={planActions}
            />
          </div>
        </>
      )}
      {mode === 'minimised' && (
        <MinimisedLine
          ask={ask}
          onOpen={onOpen}
          onOpenLayers={onOpenLayers}
          doorRef={doorRef}
          onDoorUnmountWithFocus={reportDoorLost}
        />
      )}
      {/* The refusal's sentence, said once: while the section is open the conversation's own region
          carries it (and a sentence it showed is not said again here), so this one holds only a refusal
          that arrived while the section was closed — which the Ask button also wears, as a dot and in its
          name. Mounted empty and always, so a fill is a change. */}
      <span key="status" role="status" className="sr-only" data-testid="wf-map-peek-ask-status">
        {unseenError ?? ''}
      </span>
    </div>
  );
}

MapPeekAsk.propTypes = {
  mode: PropTypes.oneOf(['collapsed', 'expanded', 'minimised', 'section']).isRequired,
  onOpen: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  onOpenLayers: PropTypes.func.isRequired,
  buttonRef: objectRefShape.isRequired,
  doorRef: objectRefShape.isRequired,
  layersRef: objectRefShape.isRequired,
  planActions: planActionsShape,
};

/**
 * The minimised state's one line (design State 6): the chosen pick's rank, spot and time and "N picks ▴"
 * — or, for an answer with no picks, the not-in-the-forecast reply or the failure, in a line of their
 * own words. Every word is read off the cards the conversation already holds (served facts, joined by
 * {@code buildPickCards}); nothing is derived here.
 *
 * <p>A ROW of two sibling buttons (`phone-pick-callout-plan.md` §3 P2), in all three variants: the line
 * itself, which opens the answer, and the Layers door, which opens the peek sheet's Layers section with
 * the answer kept (Show, Regions, Filters, Tide mode — the line replaces the Other windows · Tide · Ask ·
 * Layers buttons, so without the door the filters are reachable only through the window pill). The door is
 * a SIBLING, never a child: a button inside a button is invalid HTML and its press would also fire the
 * expand. It is the Layers peek button in miniature (the {@code LAYERS} caption over {@code ☰}, the same
 * accessible name and {@code aria-controls}), 44 x 44px like that button, with the Ask-✕'s border and radius.
 * One row wrapper owns the line's 8px side margin and 44px height, so the sheet stays at its resting 74px. The wrapper is the
 * same element in every variant, so a variant change (a pick selected, a plan opened) does not remount
 * the door.
 *
 * <p>The door unmounts under its own press; {@code onDoorUnmountWithFocus} reports that it held focus
 * (from a layout-effect cleanup, while the focused node is still {@code document.activeElement}) so
 * {@code MapPeekAsk} can hand focus on.
 */
function MinimisedLine({
  ask, onOpen, onOpenLayers, doorRef, onDoorUnmountWithFocus,
}) {
  const {
    phase, pickCards, selectedPick, planPick, answer, error,
  } = ask;
  const report = useRef(onDoorUnmountWithFocus);
  useEffect(() => { report.current = onDoorUnmountWithFocus; });
  useLayoutEffect(() => () => {
    if (document.activeElement?.classList?.contains('wf-map-peek-mini-lay')) report.current?.();
  }, []);

  let line;
  if ((phase === 'answer' || phase === 'plan') && pickCards.length > 0) {
    const shown = phase === 'plan' ? planPick : selectedPick;
    const card = pickCards.find((c) => c.rank === shown) ?? pickCards[0];
    const when = [card.shortWindow, card.eventTime].filter(Boolean).join(' ');
    // In the plan view the line says what the reader was doing, not how many picks there are (F5).
    const count = phase === 'plan' ? 'Plan this' : picksWord(pickCards.length);
    line = (
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
  } else {
    let text = 'Answer';
    if (phase === 'cant') text = 'Not in the forecast';
    else if (phase === 'error') text = error.message;
    else if (typeof answer?.summary === 'string' && answer.summary !== '') text = answer.summary;
    line = (
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
  return (
    <div className="wf-map-peek-mini-row" data-testid="wf-map-peek-mini-row">
      {line}
      <button
        ref={doorRef}
        type="button"
        className="wf-map-peek-mini-lay"
        data-testid="wf-map-peek-mini-lay"
        aria-label="Layers"
        aria-expanded="false"
        aria-controls="wf-map-peek-body"
        onClick={(event) => {
          // Safari (and iOS) do not focus a button a pointer pressed, and focus that was never on the door
          // cannot be reported lost when the door unmounts: take it, so the hand-off to the Layers button
          // runs for a tap as it does for a key.
          event.currentTarget.focus({ preventScroll: true });
          onOpenLayers();
        }}
      >
        <span className="wf-map-peek-k" aria-hidden="true">LAYERS</span>
        <span className="wf-map-peek-mini-lay-g" aria-hidden="true">&#9776;</span>
      </button>
    </div>
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
  onOpenLayers: PropTypes.func.isRequired,
  doorRef: objectRefShape.isRequired,
  onDoorUnmountWithFocus: PropTypes.func.isRequired,
};
