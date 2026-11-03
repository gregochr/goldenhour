/**
 * `MapPeekAskButton` and `MapPeekAsk` — the Ask peek button, the Ask section's body and the minimised line
 * of the phone peek sheet (`docs/engineering/ask-photocast-plan.md` §2.7, F4; `map-mobile-compact-plan.md`
 * C2), on their own: what each mode draws, what the ✕
 * does, and above all where FOCUS goes — a control that unmounts when pressed takes focus to `<body>`
 * with it, after which the map's Escape rule (which runs only while focus is inside the pane) is dead.
 * Every focus claim here is asserted on `document.activeElement`, never on a key fired at a node.
 *
 * <p>The conversation is a mutable `useAsk` value; `AskConversation` is a marker (it reads the briefing,
 * and its own tests are F1a's). `AskInputRow` is real — it is the field the row holds.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach,
} from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

let mockAsk;
vi.mock('../context/AskContext.jsx', () => ({ useAsk: () => mockAsk }));
vi.mock('../components/ask/AskConversation.jsx', () => ({
  default: (props) => (
    <div data-testid="stub-conversation" data-view={props.view} data-view-label={props.viewLabel} />
  ),
}));

import MapPeekAsk, { MapPeekAskButton, PEEK_ASK_LABEL } from '../components/map/MapPeekAsk.jsx';

const card = (rank, over = {}) => ({
  rank,
  name: `Spot ${rank}`,
  shortWindow: 'Sat AM',
  eventTime: '06:12',
  ...over,
});

const ask = (over = {}) => ({
  phase: 'empty',
  answer: null,
  pickCards: [],
  history: [],
  selectedPick: null,
  error: null,
  inputError: null,
  asked: null,
  removedWindow: null,
  mapContext: null,
  typedDisabled: false,
  availability: 'on',
  askTyped: vi.fn(),
  removeContextWindow: vi.fn(),
  clear: vi.fn(),
  ...over,
});

/** The Ask button's ref, as `MapView` owns it: a plain ref object the part writes into. */
const buttonRef = { current: null };
/** The minimised line's Layers door, and the sheet's Layers button (a stub of it), as `MapView` owns them. */
const doorRef = { current: null };
const layersRef = { current: null };
/**
 * Mounts the parts in a given mode with spies for what they ask of their owner, laid out as the sheet lays
 * them: the Ask button in the button row (with a stub Layers button after it), which is drawn in every mode but
 * minimised, then the Ask body. That is what makes the hand-offs between the button, the door and the Layers
 * button observable here.
 */
function Harness({
  mode, onOpen, onClose, onOpenLayers, onPress,
}) {
  return (
    <div>
      <button type="button" data-testid="elsewhere">elsewhere</button>
      {mode !== 'minimised' && (
        <>
          <MapPeekAskButton mode={mode} onPress={onPress} buttonRef={buttonRef} />
          <button ref={layersRef} type="button" data-testid="stub-layers">Layers</button>
        </>
      )}
      <MapPeekAsk
        mode={mode}
        onOpen={onOpen}
        onClose={onClose}
        onOpenLayers={onOpenLayers}
        buttonRef={buttonRef}
        doorRef={doorRef}
        layersRef={layersRef}
      />
    </div>
  );
}

let onOpen;
let onClose;
let onOpenLayers;
let onPress;
function draw(mode) {
  return <Harness mode={mode} onOpen={onOpen} onClose={onClose} onOpenLayers={onOpenLayers} onPress={onPress} />;
}
let view;
const mount = (mode) => { view = render(draw(mode)); };
/** What the owner (`MapView`) does when the mode changes. */
const setMode = (mode) => view.rerender(draw(mode));

beforeEach(() => {
  mockAsk = ask();
  onOpen = vi.fn();
  onClose = vi.fn();
  onOpenLayers = vi.fn();
  onPress = vi.fn();
});

