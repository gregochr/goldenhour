import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, within,
} from '@testing-library/react';

vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import { fetchAreaOutlook } from '../api/areasApi.js';
import FavouriteToggle, { FavouriteMark, FavouriteUpsell, useFavouriteUpsell } from '../components/shared/FavouriteToggle.jsx';
import AreaSheet from '../components/AreaSheet.jsx';
import PlanSearch from '../components/PlanSearch.jsx';
import WindowSpotCard from '../components/WindowSpotCard.jsx';
import RegionsJump from '../components/map/RegionsJump.jsx';
import YourPlacesRow from '../components/YourPlacesRow.jsx';
import { buildScoreIndex } from '../utils/locationSheet.js';
import { areaMapWindow } from '../utils/areaFind.js';
import { favouritesValue, withFavourites } from './favouritesKit.jsx';
import { FavouritesContext } from '../context/FavouritesContext.jsx';

function Host({ kind = 'place', id = 7, name = 'Steel Rigg' }) {
  const upsell = useFavouriteUpsell();
  return (
    <div>
      <FavouriteToggle kind={kind} id={id} name={name} upsell={upsell} />
      <FavouriteUpsell upsell={upsell} />
    </div>
  );
}

beforeEach(() => { mockIsMobile = false; vi.clearAllMocks(); });

