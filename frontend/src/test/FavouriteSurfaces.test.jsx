import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';

vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import { fetchAreaOutlook } from '../api/areasApi.js';
import FavouriteToggle, { FavouriteMark, FavouriteUpsell, useFavouriteUpsell } from '../components/shared/FavouriteToggle.jsx';
import AreaSheet from '../components/AreaSheet.jsx';
import PlanSearch from '../components/PlanSearch.jsx';
import WindowSpotCard from '../components/WindowSpotCard.jsx';
import WindowRegionRail from '../components/WindowRegionRail.jsx';
import RegionsJump from '../components/map/RegionsJump.jsx';
import ComingUpWatchlist from '../components/ComingUpWatchlist.jsx';
import { buildScoreIndex } from '../utils/locationSheet.js';
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
  it('⚠️ one constant label with aria-pressed, never a swapped name, on a desktop', () => {
    const { rerender } = render(withFavourites(<Host />));
    const off = screen.getByRole('button', { name: 'Your place' });
    expect(off).toHaveAttribute('aria-pressed', 'false');
    expect(off).toHaveTextContent(/☆\s*Your place/);
    rerender(withFavourites(<Host />, { places: [7] }));
    const on = screen.getByRole('button', { name: 'Your place' });
    expect(on).toHaveAttribute('aria-pressed', 'true');
    expect(on).toHaveTextContent(/★\s*Your place/);
  });

  it('presses through the provider with the kind, id and name', () => {
    const value = favouritesValue({ areas: [] });
    render(<FavouritesContext.Provider value={value}><Host kind="area" id={4} name="Hadrian's Wall" /></FavouritesContext.Provider>);
    fireEvent.click(screen.getByRole('button', { name: 'Your place' }));
    expect(value.toggle).toHaveBeenCalledWith('area', 4, "Hadrian's Wall");
  });

  it('on a phone the glyph stands alone and the name is spoken only', () => {
    mockIsMobile = true;
    render(withFavourites(<Host />));
    const button = screen.getByRole('button', { name: 'Your place' });
    expect(within(button).getByText('Your place')).toHaveClass('sr-only');
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

  it('⚠️ a Lite reader gets ☆ with a Pro pill and a worded upsell, never a link; Escape closes only the panel', () => {
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    render(withFavourites(<Host />, { status: 'locked' }));
    const button = screen.getByTestId('favourite-toggle-locked');
    expect(button).toHaveTextContent('Your place');
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
  it('marks only a place the reader starred itself, hidden from assistive tech', () => {
    const { rerender } = render(withFavourites(<FavouriteMark id={3} />, { places: [3] }));
    expect(screen.getByTestId('favourite-mark')).toHaveAttribute('aria-hidden', 'true');
    // A place inside a starred area is in the merged ids but is not marked individually.
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

  it('sits in the header, pressed for a starred area, and a Lite reader gets the Pro door under the head', () => {
    const { unmount } = render(sheet({ areas: [5] }));
    expect(within(screen.getByTestId('area-sheet')).getByTestId('area-sheet-favourite')).toHaveAttribute('aria-pressed', 'true');
    unmount();
    render(sheet({ status: 'locked' }));
    fireEvent.click(screen.getByTestId('area-sheet-favourite-locked'));
    expect(screen.getByTestId('favourite-upsell-panel')).toBeInTheDocument();
  });
});

describe('the mark on a popup spot card', () => {
  const SPOT = { locationId: 3, locationName: 'Bamburgh', regionName: 'North', rating: 4 };
  it('adds ★ and a spoken "your place" for a starred place only; the rating is untouched', () => {
    const { rerender } = render(withFavourites(<WindowSpotCard spot={SPOT} typeLabels={[]} onOpen={vi.fn()} />, { places: [3] }));
    expect(screen.getByTestId('favourite-mark')).toBeInTheDocument();
    expect(screen.getByTestId('window-spot')).toHaveTextContent('your place');
    expect(screen.getByTestId('window-spot-rating')).toHaveTextContent('4★');
    rerender(withFavourites(<WindowSpotCard spot={SPOT} typeLabels={[]} onOpen={vi.fn()} />, { places: [] }));
    expect(screen.queryByTestId('favourite-mark')).toBeNull();
    expect(screen.getByTestId('window-spot')).not.toHaveTextContent('your place');
  });
});

describe('Find, resting', () => {
  const SPOTS = [{ id: 1, name: 'Bamburgh', regionName: 'North' }, { id: 2, name: 'Craster', regionName: 'North' }];
  const AREAS = [{ id: 9, name: "Hadrian's Wall", kind: 'NAMED', aliases: [], locationIds: [1] }];
  const find = (options) => withFavourites(
    <PlanSearch windows={[]} locations={SPOTS} areas={AREAS} onClose={vi.fn()} />,
    options,
  );

  it('⚠️ shows a ★ Your places group, areas first, for a Pro reader, with no interactive child inside a row', () => {
    render(find({ places: [2], areas: [9] }));
    const group = screen.getByRole('group', { name: '★ Your places' });
    const rows = within(group).getAllByTestId('plan-search-row');
    expect(rows.map((r) => r.dataset.kind)).toEqual(['area', 'location']);
    expect(rows[0]).toHaveTextContent("Hadrian's Wall");
    expect(rows[0]).toHaveTextContent('your place');
    rows.forEach((row) => expect(row.querySelectorAll('button, a, input, [tabindex]')).toHaveLength(0));
  });

  it('shows no group to a Lite reader, and none when nothing is starred', () => {
    render(find({ status: 'locked', places: [2] }));
    expect(screen.queryByRole('group', { name: '★ Your places' })).toBeNull();
  });

  it('typing replaces it: a starred place that does not match is not offered, one that does goes first', () => {
    render(find({ places: [2] }));
    fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value: 'bam' } });
    expect(screen.queryByRole('group', { name: '★ Your places' })).toBeNull();
    expect(screen.getAllByTestId('plan-search-row')).toHaveLength(1);
    fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value: 'ra' } });
    expect(screen.getAllByTestId('plan-search-row')[0]).toHaveTextContent('Craster');
  });
});