describe('the Ask button — the third peek button', () => {
  it('reads ASK over a ✦ (hidden from the name), is named "Ask PhotoCast", and toggles on a press', () => {
    mount('collapsed');

    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(button).toHaveTextContent('ASK');
    expect(button).toHaveTextContent('✦');
    expect(button.querySelector('.wf-map-peek-v')).toHaveAttribute('aria-hidden', 'true');
    expect(button).toHaveAccessibleName(PEEK_ASK_LABEL);
    expect(PEEK_ASK_LABEL).toBe('Ask PhotoCast');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAttribute('aria-controls', 'wf-map-peek-ask-body');
    expect(button).toHaveClass('wf-map-peek-btn', 'wf-map-peek-btn-ask');
    fireEvent.click(button);
    expect(onPress).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('ask-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-conversation')).not.toBeInTheDocument();
  });

  it('focuses itself on a press (S2: Safari and iOS do not), and writes its node to the owner\'s ref', () => {
    mount('collapsed');
    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(buttonRef.current).toBe(button);
    expect(document.activeElement).toBe(document.body);

    fireEvent.click(button);

    expect(document.activeElement).toBe(button);
  });

  it('is pressed (aria-expanded, the on class) while the section is open, and controls the Ask body', () => {
    mount('expanded');

    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(button).toHaveClass('wf-map-peek-btn-on');
    expect(screen.getByTestId('wf-map-peek-ask-body')).toHaveAttribute('id', 'wf-map-peek-ask-body');
  });

  // S5
  it('is disabled when Ask cannot reach its server — a real disabled button, not a hidden one', () => {
    mockAsk = ask({ availability: 'down' });
    mount('collapsed');

    expect(screen.getByTestId('wf-map-peek-btn-ask')).toBeDisabled();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-ask'));
    expect(onPress).not.toHaveBeenCalled();
  });

  // S8: a 44px box cannot carry the "Pro" pill, so the gating rule's "greyed where too small" applies.
  it('LITE: drawn muted (data-locked), still pressable, named "Ask PhotoCast, Pro", and with no pill and no dot', () => {
    mockAsk = ask({ availability: 'locked', inputError: 'Ask is a Pro feature.' });
    mount('collapsed');

    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(button).toHaveAttribute('data-locked', 'true');
    expect(button).toBeEnabled();
    expect(button).toHaveAccessibleName('Ask PhotoCast, Pro');
    expect(button).not.toHaveAttribute('data-error');
    expect(button.querySelector('.wf-lens-pro')).toBeNull();
    fireEvent.click(button);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('PRO: not locked', () => {
    mount('collapsed');

    expect(screen.getByTestId('wf-map-peek-btn-ask')).not.toHaveAttribute('data-locked');
  });

  // S4. F1a's rule: the conversation renders `inputError` itself, so while the section is CLOSED — when the
  // conversation is not mounted — the button must carry it: a dot, the sentence in its name, and one announcement.
  it('S4: a refusal that arrived while closed puts a dot on the button and the sentence in its name, and is said once', () => {
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    mount('collapsed');

    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(button).toHaveAttribute('data-error', 'true');
    expect(button).toHaveAccessibleName(`${PEEK_ASK_LABEL}. Slow down a moment.`);
    expect(screen.getByTestId('wf-map-peek-ask-status')).toHaveTextContent('Slow down a moment.');
  });

  it('S4: the refusal is still said by the status region while the answer is minimised (the button is not drawn then)', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' }, inputError: 'Slow down a moment.' });
    mount('minimised');
    expect(screen.getByTestId('wf-map-peek-ask-status')).toHaveTextContent('Slow down a moment.');
    expect(screen.queryByTestId('wf-map-peek-btn-ask')).not.toBeInTheDocument();

    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    setMode('minimised');
    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
  });

  // The provider keeps `inputError` until the next question: a button that mirrored it unconditionally would
  // keep a dot the reader has read, and say the sentence again on every collapse.
  it('a refusal the reader has SEEN in the open conversation is not shown again when the section closes', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('expanded'); // the conversation shows it
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');

    const button = screen.getByTestId('wf-map-peek-btn-ask');
    expect(button).not.toHaveAttribute('data-error');
    expect(button).toHaveAccessibleName(PEEK_ASK_LABEL);
    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
  });

  it('a refusal that arrives while the section is closed is shown, until it has been seen', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');
    expect(screen.getByTestId('wf-map-peek-btn-ask')).toHaveAttribute('data-error', 'true');

    setMode('expanded'); // seen
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-btn-ask')).not.toHaveAttribute('data-error');
  });

  it('the same sentence refused TWICE is two refusals: clearing the first makes the second new again', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('expanded');
    mockAsk = ask({ inputError: null });
    setMode('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-btn-ask')).toHaveAttribute('data-error', 'true');
  });

  it('leaves the sentence to the conversation while the section is open — never a second copy for a screen reader', () => {
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    mount('expanded');

    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
    expect(screen.getByTestId('wf-map-peek-btn-ask')).not.toHaveAttribute('data-error');
  });
});

