import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
vi.mock('../api/favouritesApi.js', () => ({ fetchFavourites: vi.fn(), setFavourite: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';
import FurtherAhead from '../components/FurtherAhead.jsx';
import { FavouritesContext, FavouritesProvider } from '../context/FavouritesContext.jsx';
import { favouritesValue, withFavourites } from './favouritesKit.jsx';

/**
 * Watching, the group at the top of Further ahead (screen-spec.md D2): the same entry cards, a watched entry MOVES
 * there, and every move hands focus on to a named successor (screen-spec-plan.md section 1).
 */

const TODAY = '2026-08-09';
const PERSEIDS = 'meteor:2026-08-12:2026-08-12';
const SPRING = 'spring-tide:2026-08-12:2026-08-13';
const MOON = 'supermoon:2026-08-20:2026-08-20';

const wireEntry = (over = {}) => ({
  id: PERSEIDS,
  type: 'meteor',
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  kind: 'ALMANAC',
  family: 'night-sky',
  title: 'Perseids',
  kindTag: 'Almanac',
  superlative: null,
  metric: '~100/hr',
  prose: null,
  facts: [],
  threshold: null,
  action: { label: 'Show dark-sky spots for 12 Aug →', kind: 'dark-sky-spots', date: '2026-08-12' },
  ...over,
});

const ENTRIES = [
  wireEntry(),
  wireEntry({
    id: SPRING, type: 'spring-tide', family: 'coastal', title: 'Spring tide run', metric: null,
    endDate: '2026-08-13', action: { label: 'Show coastal spots for 12 Aug →', kind: 'coastal-spots', date: '2026-08-12' },
  }),
  wireEntry({
    id: MOON, type: 'supermoon', family: 'sun-moon', title: 'Supermoon', metric: null, startDate: '2026-08-20',
    endDate: '2026-08-20', action: { label: 'Show coastal spots for 20 Aug →', kind: 'coastal-spots', date: '2026-08-20' },
  }),
];
const COUNTS = { fixed: 3, forecast: 0, byFamily: { 'night-sky': 1, coastal: 1, 'sun-moon': 1 } };

const pane = (props = {}) => (
  <FurtherAhead
    id="further-ahead-section"
    headingId="further-ahead-h"
    status="ready"
    events={{ entries: ENTRIES, counts: COUNTS }}
    todayStr={TODAY}
    onRetry={vi.fn()}
    onGoToPlan={vi.fn()}
    onShowOnMap={vi.fn()}
    {...props}
  />
);

const ids = (list) => within(list).getAllByTestId('coming-up-entry').map((entry) => entry.getAttribute('data-entry-id'));
const monthIds = () => screen.getAllByTestId('coming-up-list').flatMap((list) => ids(list));
const toggleOf = (entryId) => document.querySelector(`[data-entry-id="${entryId}"] [data-testid="coming-up-watch-toggle"]`);

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.clearAllMocks();
});

describe('Watching, drawn from the watched entries', () => {
  it('is not drawn when nothing is watched, for a Lite reader, or while the list has not landed', () => {
    for (const options of [{}, { status: 'locked' }, { status: 'loading' }, { status: 'error' }, { status: 'off' }]) {
      const { unmount } = render(withFavourites(pane(), options));
      expect(screen.queryByTestId('further-ahead-watching')).toBeNull();
      unmount();
    }
  });

  it('is an h3 with the mark and its own list of the SAME entry cards, before the chips', () => {
    render(withFavourites(pane(), { dates: ['spring-tide:2026-08-12'] }));
    const group = screen.getByTestId('further-ahead-watching');
    const heading = within(group).getByRole('heading', { level: 3, name: 'Watching' });
    expect(heading.querySelector('[data-testid="bookmark-mark"]')).not.toBeNull();
    const list = within(group).getByRole('group', { name: 'Watching' });
    expect(ids(list)).toEqual([SPRING]);
    expect(within(list).getByRole('list', { name: 'Watching, August 2026' })).toBeInTheDocument();
    // The same card as the month list draws: the panel, the toggle, the state in the words.
    expect(within(list).getByTestId('coming-up-card')).toBeInTheDocument();
    expect(within(list).getByTestId('coming-up-watch-toggle')).toHaveAccessibleName('Watching');
    // Above the filter chips.
    const chips = screen.getByTestId('coming-up-chips');
    expect(group.compareDocumentPosition(chips) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('MOVES the entry: it is in Watching and not in its month, so nothing shows twice', () => {
    render(withFavourites(pane(), { dates: ['spring-tide:2026-08-12'] }));
    expect(ids(screen.getByTestId('watching-list'))).toEqual([SPRING]);
    expect(monthIds()).toEqual([PERSEIDS, MOON]);
    expect(monthIds()).not.toContain(SPRING);
    expect(document.querySelectorAll(`[data-entry-id="${SPRING}"]`)).toHaveLength(1);
  });

  it('lists its entries soonest first, and a drifted stored key still finds its entry', () => {
    render(withFavourites(pane(), { dates: ['supermoon:2026-08-20', 'king-tide:2026-08-10'] }));
    // The king-tide watch drifted onto the spring-tide run (same family, within the tolerance).
    expect(ids(screen.getByTestId('watching-list'))).toEqual([SPRING, MOON]);
  });

  it('the filter chips neither hide Watching nor count it', () => {
    render(withFavourites(pane(), { dates: ['spring-tide:2026-08-12'] }));
    const count = (id) => screen.getAllByTestId('coming-up-chip').find((c) => c.getAttribute('data-chip') === id)
      .querySelector('.wf-cu-chip-count').textContent;
    expect(count('all')).toBe('2');
    expect(count('coastal')).toBe('0');
    fireEvent.click(screen.getAllByTestId('coming-up-chip').find((c) => c.getAttribute('data-chip') === 'night-sky'));
    // Night sky selected: the coastal tide run is still there, in Watching.
    expect(ids(screen.getByTestId('watching-list'))).toEqual([SPRING]);
    expect(monthIds()).toEqual([PERSEIDS]);
  });

  it('with every entry watched there is no month list and no false "nothing matches" line', () => {
    render(withFavourites(pane(), { dates: ['meteor:2026-08-12', 'spring-tide:2026-08-12', 'supermoon:2026-08-20'] }));
    expect(ids(screen.getByTestId('watching-list'))).toHaveLength(3);
    expect(screen.queryByTestId('coming-up-month')).toBeNull();
    // No chips for an empty month list either, and neither empty note: Watching shows.
    expect(screen.queryByTestId('coming-up-chips')).toBeNull();
    expect(screen.queryByTestId('coming-up-filter-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
  });

  it('an entry a lane draws (graduated) is neither listed nor in Watching', () => {
    render(withFavourites(pane({ graduatedIds: new Set([SPRING]) }), { dates: ['spring-tide:2026-08-12'] }));
    expect(screen.queryByTestId('further-ahead-watching')).toBeNull();
    expect(document.querySelector(`[data-entry-id="${SPRING}"]`)).toBeNull();
  });

  it('Open all and Close all include Watching\'s cards', () => {
    render(withFavourites(pane(), { dates: ['supermoon:2026-08-20'] }));
    // 20 Aug is under 30 days out, so it starts open; close all, then every card is closed, Watching's included.
    const toggleAll = screen.getByTestId('coming-up-toggle-all');
    expect(toggleAll).toHaveTextContent('Close all');
    fireEvent.click(toggleAll);
    expect(within(screen.getByTestId('watching-list')).queryByTestId('coming-up-watch')).toBeNull();
    expect(screen.getByTestId('coming-up-toggle-all')).toHaveTextContent('Open all');
    fireEvent.click(screen.getByTestId('coming-up-toggle-all'));
    expect(within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch')).toBeInTheDocument();
  });
});

describe('Watching, one key for one entry', () => {
  const KING = 'king-tide:2026-08-14:2026-08-15';
  const entries = [...ENTRIES, wireEntry({
    id: KING, type: 'king-tide', family: 'coastal', title: 'King tide run', metric: null, startDate: '2026-08-14',
    endDate: '2026-08-15', action: { label: 'Show coastal spots for 14 Aug →', kind: 'coastal-spots', date: '2026-08-14' },
  })];

  it('puts only the entry the key is bound to in Watching, and the other entry\'s toggle stays "Watch this date"', () => {
    render(withFavourites(pane({ events: { entries, counts: COUNTS } }), { dates: ['spring-tide:2026-08-12'] }));
    expect(ids(screen.getByTestId('watching-list'))).toEqual([SPRING]);
    expect(monthIds()).toContain(KING);
    expect(toggleOf(KING)).toHaveAccessibleName('Watch this date');
    expect(toggleOf(SPRING)).toHaveAccessibleName('Watching');
  });
});

describe('Watching, two overlapping runs each watched by their own key', () => {
  const EARLY = 'king-tide:2026-08-12:2026-08-16';
  const LATE = 'king-tide:2026-08-15:2026-08-18';
  const entries = [
    wireEntry({
      id: EARLY, type: 'king-tide', family: 'coastal', title: 'Early run', metric: null, endDate: '2026-08-16',
      action: { label: 'Show coastal spots for 12 Aug →', kind: 'coastal-spots', date: '2026-08-12' },
    }),
    wireEntry({
      id: LATE, type: 'king-tide', family: 'coastal', title: 'Late run', metric: null, startDate: '2026-08-15',
      endDate: '2026-08-18', action: { label: 'Show coastal spots for 15 Aug →', kind: 'coastal-spots', date: '2026-08-15' },
    }),
  ];
  const watched = (dates) => {
    render(withFavourites(pane({ events: { entries, counts: COUNTS } }), { dates }));
    return screen.queryByTestId('watching-list') ? ids(screen.getByTestId('watching-list')) : [];
  };

  it('the later run can be watched on its own: its key is its own, not the earlier run\'s', () => {
    expect(watched(['king-tide:2026-08-15'])).toEqual([LATE]);
    expect(toggleOf(EARLY)).toHaveAccessibleName('Watch this date');
    expect(toggleOf(LATE)).toHaveAccessibleName('Watching');
  });

  it('the earlier run on its own, and then both', () => {
    expect(watched(['king-tide:2026-08-12'])).toEqual([EARLY]);
    cleanup();
    expect(watched(['king-tide:2026-08-12', 'king-tide:2026-08-15'])).toEqual([EARLY, LATE]);
  });
});

describe('Watching, the watches the served feed no longer names', () => {
  const dates = ['meteor:2026-09-14', 'king-tide:2026-10-05'];

  it('is a quiet row each, [mark] title · no longer listed, with a real Stop watching button', () => {
    render(withFavourites(pane(), { dates }));
    const rows = screen.getAllByTestId('watching-unlisted');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(/^Meteor shower · , no longer listed/);
    expect(rows[0].querySelector('[data-testid="bookmark-mark"]')).not.toBeNull();
    expect(rows[0]).toHaveAttribute('role', 'listitem');
    expect(rows[1]).toHaveTextContent(/^King tide · , no longer listed/);
    const stop = within(rows[0]).getByRole('button', { name: 'Stop watching Meteor shower · 14 Sept' });
    expect(stop).toHaveTextContent('Stop watching');
  });

  it('is not drawn before the feed has arrived, and a served-but-graduated entry is never "no longer listed"', () => {
    render(withFavourites(pane({ status: 'loading', events: null }), { dates }));
    expect(screen.queryByTestId('watching-unlisted')).toBeNull();
    const { unmount } = render(withFavourites(
      pane({ graduatedIds: new Set([SPRING]) }), { dates: ['spring-tide:2026-08-12'] },
    ));
    expect(screen.queryByTestId('watching-unlisted')).toBeNull();
    unmount();
  });

  it('un-watches that key through the provider', () => {
    const value = favouritesValue({ dates });
    render(<FavouritesContext.Provider value={value}>{pane()}</FavouritesContext.Provider>);
    fireEvent.click(screen.getAllByTestId('watching-unlisted-stop')[0]);
    expect(value.setStar).toHaveBeenCalledWith('date', 'meteor:2026-09-14', 'Meteor shower · 14 Sept', false);
  });

  it('⚠️ removing a row focuses the next row\'s button; the last one goes back to the one before', () => {
    const { rerender } = render(withFavourites(pane(), { dates: [...dates, 'supermoon:2026-11-01'] }));
    const stops = () => screen.getAllByTestId('watching-unlisted-stop');
    // The provider is a spy, so the removal is applied by re-rendering with the shorter list.
    stops()[0].focus();
    fireEvent.click(stops()[0]);
    rerender(withFavourites(pane(), { dates: ['king-tide:2026-10-05', 'supermoon:2026-11-01'] }));
    expect(document.activeElement).toBe(stops()[0]);
    expect(document.activeElement.closest('[data-watch-key]')).toHaveAttribute('data-watch-key', 'king-tide:2026-10-05');
    fireEvent.click(stops()[1]);
    rerender(withFavourites(pane(), { dates: ['king-tide:2026-10-05'] }));
    expect(document.activeElement).toBe(stops()[0]);
  });

  it('⚠️ removing the only row, with cards in Watching, focuses the Watching heading', () => {
    const { rerender } = render(withFavourites(pane(), { dates: ['spring-tide:2026-08-12', 'meteor:2026-09-14'] }));
    fireEvent.click(screen.getByTestId('watching-unlisted-stop'));
    rerender(withFavourites(pane(), { dates: ['spring-tide:2026-08-12'] }));
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-watching-heading'));
  });

  it('⚠️ removing the last row of a group that has gone focuses the Further ahead heading, never the body', () => {
    const { rerender } = render(withFavourites(pane(), { dates: ['meteor:2026-09-14'] }));
    fireEvent.click(screen.getByTestId('watching-unlisted-stop'));
    rerender(withFavourites(pane(), { dates: [] }));
    expect(screen.queryByTestId('further-ahead-watching')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
  });
});

describe('Watching, focus when a card moves (the real provider)', () => {
  const served = (dates) => ({ places: [], areas: [], locationIds: [], dates: dates.map((key) => ({ key, createdAt: 't' })) });

  const mount = async (initial = []) => {
    fetchFavourites.mockResolvedValue(served(initial));
    render(<FavouritesProvider pro>{pane()}</FavouritesProvider>);
    await waitFor(() => expect(toggleOf(PERSEIDS)).not.toBeNull());
  };

  it('⚠️ pressing Watch in a month moves the card to Watching, and focus goes to its toggle there', async () => {
    setFavourite.mockResolvedValue(served(['meteor:2026-08-12']));
    await mount();
    expect(screen.queryByTestId('further-ahead-watching')).toBeNull();
    const before = toggleOf(PERSEIDS);
    before.focus();
    await act(async () => { fireEvent.click(before); });
    const list = screen.getByTestId('watching-list');
    expect(ids(list)).toEqual([PERSEIDS]);
    expect(monthIds()).not.toContain(PERSEIDS);
    // A different node (the card changed lists), and it holds focus.
    const after = within(list).getByTestId('coming-up-watch-toggle');
    expect(after).not.toBe(before);
    expect(after.isConnected).toBe(true);
    expect(document.activeElement).toBe(after);
    expect(after).toHaveAccessibleName('Watching');
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent("Watching Perseids · 12 Aug. It's at the top of Further ahead.");
  });

  it('⚠️ un-watching from Watching moves it back to its month, and focus follows it there', async () => {
    setFavourite.mockResolvedValue(served([]));
    await mount(['meteor:2026-08-12']);
    const inWatching = within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch-toggle');
    inWatching.focus();
    await act(async () => { fireEvent.click(inWatching); });
    expect(screen.queryByTestId('further-ahead-watching')).toBeNull();
    expect(monthIds()).toContain(PERSEIDS);
    expect(document.activeElement).toBe(toggleOf(PERSEIDS));
    expect(document.activeElement).toHaveAccessibleName('Watch this date');
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('Stopped watching Perseids · 12 Aug.');
  });

  it('un-watching an entry the filter hides lands on the nearest heading, not the body', async () => {
    localStorage.setItem('furtherAheadFilter', 'coastal');
    setFavourite.mockResolvedValue(served([]));
    await mount(['meteor:2026-08-12']);
    const inWatching = within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch-toggle');
    inWatching.focus();
    await act(async () => { fireEvent.click(inWatching); });
    expect(document.querySelector(`[data-entry-id="${PERSEIDS}"]`)).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
  });

  it('⚠️ Undo after a watch puts the card back in its month and resolves focus at Undo time, never to a detached node', async () => {
    setFavourite.mockResolvedValueOnce(served(['meteor:2026-08-12'])).mockResolvedValueOnce(served([]));
    await mount();
    const before = toggleOf(PERSEIDS);
    before.focus();
    await act(async () => { fireEvent.click(before); });
    const detached = before;
    expect(detached.isConnected).toBe(false);
    await act(async () => { fireEvent.click(screen.getByTestId('undo-toast-undo')); });
    expect(setFavourite).toHaveBeenLastCalledWith('date', 'meteor:2026-08-12', false);
    await waitFor(() => expect(screen.queryByTestId('further-ahead-watching')).toBeNull());
    expect(monthIds()).toContain(PERSEIDS);
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(toggleOf(PERSEIDS));
  });

  const remount = (props) => render(<FavouritesProvider pro>{pane(props)}</FavouritesProvider>);

  it('⚠️ a watch whose entry graduates to a lane as it lands: focus goes to the Further ahead heading, never the body', async () => {
    setFavourite.mockResolvedValue(served(['meteor:2026-08-12']));
    fetchFavourites.mockResolvedValue(served([]));
    const { rerender } = remount();
    await waitFor(() => expect(toggleOf(PERSEIDS)).not.toBeNull());
    const before = toggleOf(PERSEIDS);
    before.focus();
    await act(async () => { fireEvent.click(before); });
    expect(document.activeElement).toBe(within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch-toggle'));
    // The strip reports the lane a render later: the card unmounts, and the heading takes focus.
    await act(async () => {
      rerender(<FavouritesProvider pro>{pane({ graduatedIds: new Set([PERSEIDS]) })}</FavouritesProvider>);
    });
    expect(document.querySelector(`[data-entry-id="${PERSEIDS}"]`)).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
  });

  it('⚠️ Undo of that watch, with the entry still a lane, lands on the heading rather than a detached node', async () => {
    setFavourite.mockResolvedValueOnce(served(['meteor:2026-08-12'])).mockResolvedValueOnce(served([]));
    fetchFavourites.mockResolvedValue(served([]));
    const { rerender } = remount();
    await waitFor(() => expect(toggleOf(PERSEIDS)).not.toBeNull());
    const before = toggleOf(PERSEIDS);
    before.focus();
    await act(async () => { fireEvent.click(before); });
    await act(async () => {
      rerender(<FavouritesProvider pro>{pane({ graduatedIds: new Set([PERSEIDS]) })}</FavouritesProvider>);
    });
    await act(async () => { fireEvent.click(screen.getByTestId('undo-toast-undo')); });
    expect(setFavourite).toHaveBeenLastCalledWith('date', 'meteor:2026-08-12', false);
    expect(document.activeElement).toBe(screen.getByTestId('further-ahead-heading'));
  });

  it('a reader who has moved focus elsewhere is not pulled back when the entry graduates', async () => {
    setFavourite.mockResolvedValue(served(['meteor:2026-08-12']));
    fetchFavourites.mockResolvedValue(served([]));
    const { rerender } = remount();
    await waitFor(() => expect(toggleOf(PERSEIDS)).not.toBeNull());
    const before = toggleOf(PERSEIDS);
    before.focus();
    await act(async () => { fireEvent.click(before); });
    const elsewhere = screen.getByTestId('coming-up-toggle-all');
    elsewhere.focus();
    await act(async () => {
      rerender(<FavouritesProvider pro>{pane({ graduatedIds: new Set([PERSEIDS]) })}</FavouritesProvider>);
    });
    expect(document.activeElement).toBe(elsewhere);
  });

  it('a Lite reader\'s door press moves nothing and owes no focus', async () => {
    render(withFavourites(pane(), { status: 'locked' }));
    const door = document.querySelector(`[data-entry-id="${PERSEIDS}"] [data-testid="coming-up-watch-toggle-locked"]`);
    const elsewhere = screen.getByTestId('coming-up-toggle-all');
    fireEvent.click(door);
    elsewhere.focus();
    await act(async () => {});
    expect(document.activeElement).toBe(elsewhere);
  });

  it('Undo after an un-watch returns the card to Watching with focus on its toggle there', async () => {
    setFavourite.mockResolvedValueOnce(served([])).mockResolvedValueOnce(served(['meteor:2026-08-12']));
    await mount(['meteor:2026-08-12']);
    const inWatching = within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch-toggle');
    inWatching.focus();
    await act(async () => { fireEvent.click(inWatching); });
    await act(async () => { fireEvent.click(screen.getByTestId('undo-toast-undo')); });
    await waitFor(() => expect(screen.getByTestId('further-ahead-watching')).toBeInTheDocument());
    expect(document.activeElement).toBe(within(screen.getByTestId('watching-list')).getByTestId('coming-up-watch-toggle'));
  });

  it('Undo after removing an unlisted row restores the row and focuses its Stop watching button', async () => {
    setFavourite.mockResolvedValueOnce(served([])).mockResolvedValueOnce(served(['meteor:2026-09-14']));
    fetchFavourites.mockResolvedValue(served(['meteor:2026-09-14']));
    render(<FavouritesProvider pro>{pane()}</FavouritesProvider>);
    const stop = await screen.findByTestId('watching-unlisted-stop');
    stop.focus();
    await act(async () => { fireEvent.click(stop); });
    expect(screen.queryByTestId('watching-unlisted')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByTestId('undo-toast-undo')); });
    const back = await screen.findByTestId('watching-unlisted-stop');
    expect(document.activeElement).toBe(back);
  });
});

describe('Watching, a month rule above each month of watched rows', () => {
  const at = (type, family, startDate, endDate = startDate) => wireEntry({
    id: `${type}:${startDate}:${endDate}`,
    type,
    family,
    title: type,
    metric: null,
    startDate,
    endDate,
    action: { label: `Show spots for ${startDate} →`, kind: 'coastal-spots', date: startDate },
  });
  const DEC = at('supermoon', 'sun-moon', '2026-12-10');
  const CROSS = at('spring-tide', 'coastal', '2026-12-28', '2027-01-03');
  const JAN = at('meteor', 'night-sky', '2027-01-04');
  const FEB = at('supermoon', 'sun-moon', '2027-02-05');
  const feed = (...entries) => pane({ events: { entries, counts: COUNTS } });
  const rules = () => screen.getAllByTestId('watching-month').map((rule) => rule.textContent);
  const lists = () => screen.getAllByTestId('watching-month-list');
  const unlistedKeys = () => screen.getAllByTestId('watching-unlisted').map((row) => row.getAttribute('data-watch-key'));

  it('draws DEC 2026 and JAN 2027 rules, each above its own entries, with the lists named for the month', () => {
    render(withFavourites(feed(DEC, JAN), { dates: ['supermoon:2026-12-10', 'meteor:2027-01-04'] }));
    expect(rules()).toEqual(['Dec2026', 'Jan2027']);
    expect(lists().map((list) => list.getAttribute('aria-label'))).toEqual([
      'Watching, December 2026', 'Watching, January 2027',
    ]);
    expect(ids(lists()[0])).toEqual([DEC.id]);
    expect(ids(lists()[1])).toEqual([JAN.id]);
    const [decRule, janRule] = screen.getAllByTestId('watching-month');
    expect(decRule.compareDocumentPosition(lists()[0]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(lists()[0].compareDocumentPosition(janRule) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(decRule).toHaveAttribute('data-first', 'true');
    expect(janRule).not.toHaveAttribute('data-first');
    expect(screen.getByRole('group', { name: 'Watching' })).toBe(screen.getByTestId('watching-list'));
  });

  it('still draws its rule when every watched row is in one month', () => {
    render(withFavourites(feed(DEC, JAN), { dates: ['supermoon:2026-12-10'] }));
    expect(rules()).toEqual(['Dec2026']);
    expect(ids(lists()[0])).toEqual([DEC.id]);
  });

  it('puts a run that crosses a month under the month it starts in', () => {
    render(withFavourites(feed(CROSS, JAN), { dates: ['spring-tide:2026-12-28'] }));
    expect(rules()).toEqual(['Dec2026']);
    expect(ids(lists()[0])).toEqual([CROSS.id]);
  });

  it('puts a "no longer listed" row in its own date\'s month, after that month\'s cards', () => {
    render(withFavourites(feed(DEC), { dates: ['supermoon:2026-12-10', 'meteor:2026-12-20'] }));
    expect(rules()).toEqual(['Dec2026']);
    const children = [...lists()[0].children].map((node) => node.getAttribute('data-testid'));
    expect(children).toEqual(['coming-up-entry', 'watching-unlisted']);
    expect(unlistedKeys()).toEqual(['meteor:2026-12-20']);
  });

  it('gives an unlisted-only month its rule, even BETWEEN two card months, in order', () => {
    render(withFavourites(feed(DEC, FEB), {
      dates: ['supermoon:2026-12-10', 'meteor:2027-01-20', 'supermoon:2027-02-05'],
    }));
    expect(rules()).toEqual(['Dec2026', 'Jan2027', 'Feb2027']);
    expect(ids(lists()[0])).toEqual([DEC.id]);
    expect(within(lists()[1]).queryAllByTestId('coming-up-entry')).toHaveLength(0);
    expect(within(lists()[1]).getByTestId('watching-unlisted')).toHaveAttribute('data-watch-key', 'meteor:2027-01-20');
    expect(ids(lists()[2])).toEqual([FEB.id]);
  });

  it('leaves the month lists and their test ids alone', () => {
    render(withFavourites(feed(DEC, JAN, FEB), { dates: ['supermoon:2026-12-10', 'meteor:2027-01-04'] }));
    expect(monthIds()).toEqual([FEB.id]);
    expect(screen.getAllByTestId('coming-up-month')).toHaveLength(1);
    expect(screen.getAllByTestId('coming-up-month')[0]).toHaveAttribute('data-first', 'true');
    expect(screen.getAllByTestId('coming-up-list')).toHaveLength(1);
    expect(screen.getAllByTestId('watching-month')).toHaveLength(2);
  });

  it('Stop watching on an unlisted row in the second month focuses the next unlisted row down the page', () => {
    const initial = ['meteor:2026-12-20', 'meteor:2027-01-20', 'king-tide:2027-01-25', 'meteor:2027-02-20'];
    const { rerender } = render(withFavourites(feed(DEC), { dates: initial }));
    expect(rules()).toEqual(['Dec2026', 'Jan2027', 'Feb2027']);
    const stop = screen.getAllByTestId('watching-unlisted-stop')[1];
    stop.focus();
    fireEvent.click(stop);
    expect(screen.getAllByTestId('watching-unlisted')[1]).toHaveAttribute('data-watch-key', 'meteor:2027-01-20');
    rerender(withFavourites(feed(DEC), { dates: initial.filter((key) => key !== 'meteor:2027-01-20') }));
    expect(document.activeElement.closest('[data-watch-key]')).toHaveAttribute('data-watch-key', 'king-tide:2027-01-25');
  });
});