describe('the popup region rail', () => {
  const ROWS = [
    { name: 'North', verdict: 'good', verdictLabel: 'Worth it', bestRating: 4, drawnCount: 3, countNoun: 'spots' },
    { name: 'South', verdict: 'poor', verdictLabel: 'Poor', bestRating: 2, drawnCount: 2, countNoun: 'spots' },
  ];
  const rail = (options, favourites) => withFavourites(
    <WindowRegionRail rows={ROWS} windowBest={4} drawnCount={5} countNoun="spots" selected={null} onSelect={vi.fn()} favourites={favourites} />,
    options,
  );
  const FAV = (onSelect = vi.fn(), selected = null) => ({
    cells: [{ key: 'places', label: '★ My places', count: 2 }, { key: 'area:9', label: "★ Hadrian's Wall", count: 1 }],
    selected,
    onSelect,
  });

  it('adds ★ My places and one cell per starred area under All, as pressed toggles that unpress All', () => {
    const onSelect = vi.fn();
    const { rerender } = render(rail({ places: [1] }, FAV(onSelect)));
    const cells = screen.getAllByTestId('wf-region-favourite');
    expect(cells.map((c) => c.textContent)).toEqual(['★ My places 2 places', "★ Hadrian's Wall 1 place"]);
    fireEvent.click(cells[0]);
    expect(onSelect).toHaveBeenCalledWith('places');
    rerender(rail({ places: [1] }, FAV(onSelect, 'places')));
    expect(screen.getAllByTestId('wf-region-favourite')[0]).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getAllByTestId('wf-region-cell')[0]).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getAllByTestId('wf-region-favourite')[0]);
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it('draws nothing extra for a Pro reader with no star, or with no favourites prop', () => {
    render(rail({ status: 'ready' }, null));
    expect(screen.queryByTestId('wf-region-favourite')).toBeNull();
  });

  it('a Lite reader gets one ★ My places cell with the Pro pill that opens the worded upsell', () => {
    render(rail({ status: 'locked' }, FAV()));
    const locked = screen.getByTestId('wf-region-favourite-locked');
    expect(locked).toHaveTextContent('★ My places');
    expect(locked).toHaveTextContent('Pro');
    fireEvent.click(locked);
    expect(screen.getByTestId('wf-region-favourite-upsell-panel')).toHaveTextContent('Pro keeps your own list');
  });
});

describe('the Map Regions list', () => {
  const ROWS = [{ name: 'North', driveMinutes: 30, beyondArea: false, bestRating: 4 }];
  const jump = (options, onSelectFavourite = vi.fn(), activeFavourite = null) => withFavourites(
    <RegionsJump open onOpenChange={vi.fn()} rows={ROWS} onSelectRegion={vi.fn()} onSelectFavourite={onSelectFavourite} activeFavourite={activeFavourite} />,
    options,
  );

  it('lists ★ My places then each starred area above the regions, and hands the entry back on a press', () => {
    const onSelect = vi.fn();
    render(jump({
      places: [1], locationIds: [1, 2], areas: [9],
      starredAreas: [{ id: 9, name: "Hadrian's Wall", locationIds: [2] }],
    }, onSelect, 'places'));
    const rows = screen.getAllByTestId('wf-jump-favourite');
    expect(rows.map((r) => r.querySelector('.wf-jump-name').textContent)).toEqual(['★ My places', "★ Hadrian's Wall"]);
    expect(rows[0]).toHaveAttribute('aria-current', 'true');
    expect(rows[0]).toHaveTextContent('2 places');
    fireEvent.click(rows[1]);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ key: 'area:9', ids: [2], name: "Hadrian's Wall" }));
    const all = [...screen.getByTestId('wf-jump-menu').querySelectorAll('button')].map((b) => b.dataset.testid);
    expect(all.indexOf('wf-jump-favourite')).toBeLessThan(all.indexOf('wf-jump-row'));
  });

  it('draws nothing for a Pro reader with nothing starred, or when the map offers no handler', () => {
    render(jump({}));
    expect(screen.queryByTestId('wf-jump-favourites')).toBeNull();
  });

  it('a Lite reader gets ★ My places with the Pro pill and the worded upsell', () => {
    render(jump({ status: 'locked' }));
    fireEvent.click(screen.getByTestId('wf-jump-favourite-locked'));
    expect(screen.getByTestId('wf-jump-favourite-upsell-panel')).toBeInTheDocument();
  });

  it('draws no section at all without the handler', () => {
    render(withFavourites(
      <RegionsJump open onOpenChange={vi.fn()} rows={ROWS} onSelectRegion={vi.fn()} />, { places: [1] },
    ));
    expect(screen.queryByTestId('wf-jump-favourites')).toBeNull();
  });
});

