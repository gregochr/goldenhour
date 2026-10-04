import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import RewindView, { customInRange } from '../components/RewindView.jsx';
import { getRewind, setRewind } from '../utils/rewind.js';

vi.mock('../api/rewindApi.js', () => ({ getRewindEvents: vi.fn() }));
import { getRewindEvents } from '../api/rewindApi.js';

/** The wire shape `GET /api/admin/rewind/events` serves: newest first, instants, a UK date. */
/** A Date as the `datetime-local` value the browser would produce for it, in the test's own zone. */
function localValue(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const EVENTS = {
  now: '2026-10-04T09:30:00Z',
  briefingGeneratedAt: '2026-10-04T06:42:00Z',
  events: [
    { date: '2026-10-04', eventType: 'SUNSET', earliest: '2026-10-04T17:35:00Z', latest: '2026-10-04T17:58:00Z',
      rewindTo: '2026-10-04T16:35:00Z', passed: false, locationCount: 2 },
    { date: '2026-10-04', eventType: 'SUNRISE', earliest: '2026-10-04T05:58:00Z', latest: '2026-10-04T06:21:00Z',
      rewindTo: '2026-10-04T04:58:00Z', passed: true, locationCount: 2 },
    { date: '2026-10-03', eventType: 'SUNSET', earliest: '2026-10-03T17:37:00Z', latest: '2026-10-03T18:00:00Z',
      rewindTo: '2026-10-03T16:37:00Z', passed: true, locationCount: 2 },
  ],
};

describe('RewindView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getRewindEvents.mockResolvedValue(EVENTS);
  });
  afterEach(() => act(() => setRewind(null)));

  it('lists each served event in UK time with the roster span, and names each button by its own day', async () => {
    render(<RewindView />);
    const sunrise = await screen.findByTestId('rewind-event-2026-10-04-SUNRISE');
    expect(sunrise).toHaveTextContent('Sunrise · Sun 4 Oct');
    expect(sunrise).toHaveTextContent('06:58–07:21 UK across 2 locations');
    expect(screen.getByTestId('rewind-events').querySelectorAll('li')).toHaveLength(3);
    // Two sunsets a day apart would otherwise both read "Rewind to 17:35" to a screen reader.
    expect(screen.getByRole('button', { name: 'Rewind to 05:58, before the sunrise of Sun 4 Oct' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rewind to 17:37, before the sunset of Sat 3 Oct' })).toBeInTheDocument();
  });

  it('one location is "1 location"', async () => {
    getRewindEvents.mockResolvedValue({
      ...EVENTS, events: [{ ...EVENTS.events[1], locationCount: 1 }],
    });
    render(<RewindView />);
    expect(await screen.findByTestId('rewind-event-2026-10-04-SUNRISE')).toHaveTextContent('across 1 location');
    expect(screen.getByTestId('rewind-event-2026-10-04-SUNRISE')).not.toHaveTextContent('1 locations');
  });

  it('a window still ahead cannot be rewound to — there is nothing to rewind', async () => {
    render(<RewindView />);
    const button = await screen.findByTestId('rewind-to-2026-10-04-SUNSET');
    expect(button).toBeDisabled();
    expect(screen.getByTestId('rewind-event-2026-10-04-SUNSET')).toHaveTextContent('still ahead');
  });

  it('Rewind to … sets the rewind to the served instant with the event as the map focus', async () => {
    render(<RewindView />);
    const button = await screen.findByTestId('rewind-to-2026-10-04-SUNRISE');
    expect(button).toHaveTextContent('Rewind to 05:58'); // 04:58Z is 05:58 BST
    fireEvent.click(button);
    expect(getRewind()).toEqual({
      to: '2026-10-04T04:58:00Z', focus: { date: '2026-10-04', eventType: 'SUNRISE' },
    });
  });

  it('says when the briefing was built after the moment, and only then', async () => {
    render(<RewindView />);
    await screen.findByTestId('rewind-events');
    // Built 06:42Z: after the 04:58Z sunrise rewind, before nothing else that has passed… and
    // the 03 Oct sunset (16:37Z) is before the build too, so BOTH passed events carry the note.
    expect(screen.getByTestId('rewind-built-after-2026-10-04-SUNRISE')).toHaveTextContent('last built 07:42 UK');
    expect(screen.getByTestId('rewind-built-after-2026-10-03-SUNSET')).toBeInTheDocument();
    // The one still ahead never carries it.
    expect(screen.queryByTestId('rewind-built-after-2026-10-04-SUNSET')).toBeNull();
  });

  it('with no build time there is no note', async () => {
    getRewindEvents.mockResolvedValue({ ...EVENTS, briefingGeneratedAt: null });
    render(<RewindView />);
    await screen.findByTestId('rewind-events');
    expect(screen.queryByTestId('rewind-built-after-2026-10-04-SUNRISE')).toBeNull();
  });

  it('shows the current rewind with a way back to live', async () => {
    setRewind('2026-10-04T04:58:00Z');
    render(<RewindView />);
    expect(await screen.findByTestId('rewind-current')).toHaveTextContent('Rewound to Sun 4 Oct, 05:58 UK');
    fireEvent.click(screen.getByTestId('rewind-exit'));
    expect(getRewind()).toBeNull();
    expect(screen.queryByTestId('rewind-current')).toBeNull();
  });

  it('a moment of your own: disabled until the input names a moment in range, then rewinds to it with no focus', async () => {
    render(<RewindView />);
    await screen.findByTestId('rewind-events');
    const go = screen.getByTestId('rewind-custom-go');
    const input = screen.getByTestId('rewind-custom');
    expect(go).toBeDisabled();
    // Relative to the wall clock, so the test does not age out of the three-day window.
    const twoHoursAgo = new Date(Date.now() - 2 * 3600 * 1000);
    twoHoursAgo.setSeconds(0, 0);
    fireEvent.change(input, { target: { value: localValue(twoHoursAgo) } });
    expect(go).toBeEnabled();
    fireEvent.click(go);
    expect(getRewind()?.to).toBe(twoHoursAgo.toISOString());
    expect(getRewind()?.focus).toBeNull();
  });

  it('a moment of your own is refused where the backend would refuse it: the future, or more than three days back', async () => {
    render(<RewindView />);
    await screen.findByTestId('rewind-events');
    const go = screen.getByTestId('rewind-custom-go');
    const input = screen.getByTestId('rewind-custom');
    fireEvent.change(input, { target: { value: localValue(new Date(Date.now() + 2 * 3600 * 1000)) } });
    expect(go).toBeDisabled();
    fireEvent.change(input, { target: { value: localValue(new Date(Date.now() - 4 * 24 * 3600 * 1000)) } });
    expect(go).toBeDisabled();
    expect(input).toHaveAttribute('max');
    expect(input).toHaveAttribute('min');
  });

  it('customInRange: readable, not ahead of now, not more than three days behind it', () => {
    const now = new Date('2026-10-04T09:30:00Z');
    expect(customInRange('nope', now)).toBe(false);
    expect(customInRange(new Date('2026-10-04T09:30:00Z'), now)).toBe(true);
    expect(customInRange(new Date('2026-10-04T09:30:01Z'), now)).toBe(false);
    expect(customInRange(new Date('2026-10-01T09:30:00Z'), now)).toBe(true);
    expect(customInRange(new Date('2026-10-01T09:29:59Z'), now)).toBe(false);
  });

  it('a failed load says so', async () => {
    getRewindEvents.mockRejectedValue(new Error('500'));
    render(<RewindView />);
    expect(await screen.findByText('Failed to load the recent solar events')).toBeInTheDocument();
  });

  it('an empty roster is said, not left blank', async () => {
    getRewindEvents.mockResolvedValue({ now: EVENTS.now, briefingGeneratedAt: null, events: [] });
    render(<RewindView />);
    expect(await screen.findByText('No sky locations to time an event across.')).toBeInTheDocument();
  });
});