describe('collapsed and section — the body draws nothing of its own', () => {
  it.each(['collapsed', 'section'])('%s: only the status region (mounted, empty) — no row, no field, no line', (mode) => {
    mount(mode);

    expect(screen.getByTestId('wf-map-peek-ask')).toHaveAttribute('data-mode', mode);
    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
    expect(screen.queryByTestId('wf-map-peek-ask-field')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-ask-body')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-mini')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-ask-entry')).not.toBeInTheDocument();
  });
});

describe('expanded — under the buttons: the field and a ✕, then the conversation', () => {
  it('draws the question field, the ✕ and the conversation for the Map — with the Ask button still there, pressed', () => {
    mount('expanded');

    expect(screen.getByTestId('ask-input')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek-ask-x')).toBeInTheDocument();
    expect(screen.getByTestId('stub-conversation')).toBeInTheDocument();
    // The conversation resolves its own scope and window; the peek hands it the tab and its fallback chip.
    expect(screen.getByTestId('stub-conversation')).toHaveAttribute('data-view', 'map');
    expect(screen.getByTestId('stub-conversation')).toHaveAttribute('data-view-label', 'Map · all regions');
    expect(screen.getByTestId('wf-map-peek-btn-ask')).toHaveAttribute('aria-expanded', 'true');
  });

  it('puts the field in read-only mode (Ready questions only) rather than removing it', () => {
    mockAsk = ask({ typedDisabled: true });
    mount('expanded');

    expect(screen.getByTestId('ask-input')).toHaveAttribute('placeholder', 'Ready questions only today');
    expect(screen.getByTestId('ask-input')).toHaveAttribute('readonly');
  });

  it('the ✕ names what it will do: "New conversation" over an answer, "Close Ask" otherwise', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)] });
    mount('expanded');
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('New conversation');

    mockAsk = ask({ phase: 'busy' });
    setMode('expanded');
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('Close Ask');
  });

  it('the ✕ says "New conversation" over a thread too, and describes what it ends', () => {
    mockAsk = ask({
      phase: 'answer', pickCards: [card(1)], history: [{ question: 'One?', summary: 's', answerId: 1 }],
    });
    mount('expanded');

    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('New conversation');
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleDescription(
      'Ends this conversation. Your earlier questions are kept until then.',
    );
    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));
    expect(mockAsk.clear).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['answer', true], ['cant', true], ['error', true], ['empty', false], ['busy', false],
  ])('the ✕ in phase %s %s the conversation, and always closes the section', (phase, clears) => {
    mockAsk = ask({ phase, pickCards: [], error: phase === 'error' ? { message: 'x' } : null });
    mount('expanded');

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));

    expect(mockAsk.clear).toHaveBeenCalledTimes(clears ? 1 : 0);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('minimised — one line', () => {
  it('is the LIVE answer’s line, exactly as without a thread: earlier exchanges add nothing to it', () => {
    const live = {
      phase: 'answer', pickCards: [card(1), card(2, { name: 'Whitby' })], selectedPick: 2, answer: { summary: 's' },
    };
    mockAsk = ask(live);
    mount('minimised');
    const alone = screen.getByTestId('wf-map-peek-mini');
    const before = { text: alone.textContent, name: alone.getAttribute('aria-label') };

    mockAsk = ask({
      ...live,
      thread: [{ question: 'One?', summary: 'Earlier.', answerId: 1 }, { question: 'Two?', summary: 's', answerId: 2 }],
      history: [{ question: 'One?', summary: 'Earlier.', answerId: 1 }],
    });
    setMode('minimised');

    const withThread = screen.getByTestId('wf-map-peek-mini');
    expect(withThread.textContent).toBe(before.text);
    expect(withThread.getAttribute('aria-label')).toBe(before.name);
    expect(withThread).not.toHaveTextContent('Earlier.');
  });

  it('an events-only answer reads its own summary, not an earlier exchange’s, over a thread', () => {
    mockAsk = ask({
      phase: 'answer',
      answer: { summary: 'A king tide on Thursday.' },
      history: [{ question: 'One?', summary: 'Earlier.', answerId: 1 }],
    });
    mount('minimised');

    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('A king tide on Thursday.');
    expect(screen.getByTestId('wf-map-peek-mini')).not.toHaveTextContent('Earlier.');
  });

  it('names the chosen pick: rank, spot, time, and how many picks', () => {
    mockAsk = ask({
      phase: 'answer', pickCards: [card(1), card(2, { name: 'Whitby' }), card(3)], selectedPick: 2, answer: { summary: 's' },
    });
    mount('minimised');

    const line = screen.getByTestId('wf-map-peek-mini');
    expect(line).toHaveTextContent('Whitby');
    expect(line).toHaveTextContent('Sat AM 06:12');
    expect(line).toHaveTextContent('3 picks');
    expect(line).toHaveAccessibleName('Pick 2, Whitby, Sat AM 06:12. 3 picks. Show the answer');
    fireEvent.click(line);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('with nothing chosen, names the first pick', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1), card(2)], answer: { summary: 's' } });
    mount('minimised');

    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Spot 1');
  });

  it('says "1 pick" for one — and leaves the time out when the card has none', () => {
    mockAsk = ask({
      phase: 'answer', pickCards: [card(1, { shortWindow: null, eventTime: null })], answer: { summary: 's' },
    });
    mount('minimised');

    const line = screen.getByTestId('wf-map-peek-mini');
    expect(line).toHaveTextContent('1 pick');
    expect(line).not.toHaveTextContent('1 picks');
    expect(line).toHaveAccessibleName('Pick 1, Spot 1. 1 pick. Show the answer');
  });

  it('an answer with no picks (events only) reads its own summary', () => {
    mockAsk = ask({ phase: 'answer', answer: { summary: 'A king tide on Thursday.' } });
    mount('minimised');

    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('A king tide on Thursday.');
  });

  it('a "not in the forecast" reply and a failure read as themselves', () => {
    mockAsk = ask({ phase: 'cant', answer: { summary: 's' } });
    mount('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Not in the forecast');

    mockAsk = ask({ phase: 'error', error: { message: 'Couldn’t answer just now. No question used.' } });
    setMode('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Couldn’t answer just now. No question used.');
  });

  it('an answer with no picks and no summary still reads as one, and a selected pick no card carries falls back to the first', () => {
    mockAsk = ask({ phase: 'answer', answer: {} });
    mount('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Answer');

    mockAsk = ask({
      phase: 'answer', pickCards: [card(1), card(2)], selectedPick: 9, answer: { summary: 's' },
    });
    setMode('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Spot 1');
  });

  it('F5\'s plan phase counts as settled: a line to keep, a ✕ that clears', () => {
    mockAsk = ask({ phase: 'plan', pickCards: [card(2)], selectedPick: 2, answer: { summary: 's' } });
    mount('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Spot 2');

    setMode('expanded');
    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));
    expect(mockAsk.clear).toHaveBeenCalledTimes(1);
  });

  it('is drawn in no other mode', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    for (const mode of ['collapsed', 'section', 'expanded']) {
      mount(mode);
      expect(screen.queryByTestId('wf-map-peek-mini')).not.toBeInTheDocument();
      view.unmount();
    }
  });
});

