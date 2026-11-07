import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import { COMING_UP_OPEN_KEY } from '../utils/comingUpOpen.js';

/**
 * Closable Coming up entries (collapse-and-topic-pips-plan.md §5): the 30-day default, the "In next 4 days"
 * exception, the per-entry button, Open all / Close all, the session override and the deep link.
 */
const TODAY = '2026-11-03';

const wireEntry = (over = {}) => ({
  id: 'meteor:2026-11-17:2026-11-17',
  type: 'meteor',
  startDate: '2026-11-17',
  endDate: '2026-11-17',
  kind: 'ALMANAC',
  family: 'night-sky',
  title: 'Leonids',
  kindTag: 'Almanac',
  superlative: null,
  metric: null,
  prose: 'The Leonids peak, a few meteors an hour.',
  facts: [],
  threshold: null,
  action: { label: 'Show dark-sky spots for 17 Nov', kind: 'dark-sky-spots', date: '2026-11-17' },
  ...over,
});

const NEAR = wireEntry({ id: 'near', title: 'Near entry', startDate: '2026-11-05', endDate: '2026-11-05', prose: 'Near prose.' });
const SOON = wireEntry({ id: 'soon', title: 'Within thirty', startDate: '2026-12-03', endDate: '2026-12-03', prose: 'Soon prose.' });
const LATER = wireEntry({ id: 'later', title: 'Later entry', startDate: '2026-12-23', endDate: '2026-12-23', prose: 'Later prose.' });
const FAR = wireEntry({ id: 'far', title: 'Far entry', startDate: '2027-02-10', endDate: '2027-02-10', prose: 'Far prose.' });
const COUNTS = { fixed: 4, forecast: 0, byFamily: { 'night-sky': 4 } };

const renderPane = (props = {}) => render(
  <WindowFirstComingUp
    id="window-first-panel-coming-up"
    labelledBy="window-first-tab-coming-up"
    status="ready"
    events={{ entries: [NEAR, SOON, LATER, FAR], counts: COUNTS }}
    todayStr={TODAY}
    onRetry={vi.fn()}
    onGoToPlan={vi.fn()}
    onShowOnMap={vi.fn()}
    {...props}
  />,
);

const entryEl = (id) => document.querySelector(`[data-entry-id="${id}"]`);
const toggleOf = (id) => entryEl(id).querySelector('[data-testid="coming-up-toggle"]');

beforeEach(() => { sessionStorage.clear(); });
afterEach(() => { sessionStorage.clear(); });

describe('Coming up — the default open state', () => {
  it('opens entries starting within 30 days and closes later ones', () => {
    renderPane();
    expect(entryEl('near')).toHaveAttribute('data-open', 'true');
    // 3 Dec is exactly 30 days from 3 Nov.
    expect(entryEl('soon')).toHaveAttribute('data-open', 'true');
    expect(entryEl('later')).toHaveAttribute('data-open', 'false');
    expect(entryEl('far')).toHaveAttribute('data-open', 'false');
  });

  it('always opens an entry tagged "In next 4 days", whatever its start', () => {
    // A run that started long ago and is still on: it overlaps the next four days.
    const run = wireEntry({
      id: 'run', title: 'Long run', startDate: '2026-10-01', endDate: '2027-03-01', prose: 'Run prose.',
    });
    renderPane({ events: { entries: [run, FAR], counts: COUNTS } });
    expect(entryEl('run')).toHaveAttribute('data-open', 'true');
    expect(entryEl('run')).toHaveTextContent('In next 4 days');
    expect(entryEl('far')).toHaveAttribute('data-open', 'false');
  });

  it('shows only the header line of a closed entry', () => {
    renderPane();
    const closed = entryEl('far');
    expect(closed).toHaveTextContent('Far entry');
    expect(closed).not.toHaveTextContent('Far prose.');
    expect(closed.querySelector('[data-testid="coming-up-action"]')).toBeNull();
    expect(closed.querySelector('[data-testid="coming-up-countdown"]')).toBeNull();
    expect(closed.querySelector('.wf-cu-mo')).toBeNull();
    expect(closed.querySelector('.wf-cu-dow')).toBeNull();
    // The date box keeps the day.
    expect(closed.querySelector('.wf-cu-dn')).toHaveTextContent('10');
    expect(entryEl('near')).toHaveTextContent('Near prose.');
    expect(entryEl('near').querySelector('.wf-cu-mo')).not.toBeNull();
  });
});