describe('FavouriteToggle', () => {
  it('⚠️ the words carry the state: Bookmark, then Bookmarked, with no aria-pressed and the ribbon filling in', () => {
    const { rerender } = render(withFavourites(<Host />));
    const off = screen.getByRole('button', { name: 'Bookmark' });
    expect(off).not.toHaveAttribute('aria-pressed');
    expect(off).toHaveAttribute('data-starred', 'false');
    expect(off.querySelector('[data-testid="bookmark-mark"]')).toHaveAttribute('data-form', 'outline');
    expect(off).not.toHaveTextContent(/[★☆]/);
    rerender(withFavourites(<Host />, { places: [7] }));
    const on = screen.getByRole('button', { name: 'Bookmarked' });
    expect(on).not.toHaveAttribute('aria-pressed');
    expect(on).toHaveAttribute('data-starred', 'true');
    expect(on.querySelector('[data-testid="bookmark-mark"]')).toHaveAttribute('data-form', 'filled');
    expect(screen.queryByRole('button', { name: 'Bookmark' })).toBeNull();
  });

  it('an area reads Bookmark / Bookmarked too', () => {
    const { rerender } = render(withFavourites(<Host kind="area" id={4} name="Hadrian's Wall" />, { areas: [] }));
    expect(screen.getByRole('button', { name: 'Bookmark' })).toBeInTheDocument();
    rerender(withFavourites(<Host kind="area" id={4} name="Hadrian's Wall" />, { areas: [4] }));
    expect(screen.getByRole('button', { name: 'Bookmarked' })).toBeInTheDocument();
  });

  it('presses through the provider with the kind, id and name', () => {
    const value = favouritesValue({ areas: [] });
    render(<FavouritesContext.Provider value={value}><Host kind="area" id={4} name="Hadrian's Wall" /></FavouritesContext.Provider>);
    fireEvent.click(screen.getByRole('button', { name: 'Bookmark' }));
    expect(value.toggle).toHaveBeenCalledWith('area', 4, "Hadrian's Wall");
  });

  it('on a phone the glyph stands alone and the name is spoken only', () => {
    mockIsMobile = true;
    render(withFavourites(<Host />));
    const button = screen.getByRole('button', { name: 'Bookmark' });
    expect(within(button).getByText('Bookmark')).toHaveClass('sr-only');
    expect(button.querySelector('.wf-fav-t')).toBeNull();
    expect(button.closest('.wf-fav-wrap')).not.toBeNull();
  });

  it('draws nothing for no provider state, loading, error, or no id', () => {
    for (const status of ['off', 'loading', 'error']) {
      const { unmount } = render(withFavourites(<Host />, { status }));
      expect(screen.queryByRole('button')).toBeNull();
      unmount();
    }
    render(withFavourites(<Host id={null} />));
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('⚠️ a Lite reader gets the outline mark with a Pro pill and a worded upsell, never a link; Escape closes only the panel', () => {
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    render(withFavourites(<Host />, { status: 'locked' }));
    const button = screen.getByTestId('favourite-toggle-locked');
    expect(button).toHaveTextContent('Bookmark');
    expect(button.querySelector('[data-testid="bookmark-mark"]')).toHaveAttribute('data-form', 'outline');
    expect(button).toHaveTextContent('Pro');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByTestId('favourite-upsell-panel');
    expect(panel).toHaveTextContent('Pro keeps your own list');
    expect(panel.querySelector('a')).toBeNull();
    expect(panel).not.toHaveAttribute('role', 'dialog');
    expect(screen.getByTestId('favourite-upsell-heading')).toHaveFocus();
    fireEvent.keyDown(screen.getByTestId('favourite-upsell-heading'), { key: 'Escape' });
    expect(screen.queryByTestId('favourite-upsell-panel')).toBeNull();
    expect(button).toHaveFocus();
    // Escape on the trigger with the panel closed is not swallowed.
    fireEvent.keyDown(button, { key: 'Escape' });
    expect(onKey).toHaveBeenCalledTimes(1);
    // Escape on the open trigger closes the panel and stops the key.
    fireEvent.click(button);
    onKey.mockClear();
    fireEvent.keyDown(button, { key: 'Escape' });
    expect(screen.queryByTestId('favourite-upsell-panel')).toBeNull();
    expect(onKey).not.toHaveBeenCalled();
    document.removeEventListener('keydown', onKey);
  });

  it('Not now closes the upsell and returns focus to the trigger', () => {
    render(withFavourites(<Host />, { status: 'locked' }));
    fireEvent.click(screen.getByTestId('favourite-toggle-locked'));
    fireEvent.click(screen.getByTestId('favourite-upsell-dismiss'));
    expect(screen.queryByTestId('favourite-upsell-panel')).toBeNull();
    expect(screen.getByTestId('favourite-toggle-locked')).toHaveFocus();
  });
});

describe('FavouriteMark', () => {
  it('marks only a place the reader bookmarked itself, hidden from assistive tech', () => {
    const { rerender } = render(withFavourites(<FavouriteMark id={3} />, { places: [3] }));
    expect(screen.getByTestId('favourite-mark')).toHaveAttribute('aria-hidden', 'true');
    // A place inside a bookmarked area is in the merged ids but is not marked individually.
    rerender(withFavourites(<FavouriteMark id={3} />, { places: [], locationIds: [3], areas: [1] }));
    expect(screen.queryByTestId('favourite-mark')).toBeNull();
    rerender(withFavourites(<FavouriteMark id={3} />, { status: 'locked', places: [3] }));
    expect(screen.queryByTestId('favourite-mark')).toBeNull();
  });
});

describe('the toggle in the area sheet', () => {
  const AREA = { id: 5, name: 'Northumberland Coast', kind: 'SUB_REGION', locationIds: [1] };
  const sheet = (options) => withFavourites(
    <AreaSheet area={AREA} spots={[{ id: 1, name: 'Bamburgh' }]} windows={[]} onClose={vi.fn()} onOpenSpot={vi.fn()} />,
    options,
  );

  it('sits in the header, pressed for a bookmarked area, and a Lite reader gets the Pro door under the head', () => {
    const { unmount } = render(sheet({ areas: [5] }));
    expect(within(screen.getByTestId('area-sheet')).getByTestId('area-sheet-favourite')).toHaveAttribute('data-starred', 'true');
    unmount();
    render(sheet({ status: 'locked' }));
    fireEvent.click(screen.getByTestId('area-sheet-favourite-locked'));
    expect(screen.getByTestId('favourite-upsell-panel')).toBeInTheDocument();
  });
});

describe('the mark on a popup spot card', () => {
  const SPOT = { locationId: 3, locationName: 'Bamburgh', regionName: 'North', rating: 4 };
  it('adds the bookmark mark and a spoken "bookmarked" for a bookmarked place only; the rating is untouched', () => {
    const { rerender } = render(withFavourites(<WindowSpotCard spot={SPOT} typeLabels={[]} onOpen={vi.fn()} />, { places: [3] }));
    expect(screen.getByTestId('favourite-mark')).toBeInTheDocument();
    expect(screen.getByTestId('window-spot')).toHaveTextContent('bookmarked');
    expect(screen.getByTestId('window-spot-rating')).toHaveTextContent('4★');
    rerender(withFavourites(<WindowSpotCard spot={SPOT} typeLabels={[]} onOpen={vi.fn()} />, { places: [] }));
    expect(screen.queryByTestId('favourite-mark')).toBeNull();
    expect(screen.getByTestId('window-spot')).not.toHaveTextContent('bookmarked');
  });
});

describe('Find, resting', () => {
  const SPOTS = [{ id: 1, name: 'Bamburgh', regionName: 'North' }, { id: 2, name: 'Craster', regionName: 'North' }];
  const AREAS = [{ id: 9, name: "Hadrian's Wall", kind: 'NAMED', aliases: [], locationIds: [1] }];
  const find = (options) => withFavourites(
    <PlanSearch windows={[]} locations={SPOTS} areas={AREAS} onClose={vi.fn()} />,
    options,
  );

  it('⚠️ shows a Your places group with the bookmark mark, areas first, for a Pro reader, with no interactive child inside a row', () => {
    render(find({ places: [2], areas: [9] }));
    const group = screen.getByRole('group', { name: 'Your places' });
    const rows = within(group).getAllByTestId('plan-search-row');
    expect(rows.map((r) => r.dataset.kind)).toEqual(['area', 'location']);
    expect(rows[0]).toHaveTextContent("Hadrian's Wall");
    expect(rows[0]).toHaveTextContent('bookmarked');
    expect(within(rows[0]).getByTestId('bookmark-mark')).toBeInTheDocument();
    expect(within(group).getByTestId('plan-search-group')).not.toHaveTextContent('★');
    rows.forEach((row) => expect(row.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0));
  });

  it('shows no group to a Lite reader, and none when nothing is bookmarked', () => {
    render(find({ status: 'locked', places: [2] }));
    expect(screen.queryByRole('group', { name: 'Your places' })).toBeNull();
  });

  it('typing replaces it: a bookmarked place that does not match is not offered, one that does goes first', () => {
    render(find({ places: [2] }));
    fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value: 'bam' } });
    expect(screen.queryByRole('group', { name: 'Your places' })).toBeNull();
    expect(screen.getAllByTestId('plan-search-row')).toHaveLength(1);
    fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value: 'ra' } });
    expect(screen.getAllByTestId('plan-search-row')[0]).toHaveTextContent('Craster');
  });
});