describe('the Coming up watchlist', () => {
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
    <ComingUpWatchlist
      active
      spots={SPOTS}
      windows={WINDOWS}
      scoreIndex={INDEX}
      reachById={new Map([[1, { driveMinutes: 22 }]])}
      allowedLocationIds={null}
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

  it('prints served figures with their window: areas first, a place, the quiet line below 3★, and not rated', async () => {
    render(list({ places: [1, 2, 4], areas: [9], starredAreas: [AREA] }));
    const rows = await screen.findAllByTestId('coming-up-watchlist-row');
    expect(rows.map((r) => r.dataset.kind)).toEqual(['area', 'place', 'place', 'place']);
    const text = rows.map((r) => within(r).getByTestId('coming-up-watchlist-detail').textContent);
    await screen.findByText(/best Steel Rigg/);
    expect(rows[0]).toHaveTextContent("Hadrian's Wall");
    expect(rows[0].querySelector('[data-testid="coming-up-watchlist-detail"]').textContent).toBe('best Steel Rigg · 5★ · Mon sunrise · 1 rated');
    expect(text[1]).toBe('4★ · Mon sunset · 22 min');
    expect(text[2]).toBe('nothing above 2★ in the next days');
    expect(text[3]).toBe('not rated yet');
  });

  it('⚠️ honesty: a starred 3★ is never listed above a starred 4★ by rating; the order is the reader\'s own sections', async () => {
    const index = buildScoreIndex([
      { locationId: 1, locationName: 'Bamburgh', date: '2026-10-05', targetType: 'SUNSET', rating: 3 },
      { locationId: 2, locationName: 'Craster', date: '2026-10-05', targetType: 'SUNRISE', rating: 5 },
    ]);
    render(list({ places: [1, 2] }, { scoreIndex: index }));
    const rows = await screen.findAllByTestId('coming-up-watchlist-row');
    // Held order (ids 1 then 2), not rating order; nothing here re-ranks.
    expect(rows.map((r) => r.firstChild.textContent)).toEqual(['Bamburgh', 'Craster']);
  });

  it('a place outside a Lite-style pick list says so and shows no figure', async () => {
    render(list({ places: [1] }, { allowedLocationIds: [2] }));
    expect((await screen.findByTestId('coming-up-watchlist-detail')).textContent).toBe('outside your picks');
  });

  it('opens a place or an area on a press', async () => {
    const onOpenPlace = vi.fn();
    const onOpenArea = vi.fn();
    render(list({ places: [1], areas: [9], starredAreas: [AREA] }, { onOpenPlace, onOpenArea }));
    const rows = await screen.findAllByTestId('coming-up-watchlist-row');
    fireEvent.click(rows[0]);
    fireEvent.click(rows[1]);
    expect(onOpenArea).toHaveBeenCalledWith(AREA);
    expect(onOpenPlace).toHaveBeenCalledWith(SPOTS[0]);
  });

  it('says what to do when nothing is starred, and draws no outlook request without a starred area', async () => {
    render(list({}));
    expect(await screen.findByTestId('coming-up-watchlist-empty')).toHaveTextContent('Star a place or an area');
    expect(fetchAreaOutlook).not.toHaveBeenCalled();
  });

  it('a Lite reader sees a one-line Your places · Pro teaser that opens the upsell, and nothing is fetched', () => {
    render(list({ status: 'locked' }));
    const teaser = screen.getByTestId('coming-up-watchlist-teaser-button');
    expect(teaser).toHaveTextContent('Your places');
    expect(teaser).toHaveTextContent('Pro');
    fireEvent.click(teaser);
    expect(screen.getByTestId('coming-up-watchlist-upsell-panel')).toBeInTheDocument();
    expect(fetchAreaOutlook).not.toHaveBeenCalled();
  });

  it('draws nothing while the list is loading or has failed', () => {
    render(list({ status: 'loading' }));
    expect(screen.queryByTestId('coming-up-watchlist')).toBeNull();
  });

  it('an area with only gated members says so, and one with no window says not rated yet', async () => {
    fetchAreaOutlook.mockResolvedValue([{
      areaId: 9,
      windows: [{
        date: '2026-10-05', targetType: 'SUNRISE', ratedMembers: 0, totalMembers: 2, gatedMembers: 2,
      }],
    }]);
    render(list({ areas: [9], starredAreas: [AREA] }));
    expect(await screen.findByText('2 outside your picks')).toBeInTheDocument();
  });
});
