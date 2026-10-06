/**
 * `MapPeekAsk` — the Ask row of the phone peek sheet, its body and its minimised line
 * (`docs/engineering/ask-photocast-plan.md` §2.7, F4), on its own: what each mode draws, what the ✕
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
  default: (props) => <div data-testid="stub-conversation" data-scope={String(props.scope)} />,
}));

import MapPeekAsk, { PEEK_ASK_PROMPT } from '../components/map/MapPeekAsk.jsx';

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

/** The entry button's ref, as `MapView` owns it: a plain ref object the part writes into. */
const entryRef = { current: null };
/** Mounts the part in a given mode with spies for what it asks of its owner. */
function Harness({ mode, onOpen, onClose }) {
  return (
    <div>
      <button type="button" data-testid="elsewhere">elsewhere</button>
      <MapPeekAsk mode={mode} onOpen={onOpen} onClose={onClose} entryRef={entryRef} />
    </div>
  );
}

let onOpen;
let onClose;
function draw(mode) {
  return <Harness mode={mode} onOpen={onOpen} onClose={onClose} />;
}
let view;
const mount = (mode) => { view = render(draw(mode)); };
/** What the owner (`MapView`) does when the mode changes. */
const setMode = (mode) => view.rerender(draw(mode));

beforeEach(() => {
  mockAsk = ask();
  onOpen = vi.fn();
  onClose = vi.fn();
});

describe('collapsed — the row is the entry', () => {
  it('reads "Ask about what’s on the map…" and opens the Ask section on a press', () => {
    mount('collapsed');

    const entry = screen.getByTestId('wf-map-peek-ask-entry');
    expect(entry).toHaveTextContent(PEEK_ASK_PROMPT);
    expect(entry).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(entry);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('ask-input')).not.toBeInTheDocument();
    expect(screen.queryByTestId('stub-conversation')).not.toBeInTheDocument();
  });

  it('is disabled when Ask cannot reach its server — a real disabled button, not a hidden one', () => {
    mockAsk = ask({ availability: 'down' });
    mount('collapsed');

    expect(screen.getByTestId('wf-map-peek-ask-entry')).toBeDisabled();
  });

  // F1a's rule: the conversation renders `inputError` itself, so the COLLAPSED row — where the
  // conversation is not mounted — must show it where the field would be.
  it('shows a refusal\'s sentence where the prompt was, says it once, and names the button by both', () => {
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    mount('collapsed');

    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent('Slow down a moment.');
    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveAttribute('data-error', 'true');
    expect(screen.getByTestId('wf-map-peek-ask-entry'))
      .toHaveAccessibleName(`${PEEK_ASK_PROMPT} Slow down a moment.`);
    expect(screen.getByTestId('wf-map-peek-ask-status')).toHaveTextContent('Slow down a moment.');
  });

  it('shows the refusal in the minimised row too, and shows no error when there is none', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' }, inputError: 'Slow down a moment.' });
    mount('minimised');
    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent('Slow down a moment.');

    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    setMode('minimised');
    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent(PEEK_ASK_PROMPT);
    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
  });

  // The provider keeps `inputError` until the next question: a row that mirrored it unconditionally would
  // keep a sentence the reader has read where the prompt belongs, and say it again on every collapse.
  it('a refusal the reader has SEEN in the open conversation is not shown again when the section closes', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('expanded'); // the conversation shows it
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent(PEEK_ASK_PROMPT);
    expect(screen.getByTestId('wf-map-peek-ask-q')).not.toHaveAttribute('data-error');
    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
    expect(screen.getByTestId('wf-map-peek-ask-entry')).not.toHaveAttribute('aria-label');
  });

  it('a refusal that arrives while the section is closed is shown, until it has been seen', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');
    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent('Slow down a moment.');

    setMode('expanded'); // seen
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent(PEEK_ASK_PROMPT);
  });

  it('the same sentence refused TWICE is two refusals: clearing the first makes the second new again', () => {
    mount('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('expanded');
    mockAsk = ask({ inputError: null });
    setMode('collapsed');
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    setMode('collapsed');

    expect(screen.getByTestId('wf-map-peek-ask-q')).toHaveTextContent('Slow down a moment.');
  });

  it('leaves the sentence to the conversation while the section is open — never a second copy for a screen reader', () => {
    mockAsk = ask({ inputError: 'Slow down a moment.' });
    mount('expanded');

    expect(screen.getByTestId('wf-map-peek-ask-status')).toBeEmptyDOMElement();
    expect(screen.queryByTestId('wf-map-peek-ask-entry')).not.toBeInTheDocument();
  });
});

describe('expanded — the row holds the field and a ✕, the conversation fills the body', () => {
  it('draws the question field, the ✕ and the conversation for the Map, and not the entry', () => {
    mount('expanded');

    expect(screen.getByTestId('ask-input')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek-ask-x')).toBeInTheDocument();
    expect(screen.getByTestId('stub-conversation')).toBeInTheDocument();
    expect(screen.getByTestId('stub-conversation')).toHaveAttribute('data-scope', 'all');
    expect(screen.queryByTestId('wf-map-peek-ask-entry')).not.toBeInTheDocument();
  });

  it('puts the field in read-only mode (Ready questions only) rather than removing it', () => {
    mockAsk = ask({ typedDisabled: true });
    mount('expanded');

    expect(screen.getByTestId('ask-input')).toHaveAttribute('placeholder', 'Ready questions only today');
    expect(screen.getByTestId('ask-input')).toHaveAttribute('readonly');
  });

  it('the ✕ names what it will do: "Clear answer" over an answer, "Close Ask" otherwise', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)] });
    mount('expanded');
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('Clear answer');

    mockAsk = ask({ phase: 'busy' });
    setMode('expanded');
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('Close Ask');
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
  it('opening with nothing to read puts the cursor in the field', () => {
    mount('collapsed');
    screen.getByTestId('wf-map-peek-ask-entry').focus();

    // The entry unmounts under the press, as it does in the browser: focus is on <body> by now.
    act(() => { screen.getByTestId('wf-map-peek-ask-entry').blur(); });
    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('ask-input'));
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

  it('does not take focus from a control the reader already holds (a pick chip that opened it)', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('minimised');
    screen.getByTestId('elsewhere').focus();

    setMode('expanded');

    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('the ✕ over an answer hands focus to the entry button once it exists', () => {
    mockAsk = ask({ phase: 'answer', pickCards: [card(1)], answer: { summary: 's' } });
    mount('expanded');
    screen.getByTestId('wf-map-peek-ask-x').focus();

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));
    // The owner collapses (and the provider clears): the field and ✕ go, the entry comes.
    mockAsk = ask();
    setMode('collapsed');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
    expect(document.activeElement).toBe(entryRef.current);
  });

  it('Escape in the field parks focus before the owner closes the section, and the entry takes it after', () => {
    mount('expanded');
    const field = screen.getByTestId('ask-input');
    field.focus();
    expect(document.activeElement).toBe(field);

    // The native listener runs before the owner's own handler: by the time the field would unmount,
    // focus is already somewhere that survives.
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
    setMode('minimised');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  it('Escape on the entry button (nothing is open) moves nothing', () => {
    mount('collapsed');
    const entry = screen.getByTestId('wf-map-peek-ask-entry');
    entry.focus();

    fireEvent.keyDown(entry, { key: 'Escape' });

    expect(document.activeElement).toBe(entry);
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