describe('Coming up — the per-entry button', () => {
  it('flips between Close and Open', () => {
    renderPane();
    expect(toggleOf('near')).toHaveAccessibleName('Close');
    expect(toggleOf('near')).toHaveAttribute('aria-expanded', 'true');
    expect(toggleOf('far')).toHaveAccessibleName('Open');
    expect(toggleOf('far')).toHaveTextContent('Open ▴');
    expect(toggleOf('far')).toHaveAttribute('aria-expanded', 'false');
  });

  it('is a child of the entry beside the card, never inside it (a single-action card is itself a button)', () => {
    renderPane();
    expect(toggleOf('near').closest('[data-testid="coming-up-card"]')).toBeNull();
    expect(toggleOf('near').parentElement).toBe(entryEl('near'));
  });

  it('opens a closed entry and closes an open one', () => {
    renderPane();
    fireEvent.click(toggleOf('far'));
    expect(entryEl('far')).toHaveAttribute('data-open', 'true');
    expect(entryEl('far')).toHaveTextContent('Far prose.');
    fireEvent.click(toggleOf('near'));
    expect(entryEl('near')).toHaveAttribute('data-open', 'false');
    expect(entryEl('near')).not.toHaveTextContent('Near prose.');
  });

  it('keeps the choice for the session in sessionStorage, keyed by entry id', () => {
    const { unmount } = renderPane();
    fireEvent.click(toggleOf('far'));
    fireEvent.click(toggleOf('near'));
    expect(JSON.parse(sessionStorage.getItem(COMING_UP_OPEN_KEY))).toMatchObject({ far: true, near: false });
    unmount();
    renderPane();
    expect(entryEl('far')).toHaveAttribute('data-open', 'true');
    expect(entryEl('near')).toHaveAttribute('data-open', 'false');
    expect(entryEl('later')).toHaveAttribute('data-open', 'false');
  });

  it('works when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    renderPane();
    fireEvent.click(toggleOf('far'));
    expect(entryEl('far')).toHaveAttribute('data-open', 'true');
    vi.restoreAllMocks();
  });
});

describe('Coming up — Open all / Close all', () => {
  const allButton = () => screen.getByTestId('coming-up-toggle-all');

  it('reads Open all while any entry is closed, and opens every entry', () => {
    renderPane();
    expect(allButton()).toHaveTextContent('Open all');
    fireEvent.click(allButton());
    for (const id of ['near', 'soon', 'later', 'far']) {
      expect(entryEl(id)).toHaveAttribute('data-open', 'true');
    }
    expect(allButton()).toHaveTextContent('Close all');
  });

  it('closes every entry from Close all, even those tagged in the next four days', () => {
    renderPane();
    fireEvent.click(allButton());
    fireEvent.click(allButton());
    for (const id of ['near', 'soon', 'later', 'far']) {
      expect(entryEl(id)).toHaveAttribute('data-open', 'false');
    }
    expect(allButton()).toHaveTextContent('Open all');
  });

  it('reads Close all straight away when every entry starts open', () => {
    renderPane({ events: { entries: [NEAR, SOON], counts: COUNTS } });
    expect(allButton()).toHaveTextContent('Close all');
  });

  it('flips to Close all when the reader opens the last closed entry by hand', () => {
    renderPane({ events: { entries: [NEAR, FAR], counts: COUNTS } });
    expect(allButton()).toHaveTextContent('Open all');
    fireEvent.click(toggleOf('far'));
    expect(allButton()).toHaveTextContent('Close all');
  });

  it('draws no button when there is nothing to open', () => {
    renderPane({ events: { entries: [], counts: { fixed: 0, forecast: 0, byFamily: {} } } });
    expect(screen.queryByTestId('coming-up-toggle-all')).toBeNull();
  });
});

describe('Coming up — a deep link opens a closed target first', () => {
  const originalScroll = Element.prototype.scrollIntoView;
  beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });
  afterEach(() => { Element.prototype.scrollIntoView = originalScroll; });

  it('opens the closed entry before the scroll and reports it handled', () => {
    const onFocusHandled = vi.fn();
    renderPane({ focusRequest: { entryId: 'far', seq: 1 }, onFocusHandled });
    expect(entryEl('far')).toHaveAttribute('data-open', 'true');
    expect(entryEl('far')).toHaveTextContent('Far prose.');
    expect(onFocusHandled).toHaveBeenCalledWith(true);
    expect(entryEl('far').scrollIntoView).toBeDefined();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('leaves other closed entries closed', () => {
    renderPane({ focusRequest: { entryId: 'far', seq: 1 }, onFocusHandled: vi.fn() });
    expect(entryEl('later')).toHaveAttribute('data-open', 'false');
  });

  it('keeps an already-open target open', () => {
    renderPane({ focusRequest: { entryId: 'near', seq: 1 }, onFocusHandled: vi.fn() });
    expect(entryEl('near')).toHaveAttribute('data-open', 'true');
  });
});