describe('focus never falls to <body>', () => {
  // S3: the Ask button STAYS mounted and holds focus when it is pressed, so "only when focus was lost" would
  // never fire — opening puts the cursor in the field whatever holds focus.
  it('S3: opening with nothing to read puts the cursor in the field, with the Ask button holding focus', () => {
    mount('collapsed');
    screen.getByTestId('wf-map-peek-btn-ask').focus();

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('ask-input'));
  });

  it('S3: ... and with focus on <body> or on another control (the phone Ask button need not have been what opened it)', () => {
    mount('collapsed');
    screen.getByTestId('elsewhere').focus();

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('ask-input'));
  });

  it('S3: locked, opening focuses the upsell heading, whatever holds focus', () => {
    mockAsk = ask({ availability: 'locked', typedDisabled: true });
    mount('collapsed');
    screen.getByTestId('wf-map-peek-btn-ask').focus();

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-upsell-heading'));
  });

  it('S3: with a busy question, opening keeps the old rule: nothing held, so the pane\'s node; something held, left alone', () => {
    mockAsk = ask({ phase: 'busy' });
    mount('collapsed');
    setMode('expanded');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));

    setMode('collapsed');
    screen.getByTestId('wf-map-peek-btn-ask').focus();
    setMode('expanded');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
  });

  it('opening with an answer to read parks focus on the Ask node, not in a field that would raise the keyboard over it', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('minimised');
    screen.getByTestId('wf-map-peek-mini').focus();

    act(() => { screen.getByTestId('wf-map-peek-mini').blur(); });
    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
  });

  it('with typed questions off, opening parks focus on the Ask node too (a read-only field would say nothing)', () => {
    mockAsk = ask({ typedDisabled: true });
    mount('collapsed');

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
  });

  // No pick-chip route expands the answer any more (phone-pick-callout-plan.md §3 P1: a chip opens a
  // callout); the expand effect itself still yields to a control the reader holds, whatever opened it.
  it('does not take focus from a control the reader already holds when the section expands', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('minimised');
    screen.getByTestId('elsewhere').focus();

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  // S1: focus is handed on by DESTINATION.
  it('S1: the ✕ over an answer (closing to the COLLAPSED buttons) hands focus to the Ask button', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('expanded');
    screen.getByTestId('wf-map-peek-ask-x').focus();

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));
    // The owner collapses (and the provider clears): the field and ✕ go, the buttons stay.
    mockAsk = ask();
    setMode('collapsed');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
    expect(document.activeElement).toBe(buttonRef.current);
  });

  it('S1: closing to MINIMISED hands parked focus to the line\'s door — the Ask button is not mounted then', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('expanded');
    const field = screen.getByTestId('ask-input');
    field.focus();

    // The native listener runs before the owner's own handler: by the time the field would unmount,
    // focus is already somewhere that survives.
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
    setMode('minimised');

    expect(screen.queryByTestId('wf-map-peek-btn-ask')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
    expect(document.activeElement).toBe(doorRef.current);
  });

  it('S1: Escape in the field closing to COLLAPSED hands focus to the Ask button', () => {
    mount('expanded');
    const field = screen.getByTestId('ask-input');
    field.focus();

    fireEvent.keyDown(field, { key: 'Escape' });
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
    setMode('collapsed');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
  });

  it('S1: with the Ask button DISABLED (the server went away), the hand-off lands on the Layers button, never <body>', () => {
    mount('expanded');
    screen.getByTestId('ask-input').focus();
    fireEvent.keyDown(screen.getByTestId('ask-input'), { key: 'Escape' });

    mockAsk = ask({ availability: 'down' });
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-btn-ask')).toBeDisabled();
    expect(document.activeElement).toBe(screen.getByTestId('stub-layers'));
  });

  it('Escape on the Ask button (nothing is open) moves nothing', () => {
    mount('collapsed');
    const button = screen.getByTestId('wf-map-peek-btn-ask');
    button.focus();

    fireEvent.keyDown(button, { key: 'Escape' });

    expect(document.activeElement).toBe(button);
  });

  // S2: the four buttons stay while Ask is expanded, so a press on another section unmounts the field, the ✕
  // or a pick card while one of them holds focus. A press focuses its own button; the section is the net for
  // every other route (a pick chip, a tab switch).
  it.each([
    ['the field', () => screen.getByTestId('ask-input')],
    ['the ✕', () => screen.getByTestId('wf-map-peek-ask-x')],
  ])('S2: switching to another section from an expanded Ask while %s holds focus lands on the Ask button, not <body>', (_n, target) => {
    mount('expanded');
    target().focus();
    expect(document.activeElement).toBe(target());

    setMode('section');

    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
  });

  it('S2: ... and closing an expanded Ask to MINIMISED while the field holds focus lands on the door', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('expanded');
    screen.getByTestId('ask-input').focus();

    setMode('minimised');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
  });

  it('S2: a LOCKED reader\'s upsell heading losing focus on a switch lands on the Ask button too', () => {
    mockAsk = ask({ availability: 'locked', typedDisabled: true });
    mount('expanded');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-upsell-heading'));

    setMode('section');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
  });

  it('S2: a peek button the reader pressed (it took focus) is not overridden when the section goes', () => {
    mount('expanded');
    screen.getByTestId('stub-layers').focus();

    setMode('section');

    expect(document.activeElement).toBe(screen.getByTestId('stub-layers'));
  });

  it('Escape with a dialog over the map moves nothing: the pane stands down, so nothing is about to close the section', () => {
    mount('expanded');
    const field = screen.getByTestId('ask-input');
    field.focus();
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    document.body.appendChild(dialog);

    fireEvent.keyDown(field, { key: 'Escape' });

    expect(document.activeElement).toBe(field);
    dialog.remove();
  });

  it('an IME\'s composing Escape is the IME\'s, not a close', () => {
    mount('expanded');
    const field = screen.getByTestId('ask-input');
    field.focus();

    fireEvent.keyDown(field, { key: 'Escape', isComposing: true });

    expect(document.activeElement).toBe(field);
  });

  it('a close made while focus is elsewhere (a map touch) takes nothing from where it is', () => {
    mount('expanded');
    screen.getByTestId('elsewhere').focus();

    setMode('collapsed');

    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });
});