describe('the Map Regions list', () => {
  const ROWS = [{ name: 'North', driveMinutes: 30, beyondArea: false, bestRating: 4 }];
  const jump = (options, onSelectFavourite = vi.fn(), activeFavourite = null) => withFavourites(
    <RegionsJump open onOpenChange={vi.fn()} rows={ROWS} onSelectRegion={vi.fn()} onSelectFavourite={onSelectFavourite} activeFavourite={activeFavourite} />,
    options,
  );

  it('lists My places then each bookmarked area above the regions, and hands the entry back on a press', () => {
    const onSelect = vi.fn();
    render(jump({
      places: [1], locationIds: [1, 2], areas: [9],
      bookmarkedAreas: [{ id: 9, name: "Hadrian's Wall", locationIds: [2] }],
    }, onSelect, 'places'));
    const rows = screen.getAllByTestId('wf-jump-favourite');
    expect(rows.map((r) => r.querySelector('.wf-jump-name').textContent)).toEqual(['My places', "Hadrian's Wall"]);
    rows.forEach((r) => expect(r.querySelector('.wf-jump-name [data-testid="bookmark-mark"]')).not.toBeNull());
    expect(rows[0]).toHaveAttribute('aria-current', 'true');
    expect(rows[0]).toHaveTextContent('2 places');
    fireEvent.click(rows[1]);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ key: 'area:9', ids: [2], name: "Hadrian's Wall" }));
    const all = [...screen.getByTestId('wf-jump-menu').querySelectorAll('button')].map((b) => b.dataset.testid);
    expect(all.indexOf('wf-jump-favourite')).toBeLessThan(all.indexOf('wf-jump-row'));
  });

  it('draws nothing for a Pro reader with nothing bookmarked, or when the map offers no handler', () => {
    render(jump({}));
    expect(screen.queryByTestId('wf-jump-favourites')).toBeNull();
  });

  it('a Lite reader gets My places (outline mark) with the Pro pill and the worded upsell', () => {
    render(jump({ status: 'locked' }));
    fireEvent.click(screen.getByTestId('wf-jump-favourite-locked'));
    expect(screen.getByTestId('wf-jump-favourite-upsell-panel')).toBeInTheDocument();
  });

  it('⚠️ the entries are a labelled section with the mark hidden from assistive tech, and Escape on the Lite trigger closes only the panel', () => {
    const { unmount } = render(jump({ places: [1], locationIds: [1] }));
    expect(screen.getByRole('group', { name: 'Your places' })).toBeInTheDocument();
    expect(screen.getAllByTestId('wf-jump-favourite')[0]).toHaveAccessibleName(/^My places/);
    unmount();
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    render(jump({ status: 'locked' }));
    const trigger = screen.getByTestId('wf-jump-favourite-locked');
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(screen.queryByTestId('wf-jump-favourite-upsell-panel')).toBeNull();
    expect(onKey).not.toHaveBeenCalled();
    document.removeEventListener('keydown', onKey);
  });

  it('draws no section at all without the handler', () => {
    render(withFavourites(
      <RegionsJump open onOpenChange={vi.fn()} rows={ROWS} onSelectRegion={vi.fn()} />, { places: [1] },
    ));
    expect(screen.queryByTestId('wf-jump-favourites')).toBeNull();
  });
});

