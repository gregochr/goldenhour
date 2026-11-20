/**
 * `MapView` and the four pieces the Map gained for glance-to-map-plan.md G1: the List door, "Best bet · why", the
 * warning expand and the Regions menu in best-first order. Leaflet and the layers that need a real Leaflet pane are
 * mocked (`mapViewMocks.jsx`); `BottomSheet` is the kit's passthrough.
 *
 * <p>What breaks if these fail: the List counts a different set of places than the Map draws; a window with no card
 * offers a dead List; the phone gets the desktop chip (or the desktop gets the phone row); the pick's reason shows for
 * a window the server gave no pick, or a warning's chip stops being a Met Office link; the Regions menu ranks a region
 * the drilldown ranks differently, or says "in reach" of a count that is not about reach.
 */
import {
  describe, it, expect, afterEach,
} from 'vitest';
import {
  act, screen, fireEvent, within,
} from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';
import { installGlanceHooks } from './mapViewGlanceHarness.jsx';
import { buildPastSolarWindows } from '../utils/mapEvents.js';
import { LIST_REASON } from '../utils/mapListReason.js';
import {
  TODAY, TOMORROW, SPOTS, SUNSET, CARD, heatProp, locations, rowOf, spotOf, cardsByKey,
} from './mapViewGlanceFixtures.js';

const { door, renderMap } = installGlanceHooks();
afterEach(() => {
  document.querySelectorAll('[data-test-foreign]').forEach((n) => n.remove());
});

