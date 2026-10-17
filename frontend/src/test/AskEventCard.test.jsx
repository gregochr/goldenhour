import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import AskEventCard from '../components/ask/AskEventCard.jsx';
import { ECLIPSE_SAFETY_NOTE, eclipseEvent, NOW } from './askFixtures.js';

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

// `formatDateLabel` answers "Today"/"Tomorrow" from the app's clock, so a date fixture that does not
// freeze it is a test with an expiry date (the eclipse below is the 10th).
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

/** A phone-width viewport: the one `useIsMobile` reads. */
const narrow = () => vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
  matches: true,
  media: query,
  addEventListener() {},
  removeEventListener() {},
  addListener() {},
  removeListener() {},
  dispatchEvent() { return false; },
}));

describe('AskEventCard — the safety note', () => {
  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('is visible, and in the accessibility tree, for %s', (role) => {
    mockRole = role;

    render(<AskEventCard event={eclipseEvent()} />);

    const note = screen.getByRole('note');
    expect(note).toBeVisible();
    expect(note).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
    expect(screen.getByTestId('ask-event-safety')).toBe(note);
  });

  it('reads no viewport: at a phone width the component renders the same note', () => {
    // jsdom loads no stylesheet, so this cannot see a CSS rule that hides the note at a width — that
    // half is pinned against index.css itself in askCss.test.js. What it does pin is that nothing in
    // the component branches on a media query.
    narrow();

    render(<AskEventCard event={eclipseEvent()} />);

    expect(screen.getByRole('note')).toBeVisible();
    expect(screen.getByRole('note')).toHaveTextContent(ECLIPSE_SAFETY_NOTE);
  });

  it('names itself a safety warning to a screen reader, not only by a glyph and a colour', () => {
    render(<AskEventCard event={eclipseEvent()} />);

    expect(screen.getByRole('note')).toHaveTextContent(`Safety warning: ${ECLIPSE_SAFETY_NOTE}`);
  });

  it('survives a note of any length without being truncated in the DOM', () => {
    const long = `${ECLIPSE_SAFETY_NOTE} `.repeat(6).trim();

    render(<AskEventCard event={eclipseEvent({ safetyNote: long })} />);

    expect(screen.getByRole('note')).toHaveTextContent(long);
  });

  it.each([
    ['absent', undefined],
    ['null', null],
    ['an empty string', ''],
    ['whitespace', '   '],
  ])('renders no note and no empty container when the safety note is %s', (_name, safetyNote) => {
    render(<AskEventCard event={eclipseEvent({ safetyNote })} />);

    expect(screen.queryByRole('note')).toBeNull();
    expect(screen.queryByTestId('ask-event-safety')).toBeNull();
  });
});

describe('AskEventCard — the card', () => {
  it('names the topic, says what it is and when, and why it is worth knowing', () => {
    render(<AskEventCard event={eclipseEvent()} />);

    const card = screen.getByTestId('ask-event-card');
    expect(card).toHaveTextContent('ECLIPSE');
    expect(card).toHaveTextContent('Partial solar eclipse');
    expect(screen.getByTestId('ask-event-when')).toHaveTextContent('Sat 10 Oct');
    expect(card).toHaveTextContent('A partial eclipse peaks at midday on Saturday.');
  });

  it.each([
    ['ECLIPSE', 'eclipse'],
    ['LUNAR_ECLIPSE', 'eclipse'],
    ['KING_TIDE', 'tide'],
    ['NLC', 'nlc'],
    ['AURORA', 'aurora'],
    ['SNOW_TOPS', 'snow'],
    ['DUST', 'plain'],
  ])('colours a %s card through the %s badge channel, the app’s one mapping', (type, channel) => {
    render(<AskEventCard event={eclipseEvent({ type, safetyNote: undefined })} />);

    expect(screen.getByTestId('ask-event-card')).toHaveAttribute('data-channel', channel);
  });

  it('reads an underscored type as words in the kicker', () => {
    render(<AskEventCard event={eclipseEvent({ type: 'LUNAR_ECLIPSE', safetyNote: undefined })} />);

    expect(screen.getByTestId('ask-event-card')).toHaveTextContent('LUNAR ECLIPSE');
  });

  it('renders without a date or a reason when the server sent neither', () => {
    render(<AskEventCard event={{ type: 'AURORA', label: 'Aurora tonight' }} />);

    expect(screen.getByTestId('ask-event-card')).toHaveTextContent('Aurora tonight');
    expect(screen.queryByTestId('ask-event-when')).toBeNull();
  });
});