// docs/engineering/phone-pick-callout-plan.md §3 P2: the Layers door beside the minimised line, in all
// three variants (an answer with picks; "not in the forecast" / a failure; a plain summary).
describe('the Layers door — a SIBLING of the line, never inside it', () => {
  const variants = [
    ['picks', { phase: 'answer', pickCards: [card(1), card(2)], selectedPick: 1, answer: { summary: 's' } }],
    ['a not-in-the-forecast reply', { phase: 'cant' }],
    ['a plain summary', { phase: 'answer', answer: { summary: 'A king tide on Thursday.' } }],
  ];

  it.each(variants)('%s: one row holds the line and the door, and the door is not inside the line', (_name, conversation) => {
    mockAsk = ask(conversation);
    mount('minimised');

    const row = screen.getByTestId('wf-map-peek-mini-row');
    const line = screen.getByTestId('wf-map-peek-mini');
    const door = screen.getByTestId('wf-map-peek-mini-lay');
    expect(row).toContainElement(line);
    expect(row).toContainElement(door);
    expect(line).not.toContainElement(door);
    // Valid HTML, and the reason a press cannot fire the expand: the nearest button ancestor is itself.
    expect(door.closest('button')).toBe(door);
    expect(line.closest('button')).toBe(line);
    // …and no button anywhere above it (the row itself being one would nest them and fire both).
    expect(door.parentElement.closest('button')).toBeNull();
  });

  it.each(variants)('%s: the door opens Layers and never the answer; the line opens the answer and never Layers', (_name, conversation) => {
    mockAsk = ask(conversation);
    mount('minimised');

    fireEvent.click(screen.getByTestId('wf-map-peek-mini-lay'));
    expect(onOpenLayers).toHaveBeenCalledTimes(1);
    expect(onOpen).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('wf-map-peek-mini'));
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpenLayers).toHaveBeenCalledTimes(1);
  });

  it('is the Layers button in miniature: the LAYERS caption over ☰ (both hidden), named "Layers", closed, controlling the body', () => {
    mockAsk = ask(variants[0][1]);
    mount('minimised');

    const door = screen.getByTestId('wf-map-peek-mini-lay');
    expect(door).toHaveAccessibleName('Layers');
    expect(door).toHaveAttribute('aria-expanded', 'false');
    expect(door).toHaveAttribute('aria-controls', 'wf-map-peek-body');
    expect(door).toHaveAttribute('type', 'button');
    expect(door).toHaveTextContent('LAYERS☰');
    expect(door.querySelectorAll('[aria-hidden="true"]')).toHaveLength(2);
  });

  it('the line keeps the name it had, without the door\'s words in it', () => {
    mockAsk = ask(variants[0][1]);
    mount('minimised');

    expect(screen.getByTestId('wf-map-peek-mini'))
      .toHaveAccessibleName('Pick 1, Spot 1, Sat AM 06:12. 2 picks. Show the answer');
  });

  it('is the same element when the line changes variant (a pick chosen, a plan opened): the door is not remounted', () => {
    mockAsk = ask(variants[0][1]);
    mount('minimised');
    const door = screen.getByTestId('wf-map-peek-mini-lay');

    mockAsk = ask({ ...variants[0][1], selectedPick: 2 });
    setMode('minimised');
    mockAsk = ask(variants[1][1]);
    setMode('minimised');

    expect(screen.getByTestId('wf-map-peek-mini-lay')).toBe(door);
  });

  it('is drawn in the minimised mode only', () => {
    mockAsk = ask(variants[0][1]);
    for (const mode of ['collapsed', 'expanded', 'section']) {
      mount(mode);
      expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
      view.unmount();
    }
  });

  describe('focus', () => {
    it('a press hands focus to the Layers button the owner draws in its place', () => {
      mockAsk = ask(variants[0][1]);
      mount('minimised');
      const door = screen.getByTestId('wf-map-peek-mini-lay');
      door.focus();

      fireEvent.click(door);
      setMode('section'); // the owner opens Layers: the door unmounts, the Layers button mounts

      expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
      expect(document.activeElement).toBe(screen.getByTestId('stub-layers'));
    });

    it('a press takes focus onto the door first, so a browser that does not focus a tapped button still hands it on', () => {
      mockAsk = ask(variants[0][1]);
      mount('minimised');
      expect(document.activeElement).toBe(document.body);

      fireEvent.click(screen.getByTestId('wf-map-peek-mini-lay'));
      setMode('section');

      expect(document.activeElement).toBe(screen.getByTestId('stub-layers'));
    });

    it('a door removed while the reader holds focus elsewhere takes nothing from where it is', () => {
      mockAsk = ask(variants[0][1]);
      mount('minimised');
      screen.getByTestId('elsewhere').focus();

      setMode('section');

      expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
    });

    it('a door unmounted by a question going busy hands focus to the Ask button, not to <body>', () => {
      mockAsk = ask(variants[0][1]);
      mount('minimised');
      screen.getByTestId('wf-map-peek-mini-lay').focus();

      mockAsk = ask({ phase: 'busy' });
      setMode('collapsed');

      expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-ask'));
    });

    it('the line (not the door) unmounting while focused is the expand effect\'s business, not the door\'s', () => {
      mockAsk = ask(variants[0][1]);
      mount('minimised');
      screen.getByTestId('wf-map-peek-mini').focus();

      act(() => { screen.getByTestId('wf-map-peek-mini').blur(); });
      setMode('expanded');

      expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
    });
  });
});
