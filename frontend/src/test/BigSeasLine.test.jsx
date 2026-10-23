import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { setRewind } from '../utils/rewind.js';
import BigSeasLine from '../components/map/BigSeasLine.jsx';
import {
  SATURDAY, SUNDAY, THURSDAY_MORNING, weekEvents,
} from './bigSeasFixtures.js';

const EVENTS = weekEvents();
const row = (id) => EVENTS.find((r) => r.id === id);

beforeEach(() => {
  // `BigSeasLine` reads `appNow()`; pin the wall clock so "today" is Thursday 22 Oct 2026.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(THURSDAY_MORNING);
});

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

function renderLine({ evId, days = [SATURDAY, SUNDAY], onSelectEv = vi.fn(), events = EVENTS }) {
  const utils = render(
    <BigSeasLine bigSeas={days} evRow={row(evId)} events={events} onSelectEv={onSelectEv} />,
  );
  return { onSelectEv, ...utils };
}

describe('BigSeasLine: the full form (a window of the big-seas day)', () => {
  it('prints the window, high water and the sea, with the action naming the aligned event', () => {
    renderLine({ evId: 'solar:2026-10-24:SUNRISE' });
    expect(screen.getByTestId('wf-big-seas-l1').textContent).toBe('15:30–18:15 · high water 16:58');
    expect(screen.getByTestId('wf-big-seas-l2').textContent).toBe(
      'Waves 4.1 m from NNE onto 3 piers and lighthouses · watch from high ground, not the pier',
    );
    expect(screen.getByTestId('wf-big-seas-go')).toHaveTextContent('On the sunset ›');
    expect(screen.getByTestId('wf-big-seas').dataset.form).toBe('full');
  });

  it('adds the gap clause ONLY when the reader is on the aligned event, and then has no action', () => {
    const { unmount } = renderLine({ evId: 'solar:2026-10-24:SUNRISE' });
    expect(screen.getByTestId('wf-big-seas-l1').textContent).not.toMatch(/before sunset/);
    unmount();
    renderLine({ evId: 'solar:2026-10-24:SUNSET' });
    expect(screen.getByTestId('wf-big-seas-l1').textContent)
      .toBe('15:30–18:15 · high water 16:58, 49 min before sunset');
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
  });

  it('names the one structure by its label when N = 1', () => {
    renderLine({ evId: 'solar:2026-10-25:SUNSET', days: [SUNDAY] });
    expect(screen.getByTestId('wf-big-seas-l2').textContent)
      .toBe('Waves 3.4 m from NE onto Roker Pier · watch from high ground, not the pier');
  });
});

describe('BigSeasLine: the compact form (a later day in the horizon)', () => {
  it('prints the day name, the window and "near sunset", with the See action', () => {
    renderLine({ evId: 'solar:2026-10-22:SUNSET' });
    expect(screen.getByTestId('wf-big-seas').dataset.form).toBe('compact');
    expect(screen.getByTestId('wf-big-seas-l1').textContent)
      .toBe('Saturday 15:30–18:15 · high water near sunset');
    expect(screen.getByTestId('wf-big-seas-l2').textContent)
      .toBe('Waves 4.1 m from NNE onto 3 piers and lighthouses');
    expect(screen.getByTestId('wf-big-seas-go')).toHaveTextContent('See Saturday ›');
  });

  it('draws no action when the list has no row for the aligned event', () => {
    const short = EVENTS.filter((r) => r.date !== '2026-10-25');
    render(
      <BigSeasLine bigSeas={[SUNDAY]} evRow={short[0]} events={short} onSelectEv={vi.fn()} />,
    );
    expect(screen.getByTestId('wf-big-seas-l1').textContent).toMatch(/^Sunday /);
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
  });
});

describe('BigSeasLine: the action', () => {
  it('selects the aligned EV row, after focus has moved to the line root', () => {
    const seen = [];
    const onSelectEv = vi.fn(() => seen.push(document.activeElement?.getAttribute('data-testid')));
    renderLine({ evId: 'solar:2026-10-22:SUNSET', onSelectEv });
    fireEvent.click(screen.getByTestId('wf-big-seas-go'));
    expect(onSelectEv).toHaveBeenCalledTimes(1);
    expect(onSelectEv).toHaveBeenCalledWith(row('solar:2026-10-24:SUNSET'));
    // The root held focus at the moment the jump ran, so a jump that unmounts the button strands none.
    expect(seen).toEqual(['wf-big-seas']);
    expect(document.activeElement).toBe(screen.getByTestId('wf-big-seas'));
  });

  it('keeps the root mounted and focused when the jump removes the action', () => {
    function Harness() {
      const [ev, setEv] = React.useState(row('solar:2026-10-24:SUNRISE'));
      return <BigSeasLine bigSeas={[SATURDAY]} evRow={ev} events={EVENTS} onSelectEv={setEv} />;
    }
    render(<Harness />);
    fireEvent.click(screen.getByTestId('wf-big-seas-go'));
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
    expect(screen.getByTestId('wf-big-seas-l1').textContent).toMatch(/49 min before sunset/);
    expect(document.activeElement).toBe(screen.getByTestId('wf-big-seas'));
  });
});