describe('Your places, the row at the top of This week', () => {
  const WINDOWS = [
    { key: '2026-10-05:SUNRISE', date: '2026-10-05', targetType: 'SUNRISE', label: 'Mon sunrise' },
    { key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Mon sunset' },
  ];
  const SPOTS = [
    { id: 1, name: 'Bamburgh' }, { id: 2, name: 'Craster' }, { id: 3, name: 'Steel Rigg' }, { id: 4, name: 'Unrated' },
  ];
  const INDEX = buildScoreIndex([
    { locationId: 1, locationName: 'Bamburgh', date: '2026-10-05', targetType: 'SUNSET', rating: 4 },
    { locationId: 2, locationName: 'Craster', date: '2026-10-05', targetType: 'SUNRISE', rating: 2 },
    { locationId: 3, locationName: 'Steel Rigg', date: '2026-10-05', targetType: 'SUNRISE', rating: 5 },
  ]);
  const AREA = { id: 9, name: "Hadrian's Wall", kind: 'NAMED', locationIds: [3] };
  const list = (options, extra = {}) => withFavourites(
    <YourPlacesRow
      spots={SPOTS}
      windows={WINDOWS}
      scoreIndex={INDEX}
      reachById={new Map([[1, { driveMinutes: 22 }]])}
      allowedLocationIds={null}
      onOpenWindow={vi.fn()}
      onOpenPlace={vi.fn()}
      onOpenArea={vi.fn()}
      {...extra}
    />,
    options,
  );

  beforeEach(() => {
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 9,
      windows: [{
        date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 1, totalMembers: 1, gatedMembers: 0, topRating: 5, topLocationId: 3,
      }],
    }]);
  });

  /** The chip's accessible name: the words a reader hears (the glyphs are hidden, stars are spoken). */
  const names = (chips) => chips.map((chip) => chip.getAttribute('data-testid') && chip.textContent
    && within(chip).getByTestId('your-places-detail').textContent);

  it('prints served figures on ONE line, best rating first: an area, a place, the quiet line below 3★, and not rated', async () => {
    render(list({ places: [1, 2, 4], areas: [9], bookmarkedAreas: [AREA] }));
    await screen.findByText(/Mon sunrise/);
    const rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.dataset.kind)).toEqual(['area', 'place', 'place', 'place']);
    expect(rows[0]).toHaveAccessibleName("Hadrian's Wall, Mon sunrise 5 stars");
    expect(rows[1]).toHaveAccessibleName('Bamburgh, Mon sunset 4 stars');
    expect(rows[2]).toHaveAccessibleName('Craster, nothing above 2 stars');
    expect(rows[3]).toHaveAccessibleName('Unrated, not rated yet');
    // One line: the mark, the name and the figure are siblings in one button, and the glyphs are hidden.
    expect(rows[1].querySelector('[data-testid="bookmark-mark"]')).toBeInTheDocument();
    expect(rows[1].textContent).toMatch(/^Bamburgh.* Mon sunset 4★ stars$/);
    expect(rows[1].querySelector('[aria-hidden="true"]:not(svg)')).not.toHaveTextContent('4');
  });

  it('the quiet rows (under 3★, not rated, outside the picks) are last and muted; the rest are not', async () => {
    render(list({ places: [1, 2, 4], areas: [9], bookmarkedAreas: [AREA] }));
    await screen.findByText(/Mon sunrise/);
    const rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.classList.contains('wf-yp-quiet'))).toEqual([false, false, true, true]);
    expect(names(rows).slice(2)).toEqual(['nothing above 2★ stars', 'not rated yet']);
  });

  it('⚠️ honesty: the order is a sort of the reader\'s own rows by the figure each chip prints, never a re-rating', async () => {
    const index = buildScoreIndex([
      { locationId: 1, locationName: 'Bamburgh', date: '2026-10-05', targetType: 'SUNSET', rating: 3 },
      { locationId: 2, locationName: 'Craster', date: '2026-10-05', targetType: 'SUNRISE', rating: 5 },
    ]);
    render(list({ places: [1, 2] }, { scoreIndex: index }));
    const rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent)).toEqual(['Craster', 'Bamburgh']);
    expect(rows[0]).toHaveAccessibleName('Craster, Mon sunrise 5 stars');
    expect(rows[1]).toHaveAccessibleName('Bamburgh, Mon sunset 3 stars');
  });

  it('ties go by drive minutes, then by name; an area (no drive) ties like a place with an unknown drive', async () => {
    const spots = [
      { id: 1, name: 'Bamburgh' }, { id: 2, name: 'Craster' }, { id: 3, name: 'Alnmouth' }, { id: 5, name: 'Seahouses' },
    ];
    const index = buildScoreIndex([1, 2, 3, 5].map((id) => ({
      locationId: id, locationName: spots.find((spot) => spot.id === id).name, date: '2026-10-05', targetType: 'SUNRISE', rating: 4,
    })));
    const reach = new Map([[1, { driveMinutes: 40 }], [2, { driveMinutes: 20 }], [3, { driveMinutes: 20 }]]);
    const four = { id: 8, name: 'Zebra coast', kind: 'NAMED', locationIds: [1] };
    const alsoFour = { id: 7, name: 'Alpha coast', kind: 'NAMED', locationIds: [2] };
    fetchAreaOutlook.mockResolvedValue([8, 7].map((areaId) => ({
      areaId,
      windows: [{ date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 1, totalMembers: 1, gatedMembers: 0, topRating: 4, topLocationId: 1 }],
    })));
    render(list(
      { places: [1, 2, 3, 5], areas: [8, 7], bookmarkedAreas: [four, alsoFour] },
      { spots, scoreIndex: index, reachById: reach },
    ));
    await screen.findByText('Alpha coast');
    await screen.findAllByText(/Mon sunrise/);
    const rows = await screen.findAllByTestId('your-places-chip');
    // 20 min (Alnmouth, Craster by name), 40 min (Bamburgh), then those with no drive (Seahouses, the areas) by name.
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent))
      .toEqual(['Alnmouth', 'Craster', 'Bamburgh', 'Alpha coast', 'Seahouses', 'Zebra coast']);
  });

  it('⚠️ it sorts only once its figures are in: until the area outlook lands, areas then places in the order held', async () => {
    let resolveOutlook;
    fetchAreaOutlook.mockReturnValue(new Promise((resolve) => { resolveOutlook = resolve; }));
    // Craster (2★, quiet) is held before Bamburgh (4★) and the area (5★ once it lands).
    render(list({ places: [2, 1], areas: [9], bookmarkedAreas: [AREA] }));
    let rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent)).toEqual(["Hadrian's Wall", 'Craster', 'Bamburgh']);
    await act(async () => {
      resolveOutlook([{
        areaId: 9,
        windows: [{ date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 1, totalMembers: 1, gatedMembers: 0, topRating: 5, topLocationId: 3 }],
      }]);
    });
    rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent)).toEqual(["Hadrian's Wall", 'Bamburgh', 'Craster']);
  });

  it('with no score index yet the places keep the order held, and sort when it arrives', async () => {
    const { rerender } = render(list({ places: [2, 1] }, { scoreIndex: null }));
    let rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent)).toEqual(['Craster', 'Bamburgh']);
    rerender(list({ places: [2, 1] }));
    rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.querySelector('.wf-yp-name').textContent)).toEqual(['Bamburgh', 'Craster']);
  });

  it('⚠️ a reorder puts an untouched row back at its start (a browser re-snaps to the old chip otherwise)', async () => {
    const { rerender } = render(list({ places: [2, 1] }, { scoreIndex: null }));
    const row = await screen.findByTestId('your-places-row');
    row.scrollLeft = 200; // what scroll-snap does to keep the chip it had snapped to
    rerender(list({ places: [2, 1] }));
    await screen.findAllByTestId('your-places-chip');
    expect(screen.getByTestId('your-places-row').scrollLeft).toBe(0);
  });

  it('a row the reader has already reached for is never moved by the sort', async () => {
    for (const gesture of ['pointerDown', 'touchStart', 'wheel', 'keyDown', 'focusIn']) {
      const { rerender, unmount } = render(list({ places: [2, 1] }, { scoreIndex: null }));
      const row = await screen.findByTestId('your-places-row');
      fireEvent[gesture](row);
      row.scrollLeft = 200;
      rerender(list({ places: [2, 1] }));
      await screen.findAllByTestId('your-places-chip');
      expect(screen.getByTestId('your-places-row').scrollLeft, gesture).toBe(200);
      unmount();
    }
  });

  it('a watched date is never a chip here: the row holds places and areas only', async () => {
    render(list({ places: [1], dates: ['king-tide:2026-12-03'] }));
    const rows = await screen.findAllByTestId('your-places-chip');
    expect(rows).toHaveLength(1);
    expect(rows[0].dataset.kind).toBe('place');
    expect(screen.queryByRole('button', { name: /stop watching/i })).toBeNull();
    expect(screen.queryByTestId('your-places-hint')).toBeNull();
  });

  it('a place outside a Lite-style pick list says so and shows no figure', async () => {
    render(list({ places: [1] }, { allowedLocationIds: [2] }));
    expect((await screen.findByTestId('your-places-detail')).textContent).toBe('outside your picks');
    expect(screen.getByTestId('your-places-chip')).toHaveClass('wf-yp-quiet');
  });

  it('a chip with a worthwhile window opens the map view on it: a place on its best window, an area on its spots', async () => {
    const onOpenWindow = vi.fn();
    const onOpenPlace = vi.fn();
    const onOpenArea = vi.fn();
    render(list({ places: [1], areas: [9], bookmarkedAreas: [AREA] }, { onOpenWindow, onOpenPlace, onOpenArea }));
    await screen.findByText(/Mon sunrise/);
    const chips = await screen.findAllByTestId('your-places-chip');
    fireEvent.click(chips[0]);
    fireEvent.click(chips[1]);
    expect(onOpenArea).not.toHaveBeenCalled();
    expect(onOpenPlace).not.toHaveBeenCalled();
    expect(onOpenWindow).toHaveBeenNthCalledWith(1, {
      window: { date: '2026-10-05', eventType: 'SUNRISE' }, focus: { kind: 'area', area: AREA }, from: chips[0],
    });
    expect(onOpenWindow).toHaveBeenNthCalledWith(2, {
      window: { date: '2026-10-05', eventType: 'SUNSET' },
      focus: { kind: 'location', id: 1, name: 'Bamburgh' },
      from: chips[1],
    });
  });

  it('an area chip opens the same window the area sheet\'s "Show on map" foot would (areaMapWindow)', async () => {
    const onOpenWindow = vi.fn();
    render(list({ areas: [9], bookmarkedAreas: [AREA] }, { onOpenWindow }));
    await screen.findByText(/Mon sunrise/);
    fireEvent.click(await screen.findByTestId('your-places-chip'));
    const foot = areaMapWindow(WINDOWS, [{
      date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 1, totalMembers: 1, gatedMembers: 0, topRating: 5, topLocationId: 3,
    }]);
    expect(onOpenWindow.mock.calls[0][0].window).toEqual({ date: foot.date, eventType: foot.targetType });
  });

  it('a chip with no window worth a trip opens the place\'s or area\'s own sheet instead, never nothing', async () => {
    const onOpenWindow = vi.fn();
    const onOpenPlace = vi.fn();
    const onOpenArea = vi.fn();
    // Craster is rated 2 (quiet), the unrated place has no figure, and the area has no outlook window.
    fetchAreaOutlook.mockResolvedValue([{ areaId: 9, windows: [] }]);
    render(list({ places: [2, 4], areas: [9], bookmarkedAreas: [AREA] }, { onOpenWindow, onOpenPlace, onOpenArea }));
    const chips = await screen.findAllByTestId('your-places-chip');
    fireEvent.click(chips[0]);
    fireEvent.click(chips[1]);
    fireEvent.click(chips[2]);
    expect(onOpenArea).toHaveBeenCalledWith(AREA);
    expect(onOpenPlace).toHaveBeenNthCalledWith(1, SPOTS[1]);
    expect(onOpenPlace).toHaveBeenNthCalledWith(2, SPOTS[3]);
    expect(onOpenWindow).not.toHaveBeenCalled();
  });

  it('a place outside the reader\'s picks opens its sheet and carries no window', async () => {
    const onOpenWindow = vi.fn();
    const onOpenPlace = vi.fn();
    render(list({ places: [1] }, { allowedLocationIds: [2], onOpenWindow, onOpenPlace }));
    fireEvent.click(await screen.findByTestId('your-places-chip'));
    expect(onOpenPlace).toHaveBeenCalledWith(SPOTS[0]);
    expect(onOpenWindow).not.toHaveBeenCalled();
  });

  it('is one horizontal row, a list of chips, labelled "Your places" by its own heading', async () => {
    render(list({ places: [1, 2] }));
    const section = await screen.findByRole('region', { name: /Your places/ });
    expect(within(section).getByTestId('your-places-row').tagName).toBe('UL');
    expect(within(section).getAllByRole('listitem')).toHaveLength(2);
  });

  describe('the outlook fetch waits for the row to be on screen', () => {
    let observers;
    beforeEach(() => {
      observers = [];
      globalThis.IntersectionObserver = class {
        constructor(callback) { this.callback = callback; observers.push(this); }

        observe() {}

        disconnect() {}
      };
    });
    afterEach(() => { delete globalThis.IntersectionObserver; });

    it('fetches nothing until the row first intersects, then fetches once', async () => {
      render(list({ places: [1], areas: [9], bookmarkedAreas: [AREA] }));
      await screen.findAllByTestId('your-places-chip');
      expect(fetchAreaOutlook).not.toHaveBeenCalled();

      act(() => { observers.at(-1).callback([{ isIntersecting: false }]); });
      expect(fetchAreaOutlook).not.toHaveBeenCalled();

      act(() => { observers.at(-1).callback([{ isIntersecting: true }]); });
      await screen.findByText(/Mon sunrise/);
      expect(fetchAreaOutlook).toHaveBeenCalledTimes(1);
    });
  });

  it('⚠️ an area-only reader whose directory has not named the area never sees the hint', async () => {
    // Served list: one area bookmarked. Directory unresolved: bookmarkedAreas is empty.
    render(list({ areas: [9], locationIds: [3], bookmarkedAreas: [] }, { hintDue: true }));
    expect(screen.queryByTestId('your-places-hint')).toBeNull();
    expect(await screen.findByTestId('your-places-areas-pending')).toHaveTextContent('1 bookmarked area is not shown yet.');
    expect(screen.queryByTestId('your-places-offplan')).toBeNull();
  });

  it('a bookmarked place that is not on the plan says so rather than printing the hint', async () => {
    render(list({ places: [999] }, { hintDue: true }));
    expect(screen.queryByTestId('your-places-hint')).toBeNull();
    expect(await screen.findByTestId('your-places-offplan')).toBeInTheDocument();
  });

  it('Escape on the Lite hint with the upsell open closes only the panel', () => {
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    render(list({ status: 'locked' }, { hintDue: true }));
    const trigger = screen.getByTestId('your-places-hint-button');
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(screen.queryByTestId('your-places-upsell-panel')).toBeNull();
    expect(onKey).not.toHaveBeenCalled();
    document.removeEventListener('keydown', onKey);
  });

  describe('the one-time hint (screen-spec.md D8)', () => {
    const HINT = 'Bookmark places and watch dates to build your plan.';

    it('replaces the row for a Pro reader with nothing saved, while it is due', async () => {
      render(list({}, { hintDue: true }));
      const hint = await screen.findByTestId('your-places-hint');
      expect(hint).toHaveTextContent(HINT);
      expect(screen.getByTestId('your-places-hint-dismiss')).toHaveAccessibleName('Dismiss');
      expect(screen.queryByTestId('your-places')).toBeNull();
      expect(screen.queryByRole('button', { name: /pro/i })).toBeNull();
      expect(fetchAreaOutlook).not.toHaveBeenCalled();
    });

    it('is absent when it is not due, and the row is absent too (no empty heading)', () => {
      render(list({}, { hintDue: false }));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
      expect(screen.queryByTestId('your-places')).toBeNull();
    });

    it('draws nothing while the list is loading or has failed, even when due', () => {
      render(list({ status: 'loading' }, { hintDue: true }));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
    });

    it('is not shown to a reader who has a bookmarked place, an area or a watched date', async () => {
      render(list({ places: [1] }, { hintDue: true }));
      await screen.findAllByTestId('your-places-chip');
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
      cleanup();
      render(list({ dates: ['king-tide:2026-12-03'] }, { hintDue: true }));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
    });

    it('the Dismiss writes the flag once, hides the hint at once and puts focus on the This week heading', () => {
      const onDismissHint = vi.fn();
      render(
        <>
          <h2 id="wf-this-week-h" tabIndex={-1}>This week</h2>
          {list({}, { hintDue: true, onDismissHint })}
        </>,
      );
      fireEvent.click(screen.getByTestId('your-places-hint-dismiss'));
      expect(onDismissHint).toHaveBeenCalledTimes(1);
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
      expect(document.getElementById('wf-this-week-h')).toHaveFocus();
    });

    it('the Dismiss still works with no writer handed in, and a missing heading drops focus nowhere', () => {
      render(list({}, { hintDue: true }));
      fireEvent.click(screen.getByTestId('your-places-hint-dismiss'));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
    });

    it('the first save hides it at once, and removing everything afterwards never brings it back', async () => {
      const view = render(list({}, { hintDue: true }));
      expect(screen.getByTestId('your-places-hint')).toBeInTheDocument();

      view.rerender(list({ places: [1] }, { hintDue: true }));
      await screen.findAllByTestId('your-places-chip');
      expect(screen.queryByTestId('your-places-hint')).toBeNull();

      view.rerender(list({}, { hintDue: true }));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
    });

    it('tells the page once when the first save lands while the hint was due, and not otherwise', async () => {
      const onSettleHint = vi.fn();
      const view = render(list({}, { hintDue: true, onSettleHint }));
      expect(onSettleHint).not.toHaveBeenCalled();

      view.rerender(list({ places: [1] }, { hintDue: true, onSettleHint }));
      await screen.findAllByTestId('your-places-chip');
      expect(onSettleHint).toHaveBeenCalledTimes(1);

      cleanup();
      const quiet = vi.fn();
      render(list({ places: [1] }, { hintDue: false, onSettleHint: quiet }));
      await screen.findAllByTestId('your-places-chip');
      expect(quiet).not.toHaveBeenCalled();
    });

    it('a Lite reader sees the same line with a Pro pill that opens one upsell for places and dates', () => {
      render(list({ status: 'locked' }, { hintDue: true }));
      const trigger = screen.getByTestId('your-places-hint-button');
      expect(trigger).toHaveTextContent(HINT);
      expect(trigger).toHaveTextContent('Pro');
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
      fireEvent.click(trigger);
      const panel = screen.getByTestId('your-places-upsell-panel');
      expect(trigger).toHaveAttribute('aria-expanded', 'true');
      expect(panel).toHaveTextContent(/places and areas, and can watch dates/);
      expect(fetchAreaOutlook).not.toHaveBeenCalled();
    });

    it('a Lite reader whose hint is not due sees nothing: no teaser any more', () => {
      render(list({ status: 'locked' }, { hintDue: false }));
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
      expect(screen.queryByTestId('your-places')).toBeNull();
    });

    it('a Lite reader can dismiss it too', () => {
      const onDismissHint = vi.fn();
      render(list({ status: 'locked' }, { hintDue: true, onDismissHint }));
      fireEvent.click(screen.getByTestId('your-places-hint-dismiss'));
      expect(onDismissHint).toHaveBeenCalledTimes(1);
      expect(screen.queryByTestId('your-places-hint')).toBeNull();
    });
  });

  it('an area with only gated members says so, and one with no window says not rated yet', async () => {
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 9,
      windows: [{
        date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 0, totalMembers: 2, gatedMembers: 2,
      }],
    }]);
    render(list({ areas: [9], bookmarkedAreas: [AREA] }));
    expect(await screen.findByText('2 outside your picks')).toBeInTheDocument();
  });
});
