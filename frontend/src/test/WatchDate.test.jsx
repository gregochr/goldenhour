import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
vi.mock('../api/favouritesApi.js', () => ({ fetchFavourites: vi.fn(), setFavourite: vi.fn() }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';
import WatchDateControl from '../components/WatchDateControl.jsx';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';
import YourPlacesRow, { YOUR_PLACES_EMPTY } from '../components/YourPlacesRow.jsx';
import { FavouritesContext, FavouritesProvider, useFavourites } from '../context/FavouritesContext.jsx';
import { buildEntryView } from '../utils/comingUpFeed.js';
import { favouritesValue, withFavourites } from './favouritesKit.jsx';

/** Watch a date (one-screen-plan.md D16): the toggle in an open Further ahead entry, and the chips in Your places. */

const TODAY = '2026-12-02';

/** A served almanac entry. */
const served = (over = {}) => ({
  id: 'king-tide:2026-12-03:2026-12-07',
  type: 'king-tide',
  family: 'coastal',
  kind: 'ALMANAC',
  title: 'King tide',
  kindTag: 'Almanac',
  startDate: '2026-12-03',
  endDate: '2026-12-07',
  action: { label: 'Show coastal spots', kind: 'coastal-spots', date: '2026-12-03' },
  facts: [],
  ...over,
});
const view = (over = {}) => buildEntryView(served(over), TODAY, null, null);

const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`, date, targetType, dow: 'Fri', sunrise: targetType === 'SUNRISE', time: '07:48', away: false, ...over,
});

beforeEach(() => { mockIsMobile = false; vi.clearAllMocks(); });

describe('Watch this date, the toggle in an entry', () => {
  const entry = () => view();
  const toggle = () => screen.getByTestId('coming-up-watch-toggle');

  it('⚠️ a real button with one constant label and aria-pressed, for a Pro reader whose list has landed', () => {
    const { rerender } = render(withFavourites(<WatchDateControl entry={entry()} />));
    expect(toggle().tagName).toBe('BUTTON');
    expect(toggle()).toHaveAccessibleName('Watch this date');
    expect(toggle()).toHaveAttribute('aria-pressed', 'false');
    expect(toggle()).toHaveTextContent(/☆\s*Watch this date/);
    rerender(withFavourites(<WatchDateControl entry={entry()} />, { dates: ['king-tide:2026-12-03'] }));
    expect(toggle()).toHaveAttribute('aria-pressed', 'true');
    expect(toggle()).toHaveAccessibleName('Watch this date');
    expect(toggle()).toHaveTextContent(/★\s*Watch this date/);
  });

  it('the words stay on a phone: it sits in an entry, not a header', () => {
    mockIsMobile = true;
    render(withFavourites(<WatchDateControl entry={entry()} />));
    expect(within(toggle()).getByText('Watch this date')).not.toHaveClass('sr-only');
  });

  it('watching presses through the provider with the entry\'s own key and a name for the toast', () => {
    const value = favouritesValue({});
    render(<FavouritesContext.Provider value={value}><WatchDateControl entry={entry()} /></FavouritesContext.Provider>);
    fireEvent.click(toggle());
    expect(value.toggle).toHaveBeenCalledWith('date', 'king-tide:2026-12-03', 'King tide · 3 Dec');
    expect(value.setStar).not.toHaveBeenCalled();
  });

  it('a stored watch that drifted still reads as pressed, and un-watching removes the STORED key', () => {
    const value = favouritesValue({ dates: ['spring-tide:2026-12-01'] });
    render(<FavouritesContext.Provider value={value}><WatchDateControl entry={entry()} /></FavouritesContext.Provider>);
    expect(toggle()).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(toggle());
    expect(value.setStar).toHaveBeenCalledWith(
      'date', 'spring-tide:2026-12-01', 'King tide · 3 Dec', false, false, ['spring-tide:2026-12-01'],
    );
    expect(value.toggle).not.toHaveBeenCalled();
  });

  it('⚠️ a Lite reader gets the control with the Pro pill and a date-worded upsell, never a link', () => {
    render(withFavourites(<WatchDateControl entry={entry()} />, { status: 'locked' }));
    const door = screen.getByTestId('coming-up-watch-toggle-locked');
    expect(door).toHaveTextContent('Watch this date');
    expect(door).toHaveTextContent('Pro');
    expect(door).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(door);
    const panel = screen.getByTestId('coming-up-watch-upsell-panel');
    expect(panel).toHaveTextContent('Watch a date');
    expect(panel).toHaveTextContent(/joins your places at the top of This week/);
    expect(panel.querySelector('a')).toBeNull();
    expect(panel.textContent).not.toMatch(/Your places\b.*places and areas/);
    fireEvent.keyDown(panel, { key: 'Escape' });
    expect(screen.queryByTestId('coming-up-watch-upsell-panel')).toBeNull();
  });

  it('draws nothing until favourites are known, with no provider, or for an entry that cannot be watched', () => {
    for (const status of ['off', 'loading', 'error']) {
      const { unmount } = render(withFavourites(<WatchDateControl entry={entry()} />, { status }));
      expect(screen.queryByTestId('coming-up-watch')).toBeNull();
      unmount();
    }
    render(withFavourites(<WatchDateControl entry={{ ...entry(), warning: {} }} />));
    expect(screen.queryByTestId('coming-up-watch')).toBeNull();
  });
});

describe('Watch this date, in the entry', () => {
  const renderEntry = (props = {}, options = {}) => render(withFavourites(
    <WindowComingUpEntry
      entry={view()}
      onGoToPlan={vi.fn()}
      onShowOnMap={vi.fn()}
      open
      onToggleOpen={vi.fn()}
      {...props}
    />,
    options,
  ));

  it('⚠️ is a sibling of the card and of the Open / Close button, never inside either', () => {
    renderEntry();
    const watch = screen.getByTestId('coming-up-watch-toggle');
    expect(screen.getByTestId('coming-up-card').contains(watch)).toBe(false);
    expect(screen.getByTestId('coming-up-toggle').contains(watch)).toBe(false);
    expect(watch.closest('[data-testid="coming-up-entry"]')).toBe(screen.getByTestId('coming-up-entry'));
    // A button inside a button would be invalid: the card here is itself a button when it has one action.
    expect(watch.closest('button[data-testid="coming-up-card"]')).toBeNull();
  });

  it('a closed entry has none', () => {
    renderEntry({ open: false });
    expect(screen.queryByTestId('coming-up-watch')).toBeNull();
  });
});

describe('the watch in the provider', () => {
  const spy = { current: null };
  function Spy() {
    const f = useFavourites();
    React.useEffect(() => { spy.current = f; });
    return <span data-testid="spy-status">{f.status}</span>;
  }

  it('watches optimistically through the date path, takes the served list, and says so with an Undo', async () => {
    fetchFavourites.mockResolvedValue({ places: [], areas: [], locationIds: [], dates: [] });
    setFavourite.mockResolvedValue({
      places: [], areas: [], locationIds: [], dates: [{ key: 'king-tide:2026-12-03', createdAt: 't' }],
    });
    render(<FavouritesProvider pro><Spy /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    expect(spy.current.isDateWatched('king-tide:2026-12-03')).toBe(false);

    await act(async () => { await spy.current.toggle('date', 'king-tide:2026-12-03', 'King tide · 3 Dec'); });

    expect(setFavourite).toHaveBeenCalledWith('date', 'king-tide:2026-12-03', true);
    expect(spy.current.isDateWatched('king-tide:2026-12-03')).toBe(true);
    expect(spy.current.dates).toEqual([{ key: 'king-tide:2026-12-03', createdAt: 't' }]);
    expect(spy.current.locationIds.size).toBe(0);
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('Watching King tide · 3 Dec. It joins your places.');
  });

  it('a refused watch at the cap reverts and prints the server sentence', async () => {
    fetchFavourites.mockResolvedValue({ places: [], areas: [], locationIds: [], dates: [] });
    setFavourite.mockRejectedValue({ response: { status: 409, data: { error: 'You can keep up to 25 places and dates.' } } });
    render(<FavouritesProvider pro><Spy /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));

    await act(async () => { await spy.current.toggle('date', 'king-tide:2026-12-03', 'King tide · 3 Dec'); });

    expect(spy.current.isDateWatched('king-tide:2026-12-03')).toBe(false);
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent('You can keep up to 25 places and dates.');
  });

  it('⚠️ un-watching several matched keys is one press with one Undo that puts every one of them back', async () => {
    const both = { places: [], areas: [], locationIds: [], dates: [{ key: 'king-tide:2026-12-03' }, { key: 'spring-tide:2026-12-01' }] };
    fetchFavourites.mockResolvedValue(both);
    setFavourite.mockResolvedValue({ places: [], areas: [], locationIds: [], dates: [] });
    render(<FavouritesProvider pro><Spy /></FavouritesProvider>);
    await waitFor(() => expect(screen.getByTestId('spy-status')).toHaveTextContent('ready'));
    const group = ['king-tide:2026-12-03', 'spring-tide:2026-12-01'];

    await act(async () => {
      await Promise.all(group.map((key) => spy.current.setStar('date', key, 'King tide · 3 Dec', false, false, group)));
    });
    expect(setFavourite).toHaveBeenCalledTimes(2);
    await act(async () => { fireEvent.click(screen.getByTestId('undo-toast-undo')); });

    expect(setFavourite).toHaveBeenCalledTimes(4);
    expect(setFavourite).toHaveBeenCalledWith('date', 'king-tide:2026-12-03', true);
    expect(setFavourite).toHaveBeenCalledWith('date', 'spring-tide:2026-12-01', true);
  });
});

describe('Your places, watched dates', () => {
  const ENTRIES = [served()];
  const WINDOWS = [
    card('2026-12-03', 'SUNRISE', { dow: 'Thu', tide: { lightAlignment: 'NONE' } }),
    card('2026-12-04', 'SUNRISE', {
      tide: { lightAlignment: 'HW' }, bestReach: { rating: 4, locationId: 7, locationName: 'Crimdon Beach' },
    }),
  ];
  const mount = (options, extra = {}) => {
    const props = {
      spots: [{ id: 7, name: 'Crimdon Beach' }],
      windows: [],
      scoreIndex: null,
      reachById: new Map(),
      allowedLocationIds: null,
      onOpenWindow: vi.fn(),
      onOpenPlace: vi.fn(),
      onOpenArea: vi.fn(),
      onOpenEntry: vi.fn(),
      graduatedIds: undefined,
      entries: ENTRIES,
      hotTopics: [],
      todayStr: TODAY,
      ...extra,
    };
    render(withFavourites(<YourPlacesRow {...props} />, options));
    return props;
  };

  it('beyond the forecast the chip names the date and the countdown, and a tap opens its entry in Further ahead', () => {
    const props = mount({ dates: ['king-tide:2026-12-03'] });
    const chip = screen.getByTestId('your-places-chip');
    expect(chip).toHaveAttribute('data-kind', 'date');
    expect(chip).toHaveAttribute('data-date-state', 'beyond');
    expect(chip).toHaveTextContent('Watched date: 🌊 King tide · 3 Dec');
    expect(screen.getByTestId('your-places-detail')).toHaveTextContent("tomorrow · best spots once it's forecast");
    fireEvent.click(chip);
    expect(props.onOpenEntry).toHaveBeenCalledWith('king-tide:2026-12-03:2026-12-07');
    expect(props.onOpenWindow).not.toHaveBeenCalled();
  });

  it('inside the forecast the chip is that morning\'s plan, and a tap opens the map view on that window', () => {
    const props = mount({ dates: ['king-tide:2026-12-03'] }, { windows: WINDOWS });
    const chip = screen.getByTestId('your-places-chip');
    expect(chip).toHaveAttribute('data-date-state', 'window');
    expect(chip).toHaveTextContent('King tide · Fri');
    expect(screen.getByTestId('your-places-detail').textContent)
      .toBe('sunrise 07:48 · best Crimdon Beach 4★ · tide on the light');
    fireEvent.click(chip);
    expect(props.onOpenWindow).toHaveBeenCalledWith({
      window: { date: '2026-12-04', eventType: 'SUNRISE' },
      focus: { kind: 'location', id: 7, name: 'Crimdon Beach' },
      from: chip,
    });
    expect(props.onOpenEntry).not.toHaveBeenCalled();
  });

  it('a window with no rated spot opens the map on the window alone', () => {
    const windows = [card('2026-12-04', 'SUNRISE', { tide: { lightAlignment: 'HW' } })];
    const props = mount({ dates: ['king-tide:2026-12-03'] }, { windows });
    fireEvent.click(screen.getByTestId('your-places-chip'));
    expect(props.onOpenWindow).toHaveBeenCalledWith(expect.objectContaining({
      window: { date: '2026-12-04', eventType: 'SUNRISE' }, focus: null,
    }));
  });

  it('⚠️ a date with no window of its own is a chip that is not a button, and opens nothing', () => {
    const meteor = served({
      id: 'meteor:2026-12-04', type: 'meteor', family: 'night-sky', title: 'Geminids',
      startDate: '2026-12-04', endDate: '2026-12-04',
    });
    const props = mount({ dates: ['meteor:2026-12-04'] }, { entries: [meteor], windows: [card('2026-12-04', 'SUNSET')] });
    const chip = screen.getByTestId('your-places-chip');
    expect(chip.tagName).not.toBe('BUTTON');
    expect(chip).toHaveAttribute('data-date-state', 'static');
    expect(screen.getByTestId('your-places-detail')).toHaveTextContent('clear-sky check on the night');
    fireEvent.click(chip);
    expect(props.onOpenEntry).not.toHaveBeenCalled();
    expect(props.onOpenWindow).not.toHaveBeenCalled();
  });

  it('is the reader\'s own sections: dates first, then areas, then places, and a date is never an empty row', () => {
    mount({ dates: ['king-tide:2026-12-03'], places: [7] });
    const chips = screen.getAllByTestId('your-places-chip');
    expect(chips.map((c) => c.dataset.kind)).toEqual(['date', 'place']);
    expect(screen.queryByTestId('your-places-empty')).toBeNull();
  });

  it('a reader with nothing starred or watched still gets the advice, which now names dates', () => {
    mount({});
    expect(screen.getByTestId('your-places-empty')).toHaveTextContent(YOUR_PLACES_EMPTY);
    expect(YOUR_PLACES_EMPTY).toMatch(/watch a date/);
  });

  it('a watched date with no feed yet says so, rather than the starred-places line', () => {
    mount({ dates: ['king-tide:2026-12-03'] }, { entries: null });
    expect(screen.queryByTestId('your-places-chip')).toBeNull();
    expect(screen.getByTestId('your-places-offplan')).toHaveTextContent('Your watched dates are not listed right now.');
  });

  it('a starred place that is off the plan keeps the original line beside a watched date', () => {
    mount({ dates: ['king-tide:2026-12-03'], places: [99] }, { entries: null });
    expect(screen.getByTestId('your-places-offplan')).toHaveTextContent('Your starred places are not on the plan right now.');
  });

  it('a Lite reader still gets the one-line teaser and no chips', () => {
    mount({ status: 'locked' });
    expect(screen.getByTestId('your-places-teaser-button')).toBeInTheDocument();
    expect(screen.queryByTestId('your-places-chip')).toBeNull();
  });

  it('⚠️ every date chip has its own stop-watching button, a sibling outside the chip, that un-watches that key', () => {
    const value = favouritesValue({ dates: ['king-tide:2026-12-03'] });
    render(<FavouritesContext.Provider value={value}><YourPlacesRow
      spots={[]} windows={[]} scoreIndex={null} reachById={new Map()} allowedLocationIds={null}
      onOpenWindow={vi.fn()} onOpenPlace={vi.fn()} onOpenArea={vi.fn()} entries={ENTRIES} hotTopics={[]} todayStr={TODAY}
    /></FavouritesContext.Provider>);
    const stop = screen.getByTestId('your-places-stop-watching');
    expect(stop).toHaveAccessibleName('Stop watching King tide · 3 Dec');
    expect(screen.getByTestId('your-places-chip').contains(stop)).toBe(false);
    fireEvent.click(stop);
    expect(value.setStar).toHaveBeenCalledWith('date', 'king-tide:2026-12-03', 'King tide · 3 Dec', false);
  });

  it('the stop button is on a window chip, a static chip and an unlisted chip too, and not on a place', () => {
    const meteor = served({ id: 'meteor:2026-12-04', type: 'meteor', title: 'Geminids', startDate: '2026-12-04', endDate: '2026-12-04' });
    mount(
      { dates: ['king-tide:2026-12-03', 'meteor:2026-12-04', 'supermoon:2026-12-20'], places: [7] },
      { entries: [...ENTRIES, meteor], windows: [...WINDOWS, card('2026-12-04', 'SUNSET')] },
    );
    const states = screen.getAllByTestId('your-places-chip').map((c) => c.dataset.dateState ?? c.dataset.kind);
    expect(states).toEqual(['window', 'static', 'unlisted', 'place']);
    expect(screen.getAllByTestId('your-places-stop-watching')).toHaveLength(3);
    const unlisted = screen.getAllByTestId('your-places-chip')[2];
    expect(unlisted.tagName).not.toBe('BUTTON');
    expect(unlisted).toHaveTextContent('Supermoon · 20 Dec');
    expect(unlisted).toHaveTextContent('no longer listed');
  });

  it('a beyond chip whose entry is a lane now opens the map on its first window, never Further ahead', () => {
    const windows = [card('2026-12-03', 'SUNSET', { tide: { lightAlignment: 'NONE' } })];
    const props = mount({ dates: ['king-tide:2026-12-03'] }, { windows, graduatedIds: new Set([ENTRIES[0].id]) });
    fireEvent.click(screen.getByTestId('your-places-chip'));
    expect(props.onOpenWindow).toHaveBeenCalledWith(expect.objectContaining({
      window: { date: '2026-12-03', eventType: 'SUNSET' }, focus: null,
    }));
    expect(props.onOpenEntry).not.toHaveBeenCalled();
  });

  it('a graduated beyond chip with no window yet still opens its entry', () => {
    const props = mount({ dates: ['king-tide:2026-12-03'] }, { graduatedIds: new Set([ENTRIES[0].id]) });
    fireEvent.click(screen.getByTestId('your-places-chip'));
    expect(props.onOpenEntry).toHaveBeenCalledWith(ENTRIES[0].id);
  });

  it('a tap with no handler to open the entry does nothing and does not throw', () => {
    mount({ dates: ['king-tide:2026-12-03'] }, { onOpenEntry: undefined });
    expect(() => fireEvent.click(screen.getByTestId('your-places-chip'))).not.toThrow();
  });
});