describe('the Map List', () => {
  it('draws no List control with no shell to open it', async () => {
    await renderMap({ onOpenList: null });
    expect(screen.queryByTestId('map-list-button')).toBeNull();
  });

  it('sits beside Regions on desktop and opens the list for the window on screen', async () => {
    await renderMap();
    const cluster = screen.getByTestId('wf-map-chrome-tr');
    const button = within(cluster).getByTestId('map-list-button');
    expect(button).toHaveAttribute('aria-haspopup', 'dialog');
    expect(button).not.toHaveAttribute('aria-disabled');
    fireEvent.click(button);
    expect(door.onOpenList).toHaveBeenCalledTimes(1);
    const request = door.onOpenList.mock.calls[0][0];
    expect(request.key).toBe(SUNSET);
    // The Map's own floor (3★+ by default) and tier (Any with nothing measured), as the sheet's ids.
    expect(request.floorId).toBe('3');
    expect(request.tierId).toBe('any');
  });

  it.each([1, 2, 3, 4, 5])('hands the sheet the Map\'s own %i-star floor exactly', async (stars) => {
    localStorage.setItem('mapFilterMinStars', String(stars));
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(door.onOpenList.mock.calls[0][0].floorId).toBe(String(stars));
  });

  it('hands over exactly the places the Map holds in scope, so the two counts agree', async () => {
    await renderMap();
    const footer = screen.getByTestId('wf-map-counts-footer');
    const shown = Number(footer.querySelector('b').textContent);
    const total = Number(/of (\d+) shown/.exec(footer.textContent)[1]);
    fireEvent.click(screen.getByTestId('map-list-button'));
    const { spotKeys } = door.onOpenList.mock.calls[0][0];
    expect([...spotKeys].sort()).toEqual(['#1', '#2', '#3']);
    // The Map's "x of y shown" is over the same set (My area, three places).
    expect(spotKeys.size).toBe(total);
    expect(spotKeys.size).toBe(shown);
  });

  it('widens with the scope segment: Everywhere hands over the fourth place', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-filters-chip'));
    fireEvent.click(screen.getByTestId('wf-filters-scope-all'));
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect([...door.onOpenList.mock.calls[0][0].spotKeys].sort()).toEqual(['#1', '#2', '#3', '#4']);
  });

  it('closes the Map\'s open menu when it opens, and leaves it open when the shell refuses', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    door.onOpenList.mockReturnValueOnce(false);
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(screen.queryByTestId('wf-jump-menu')).toBeNull();
  });

  it('stands down while a dialog from outside the pane is over it', async () => {
    await renderMap();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-test-foreign', '');
    document.body.appendChild(foreign);
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(door.onOpenList).not.toHaveBeenCalled();
  });

  it.each([
    ['a window with no card (past, travel day, filler)', { windowCardsByKey: new Map() }, /There is no spot list for this window/],
    ['a window the shell holds no cards for at all', { windowCardsByKey: null }, /There is no spot list for this window/],
  ])('is greyed with its reason for %s, and presses nothing', async (_name, props, reason) => {
    await renderMap(props);
    const button = screen.getByTestId('map-list-button');
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).toHaveAttribute('aria-describedby', 'wf-map-list-why');
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(reason);
    fireEvent.click(button);
    expect(door.onOpenList).not.toHaveBeenCalled();
  });

  it('says why for a served window the shell holds no card for', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNRISE`);
    fireEvent.click(row);
    expect(screen.getByTestId('map-list-button')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('map-list-why')).toHaveTextContent('There is no spot list for this window');
  });

  it('says why on a past window, exactly', async () => {
    const yesterday = '2026-01-14';
    const locs = locations().map((loc) => ({
      ...loc,
      forecastsByDate: new Map([[yesterday, rowOf(4)], ...loc.forecastsByDate]),
    }));
    await renderMap({
      locations: locs,
      pastSolarWindows: buildPastSolarWindows(locs, TODAY),
      date: yesterday,
      forecastDates: [yesterday, TODAY, TOMORROW],
      autoEventType: 'SUNSET',
      windowCardsByKey: new Map(),
    });
    expect(screen.getByTestId('wf-win-past')).toBeInTheDocument();
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(LIST_REASON.PAST);
  });

  it('says there is no window when the Map has none selected, exactly', async () => {
    await renderMap({ heat: { ...heatProp(), windows: [] }, forecastDates: [], date: TOMORROW });
    expect(screen.getByTestId('map-list-button')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(LIST_REASON.NO_WINDOW);
  });

  it('says the exact no-card reason for a served window the shell holds no card for', async () => {
    await renderMap({ windowCardsByKey: new Map() });
    expect(screen.getByTestId('map-list-why')).toHaveTextContent(LIST_REASON.NO_CARD);
  });

  it('is not drawn on a phone: the peek sheet carries the door instead', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    expect(screen.queryByTestId('map-list-button')).toBeNull();
    expect(screen.queryByTestId('wf-map-chrome-tr')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-list')).toBeNull();
  });

  it('adds "See all N spots" to the phone Windows section, opening the list without closing it', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    const row = screen.getByTestId('wf-map-peek-list');
    expect(row).toHaveTextContent('See all 3 spots');
    fireEvent.click(row);
    expect(door.onOpenList).toHaveBeenCalledTimes(1);
    expect(door.onOpenList.mock.calls[0][0].key).toBe(SUNSET);
    expect(screen.getByTestId('wf-map-peek-windows')).toBeInTheDocument();
  });

  it.each([
    [3, 2], [4, 1], [5, 0], [2, 3], [1, 3],
  ])('counts "See all N" at the Map\'s %i-star floor, as the list will', async (stars, expected) => {
    localStorage.setItem('mapFilterMinStars', String(stars));
    mapViewState.isMobile = true;
    const rated = { ...CARD, allSpots: [spotOf(SPOTS[0], 3), spotOf(SPOTS[1], 2), spotOf(SPOTS[2], 4)] };
    await renderMap({ windowCardsByKey: cardsByKey(rated) });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.getByTestId('wf-map-peek-list')).toHaveTextContent(`See all ${expected} spot`);
  });

  it('says "1 spot" for a single place and draws no row for a window with no card', async () => {
    mapViewState.isMobile = true;
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, allSpots: [spotOf(SPOTS[0], 4)] }) });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.getByTestId('wf-map-peek-list')).toHaveTextContent('See all 1 spot');
    expect(screen.getByTestId('wf-map-peek-list')).not.toHaveTextContent('spots');
  });

  it('draws no phone row for a window with no card', async () => {
    mapViewState.isMobile = true;
    await renderMap({ windowCardsByKey: new Map() });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.queryByTestId('wf-map-peek-list')).toBeNull();
  });

  it('selects a place in place through the callback it hands over', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    const { onPick } = door.onOpenList.mock.calls[0][0];
    await act(async () => { onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toHaveTextContent('Wastwater');
  });
});