describe('BigSeasLine: the clock is the app clock', () => {
  it('under a Rewind past the Saturday window, the Saturday sunrise row reads the compact Sunday line', () => {
    // `Date` is faked at Thursday; only `appNow()` can see the Rewind. A line reading `new Date()`
    // would still say Saturday's full form here.
    setRewind('2026-10-24T18:30:00Z');
    renderLine({ evId: 'solar:2026-10-24:SUNRISE' });
    expect(screen.getByTestId('wf-big-seas').dataset.form).toBe('compact');
    expect(screen.getByTestId('wf-big-seas-l1').textContent).toMatch(/^Sunday 06:49/);
  });
});

describe('BigSeasLine: accessibility and absence', () => {
  it('is a labelled group, not a live status region, with the bolt and the badge words hidden from AT', () => {
    renderLine({ evId: 'solar:2026-10-24:SUNRISE' });
    const group = screen.getByRole('group', { name: 'Big seas' });
    expect(group).toBe(screen.getByTestId('wf-big-seas'));
    expect(group.getAttribute('tabindex')).toBe('-1');
    expect(screen.queryByRole('status')).toBeNull();
    // An inline SVG, not the U+26A1 emoji (which ignores `color` and shows yellow).
    const bolt = screen.getByTestId('wf-bolt');
    expect(bolt.tagName.toLowerCase()).toBe('svg');
    expect(bolt.getAttribute('aria-hidden')).toBe('true');
    expect(bolt.getAttribute('fill')).toBe('currentColor');
    expect(group.textContent).not.toContain('\u26A1');
    // "Big seas" is spoken once: by the group's label, not again by the badge.
    const badgeWords = screen.getByText('Big seas');
    expect(badgeWords.getAttribute('aria-hidden')).toBe('true');
  });

  it('names the structure-less sea when no structure is served (N = 0)', () => {
    renderLine({ evId: 'solar:2026-10-24:SUNRISE', days: [{ ...SATURDAY, structures: [] }] });
    expect(screen.getByTestId('wf-big-seas-l2').textContent)
      .toBe('Waves 4.1 m from NNE \u00B7 watch from high ground, not the pier');
  });

  it('renders nothing with no day served, or on a past row', () => {
    const { container, rerender } = render(
      <BigSeasLine bigSeas={undefined} evRow={row('solar:2026-10-24:SUNRISE')} events={EVENTS} />,
    );
    expect(container).toBeEmptyDOMElement();
    rerender(
      <BigSeasLine
        bigSeas={[SATURDAY]}
        evRow={{ ...row('solar:2026-10-24:SUNRISE'), past: true }}
        events={EVENTS}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('BigSeasLine: publishing the chrome height', () => {
  const OriginalRO = globalThis.ResizeObserver;
  let observers;
  let height;
  let original;

  beforeEach(() => {
    observers = [];
    height = 94;
    globalThis.ResizeObserver = class {
      constructor(cb) {
        this.cb = cb;
        this.observed = [];
        this.disconnected = false;
        observers.push(this);
      }

      observe(el) { this.observed.push(el); }

      disconnect() { this.disconnected = true; }
    };
    // jsdom has no layout: give the group a height, as the browser would measure it.
    original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'chrome' ? height : 0; },
    });
  });

  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  const frameOf = (evId) => (
    <div data-testid="frame">
      <div data-testid="chrome">
        <BigSeasLine bigSeas={[SATURDAY]} evRow={row(evId)} events={EVENTS} onSelectEv={vi.fn()} />
      </div>
    </div>
  );

  function renderInFrame(evId) {
    const utils = render(frameOf(evId));
    return { frame: screen.getByTestId('frame'), chrome: screen.getByTestId('chrome'), ...utils };
  }

  it('sets --wf-chrome-tl-h on the frame from the chrome group, follows resizes, and clears on unmount', () => {
    const { frame, unmount } = renderInFrame('solar:2026-10-24:SUNRISE');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('94px');
    height = 112;
    observers.forEach((o) => o.cb());
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('112px');
    unmount();
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
  });

  it('observes the chrome group itself, and disconnects on unmount and on hide', () => {
    const { chrome, unmount } = renderInFrame('solar:2026-10-24:SUNRISE');
    expect(observers).toHaveLength(1);
    expect(observers[0].observed).toEqual([chrome]);
    expect(observers[0].disconnected).toBe(false);
    unmount();
    expect(observers[0].disconnected).toBe(true);
  });

  it('follows shown -> hidden -> shown: the variable goes "" -> 94px -> "" -> 94px, each show with a fresh observer', () => {
    const { frame, rerender } = renderInFrame('solar:2026-10-26:SUNRISE');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
    expect(observers).toHaveLength(0);
    rerender(frameOf('solar:2026-10-24:SUNRISE'));
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('94px');
    expect(observers).toHaveLength(1);
    rerender(frameOf('solar:2026-10-26:SUNRISE'));
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
    expect(observers[0].disconnected).toBe(true);
    rerender(frameOf('solar:2026-10-24:SUNRISE'));
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('94px');
    expect(observers).toHaveLength(2);
  });

  it('publishes nothing while the group measures zero (a hidden pane)', () => {
    height = 0;
    const { frame } = renderInFrame('solar:2026-10-24:SUNRISE');
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('');
  });
});
