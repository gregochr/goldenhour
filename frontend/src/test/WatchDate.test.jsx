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
import { FavouritesContext, FavouritesProvider, useFavourites } from '../context/FavouritesContext.jsx';
import { buildEntryView } from '../utils/comingUpFeed.js';
import { favouritesValue, withFavourites } from './favouritesKit.jsx';

/** Watch a date (one-screen-plan.md D16): the toggle in an open Further ahead entry. Where a watched date lives (Watching, the lane) is in FurtherAheadWatching.test.jsx. */

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

beforeEach(() => { mockIsMobile = false; vi.clearAllMocks(); });

describe('Watch this date, the toggle in an entry', () => {
  const entry = () => view();
  const toggle = () => screen.getByTestId('coming-up-watch-toggle');

  it('⚠️ a real button whose words carry the state (Watch this date, then Watching), for a Pro reader whose list has landed', () => {
    const { rerender } = render(withFavourites(<WatchDateControl entry={entry()} />));
    expect(toggle().tagName).toBe('BUTTON');
    expect(toggle()).toHaveAccessibleName('Watch this date');
    expect(toggle()).not.toHaveAttribute('aria-pressed');
    expect(toggle()).toHaveAttribute('data-starred', 'false');
    expect(toggle().querySelector('[data-testid="bookmark-mark"]')).toHaveAttribute('data-form', 'outline');
    rerender(withFavourites(<WatchDateControl entry={entry()} />, { dates: ['king-tide:2026-12-03'] }));
    expect(toggle()).toHaveAccessibleName('Watching');
    expect(toggle()).not.toHaveAttribute('aria-pressed');
    expect(toggle()).toHaveAttribute('data-starred', 'true');
    expect(toggle().querySelector('[data-testid="bookmark-mark"]')).toHaveAttribute('data-form', 'filled');
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
    expect(toggle()).toHaveAttribute('data-starred', 'true');
    expect(toggle()).toHaveAccessibleName('Watching');
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
    expect(panel).toHaveTextContent(/moves to the top of Further ahead/);
    expect(panel).not.toHaveTextContent(/joins Your places/);
    expect(panel.querySelector('a')).toBeNull();
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
    expect(screen.getByTestId('undo-toast-status')).toHaveTextContent("Watching King tide · 3 Dec. It's at the top of Further ahead.");
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
