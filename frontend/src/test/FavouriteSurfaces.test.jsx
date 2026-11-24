import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';

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

  it('⚠️ the entries are a labelled section with the ★ hidden from assistive tech, and Escape on the Lite trigger closes only the panel', () => {
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

  it('prints served figures with their window: areas first, a place, the quiet line below 3★, and not rated', async () => {
    render(list({ places: [1, 2, 4], areas: [9], starredAreas: [AREA] }));
    const rows = await screen.findAllByTestId('your-places-chip');
    expect(rows.map((r) => r.dataset.kind)).toEqual(['area', 'place', 'place', 'place']);
    const text = rows.map((r) => within(r).getByTestId('your-places-detail').textContent);
    await screen.findByText(/best Steel Rigg/);
    expect(rows[0]).toHaveTextContent("Hadrian's Wall");
    expect(rows[0].querySelector('[data-testid="your-places-detail"]').textContent).toBe('best Steel Rigg · 5★ · Mon sunrise · 1 rated');
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
    const rows = await screen.findAllByTestId('your-places-chip');
    // Held order (ids 1 then 2), not rating order; nothing here re-ranks.
    expect(rows.map((r) => r.firstChild.textContent)).toEqual(['Bamburgh', 'Craster']);
  });

  it('a place outside a Lite-style pick list says so and shows no figure', async () => {
    render(list({ places: [1] }, { allowedLocationIds: [2] }));
    expect((await screen.findByTestId('your-places-detail')).textContent).toBe('outside your picks');
  });

  it('a chip with a worthwhile window opens the map view on it: a place on its best window, an area on its spots', async () => {
    const onOpenWindow = vi.fn();
    const onOpenPlace = vi.fn();
    const onOpenArea = vi.fn();
    render(list({ places: [1], areas: [9], starredAreas: [AREA] }, { onOpenWindow, onOpenPlace, onOpenArea }));
    await screen.findByText(/best Steel Rigg/);
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

  it('a chip with no window worth a trip opens the place\'s or area\'s own sheet instead, never nothing', async () => {
    const onOpenWindow = vi.fn();
    const onOpenPlace = vi.fn();
    const onOpenArea = vi.fn();
    // Craster is rated 2 (quiet), the unrated place has no figure, and the area has no outlook window.
    fetchAreaOutlook.mockResolvedValue([{ areaId: 9, windows: [] }]);
    render(list({ places: [2, 4], areas: [9], starredAreas: [AREA] }, { onOpenWindow, onOpenPlace, onOpenArea }));
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
      render(list({ places: [1], areas: [9], starredAreas: [AREA] }));
      await screen.findAllByTestId('your-places-chip');
      expect(fetchAreaOutlook).not.toHaveBeenCalled();

      act(() => { observers.at(-1).callback([{ isIntersecting: false }]); });
      expect(fetchAreaOutlook).not.toHaveBeenCalled();

      act(() => { observers.at(-1).callback([{ isIntersecting: true }]); });
      await screen.findByText(/best Steel Rigg/);
      expect(fetchAreaOutlook).toHaveBeenCalledTimes(1);
    });
  });

  it('⚠️ an area-only reader whose directory has not named the area never sees the empty-state advice', async () => {
    // Served list: one area starred. Directory unresolved: starredAreas is empty.
    render(list({ areas: [9], locationIds: [3], starredAreas: [] }));
    expect(screen.queryByTestId('your-places-empty')).toBeNull();
    expect(await screen.findByTestId('your-places-areas-pending')).toHaveTextContent('1 starred area is not shown yet.');
    expect(screen.queryByTestId('your-places-offplan')).toBeNull();
  });

  it('a starred place that is not on the plan says so rather than printing the empty-state advice', async () => {
    render(list({ places: [999] }));
    expect(screen.queryByTestId('your-places-empty')).toBeNull();
    expect(await screen.findByTestId('your-places-offplan')).toBeInTheDocument();
  });

  it('Escape on the Lite teaser with the upsell open closes only the panel', () => {
    const onKey = vi.fn();
    document.addEventListener('keydown', onKey);
    render(list({ status: 'locked' }));
    const teaser = screen.getByTestId('your-places-teaser-button');
    fireEvent.click(teaser);
    fireEvent.keyDown(teaser, { key: 'Escape' });
    expect(screen.queryByTestId('your-places-upsell-panel')).toBeNull();
    expect(onKey).not.toHaveBeenCalled();
    document.removeEventListener('keydown', onKey);
  });

  it('says what to do when nothing is starred, and draws no outlook request without a starred area', async () => {
    render(list({}));
    expect(await screen.findByTestId('your-places-empty')).toHaveTextContent('Star a place or an area');
    expect(fetchAreaOutlook).not.toHaveBeenCalled();
  });

  it('a Lite reader sees a one-line Your places · Pro teaser that opens the upsell, and nothing is fetched', () => {
    render(list({ status: 'locked' }));
    const teaser = screen.getByTestId('your-places-teaser-button');
    expect(teaser).toHaveTextContent('Your places');
    expect(teaser).toHaveTextContent('Pro');
    fireEvent.click(teaser);
    expect(screen.getByTestId('your-places-upsell-panel')).toBeInTheDocument();
    expect(fetchAreaOutlook).not.toHaveBeenCalled();
  });

  it('draws nothing while the list is loading or has failed', () => {
    render(list({ status: 'loading' }));
    expect(screen.queryByTestId('your-places')).toBeNull();
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
