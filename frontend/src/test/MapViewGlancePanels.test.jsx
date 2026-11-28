/**
 * `MapView` and the panels the Map gained for glance-to-map-plan.md G1: "Best bet · why", the warning expand and the
 * Regions menu in best-first order, and a Lite reader's view of them. The List door is `MapViewGlanceToMap.test.jsx`.
 * Leaflet and the layers that need a real Leaflet pane are mocked (`mapViewMocks.jsx`); `BottomSheet` is the kit's
 * passthrough (the real sheet is `MapViewRealSheets.test.jsx`).
 *
 * <p>What breaks if these fail: the reason panel grows the chrome group (and re-places the labels); a panel closes
 * and drops focus to the page; one Escape closes two layers; the attribution prints twice; the Regions menu ranks a
 * region the drilldown ranks differently, or says "in reach" of a count that is not about reach.
 */
import {
  describe, it, expect, beforeEach,
} from 'vitest';
import {
  act, screen, fireEvent, within,
} from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';
import { installGlanceHooks } from './mapViewGlanceHarness.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { TODAY, TOMORROW, PICK, CARD, cardsByKey } from './mapViewGlanceFixtures.js';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';

const { door, renderMap } = installGlanceHooks();

describe('the Map\'s Best bet · why', () => {
  it('draws nothing without a pick, for a Lite reader or an away origin the server scoped out', async () => {
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, pick: null }) });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
  });

  it('is a pill outside the window control; its reason opens in a panel UNDER the group, never inside it', async () => {
    await renderMap();
    const pill = screen.getByTestId('map-best-bet-why');
    const group = screen.getByTestId('wf-map-chrome-tl');
    expect(group).toContainElement(pill);
    expect(screen.getByTestId('wf-win-pill').parentElement).not.toContainElement(pill);
    expect(pill).toHaveTextContent('Best bet · why');
    expect(pill).toHaveAttribute('aria-expanded', 'false');
    expect(pill).not.toHaveAttribute('aria-controls');
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
    fireEvent.click(pill);
    const body = screen.getByTestId('map-best-bet-body');
    // The group's height cannot change on expand: nothing of the reason is inside it.
    expect(group.contains(body)).toBe(false);
    expect(pill).toHaveAttribute('aria-expanded', 'true');
    expect(pill).toHaveAttribute('aria-controls', body.id);
    expect(screen.getByTestId('map-best-bet-headline')).toHaveTextContent(PICK.headline);
    expect(screen.getByTestId('map-best-bet-detail')).toHaveTextContent(PICK.detail);
    expect(body.closest('[role="status"]')).toBeNull();
    fireEvent.click(pill);
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it('returns focus to the pill when it closes by its button or by Escape', async () => {
    await renderMap();
    const pill = screen.getByTestId('map-best-bet-why');
    fireEvent.click(pill);
    screen.getByTestId('map-best-bet-close').focus();
    fireEvent.click(screen.getByTestId('map-best-bet-close'));
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
    expect(document.activeElement).toBe(pill);
    fireEvent.click(pill);
    document.body.focus();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
    expect(document.activeElement).toBe(pill);
  });

  it('draws nothing for a window with no card (a night, a past window, a filler)', async () => {
    await renderMap({ windowCardsByKey: new Map() });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
  });

  it('closes when the window moves, because the pick belongs to the window', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    expect(screen.getByTestId('map-best-bet-body')).toBeInTheDocument();
    const sunrise = { ...CARD, key: windowKey(TODAY, 'SUNRISE'), targetType: 'SUNRISE' };
    await view.again({
      autoEventType: 'SUNRISE',
      windowCardsByKey: new Map([[CARD.key, CARD], [sunrise.key, sunrise]]),
    });
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });
});

describe('the Regions menu, best first', () => {
  const names = () => screen.getAllByTestId('wf-jump-row').map((row) => row.querySelector('.wf-jump-name').textContent);

  it('orders a solar window by the drilldown\'s own comparator, with the verdict word and "k of m at 4★+"', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    // The Borders is outside the area, so it has no verdict row and keeps its place after the ranked ones.
    expect(names()).toEqual(['The Lakes', 'North East', 'The Borders']);
    const lakes = screen.getAllByTestId('wf-jump-row')[0];
    expect(within(lakes).getByTestId('wf-jump-word')).toHaveTextContent('Worth it');
    expect(within(lakes).getByTestId('wf-jump-word')).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(within(lakes).getByTestId('wf-jump-count')).toHaveTextContent('1 of 1 at 4★+');
    const northEast = screen.getAllByTestId('wf-jump-row')[1];
    expect(within(northEast).getByTestId('wf-jump-word')).toHaveTextContent('Poor');
    expect(within(northEast).getByTestId('wf-jump-count')).toHaveTextContent('1 of 2 at 4★+');
    expect(screen.getByTestId('wf-jump-menu').textContent).not.toMatch(/in reach/i);
    expect(within(screen.getAllByTestId('wf-jump-row')[2]).queryByTestId('wf-jump-sub')).toBeNull();
  });

  it('keeps the drive order for a window with no regional verdict (here, one the index has no entry for)', async () => {
    await renderMap({ regionVerdictIndex: buildRegionVerdictIndex([]) });
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(names()).toEqual(['North East', 'The Borders', 'The Lakes']);
    expect(screen.getAllByTestId('wf-jump-row')).toHaveLength(3);
    expect(screen.queryByTestId('wf-jump-sub')).toBeNull();
  });
});

describe('the Map warning row (map-warning-row-plan.md M1-M2)', () => {
  it('is one full-width disclosure button per warning, with the chip as text and no link inside it', async () => {
    await renderMap();
    const row = screen.getByTestId('map-warning-row');
    expect(row.tagName).toBe('BUTTON');
    expect(row).toHaveAttribute('aria-expanded', 'false');
    expect(row).not.toHaveAttribute('aria-controls');
    expect(row).toHaveClass('w-full', 'min-h-[36px]');
    expect(within(row).getByTestId('warning-chip').tagName).toBe('SPAN');
    expect(row.querySelector('a')).toBeNull();
    expect(row).toHaveAccessibleName(/Amber warning of snow.*valid .* UK time/);
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('opens the warning in full under its row, inside the box, with the attribution printed once', async () => {
    await renderMap();
    const row = screen.getByTestId('map-warning-row');
    fireEvent.click(row);
    const region = screen.getByTestId('map-warning-region');
    expect(screen.getByTestId('wf-map-chrome-tl').contains(region)).toBe(true);
    expect(region).toHaveAttribute('role', 'region');
    expect(region).toHaveTextContent('UK time');
    expect(region).toHaveClass('max-h-[300px]', 'max-sm:max-h-[44vh]', 'overflow-y-auto');
    expect(row.parentElement).toContainElement(region);
    expect(region).toHaveAccessibleName(/Amber warning of snow/);
    expect(region).toHaveTextContent('Strong winds');
    expect(within(region).getByTestId('window-warning')).toBeInTheDocument();
    expect(within(region).queryByTestId('warning-chip')).toBeNull();
    expect(row).toHaveAttribute('aria-expanded', 'true');
    expect(row).toHaveAttribute('aria-controls', region.id);
    expect(screen.getAllByTestId('warning-attribution')).toHaveLength(1);
    fireEvent.click(row);
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(row).toHaveAttribute('aria-expanded', 'false');
  });

  it('keeps Escape returning focus to the row, and closes before the callout: one press, one layer', async () => {
    await renderMap();
    const row = screen.getByTestId('map-warning-row');
    fireEvent.click(row);
    screen.getByTestId('map-warning-source-link').focus();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(document.activeElement).toBe(row);
    fireEvent.click(row);
    document.body.focus();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(document.activeElement).toBe(row);
    fireEvent.click(screen.getByTestId('map-list-button'));
    await act(async () => { door.onOpenList.mock.calls[0][0].onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.click(row);
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.getByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout-name')).toBeNull();
  });

  it('does not steal focus when the row closes because the reader pressed a menu', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    const chip = screen.getByTestId('wf-jump-chip');
    chip.focus();
    fireEvent.click(chip);
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(document.activeElement).toBe(chip);
  });

  it('is exclusive with the Regions menu, the window drilldown and the Best bet reason, in both directions', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.queryByTestId('wf-jump-menu')).toBeNull();
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    // The drilldown (a value of the same menu state).
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    // The Best bet reason.
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.getByTestId('map-best-bet-body')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    // A menu closes the Best bet reason too.
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it('is the same in place on a phone: no sheet, no second dialog', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    const region = screen.getByTestId('map-warning-region');
    expect(region).toHaveAccessibleName(/Amber warning of snow/);
    expect(region).toHaveTextContent('Strong winds');
    expect(screen.getByTestId('wf-map-chrome-tl').contains(region)).toBe(true);
    expect(screen.queryByTestId('bottom-sheet-root')).toBeNull();
  });

  it('closes when the window moves to another event, even though the same warning still applies there', async () => {
    const view = await renderMap();
    const row = screen.getByTestId('map-warning-row');
    fireEvent.click(row);
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    const sunrise = { ...CARD, key: windowKey(TODAY, 'SUNRISE'), targetType: 'SUNRISE' };
    await view.again({
      autoEventType: 'SUNRISE',
      windowCardsByKey: new Map([[CARD.key, CARD], [sunrise.key, sunrise]]),
    });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.queryByTestId('map-warning-row')?.getAttribute('aria-expanded') ?? 'false').toBe('false');
  });
});

describe('a Lite reader', () => {
  beforeEach(() => { mapViewState.role = 'LITE_USER'; });

  it('gets the List, the warning row and the ranked Regions menu as a Pro reader does', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(door.onOpenList).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.getAllByTestId('wf-jump-row')[0]).toHaveTextContent('The Lakes');
  });

  it('shows no Best bet reason where the server served no pick', async () => {
    await renderMap({ windowCardsByKey: cardsByKey({ ...CARD, pick: null }) });
    expect(screen.queryByTestId('map-best-bet-why')).toBeNull();
    expect(screen.getByTestId('map-list-button')).toBeInTheDocument();
  });
});

describe('a panel cannot outlive the Map pane (glance-to-map review H1)', () => {
  it('on a phone, hiding the pane closes the open warning and it does not return with the pane', async () => {
    mapViewState.isMobile = true;
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    await view.again({ panelShown: false });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    await view.again({ panelShown: true });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.getByTestId('map-warning-row')).toHaveAttribute('aria-expanded', 'false');
  });

  it('on a phone, hiding the pane closes the Best bet reason too', async () => {
    mapViewState.isMobile = true;
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    expect(screen.getByTestId('map-best-bet-body')).toBeInTheDocument();
    await view.again({ panelShown: false });
    await view.again({ panelShown: true });
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it.each([
    ['Regions', 'wf-jump-chip', 'wf-jump-menu'],
    ['Filters', 'wf-filters-chip', 'wf-filters-panel'],
  ])('off a phone the %s menu is left exactly as it was when the pane hides', async (_name, chip, menu) => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId(chip));
    expect(screen.getByTestId(menu)).toBeInTheDocument();
    await view.again({ panelShown: false });
    await view.again({ panelShown: true });
    expect(screen.getByTestId(menu)).toBeInTheDocument();
  });

  it('off a phone the open warning is left exactly as it was (desktop and tablet unchanged)', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    await view.again({ panelShown: false });
    await view.again({ panelShown: true });
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
  });
});

describe('a warning the next poll withdraws (glance-to-map review L4)', () => {
  it('leaves no stale layer for Escape to spend a press on, and does not reopen when it returns', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    await act(async () => { door.onOpenList.mock.calls[0][0].onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-row'));
    expect(screen.getByTestId('map-warning-region')).toBeInTheDocument();
    // The window moves to a day the warning is not in force: the panel goes, its id must not linger.
    await view.again({ date: TOMORROW });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    // One Escape takes the callout, not a phantom warning layer.
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout-name')).toBeNull();
    // The warning returning with its window stays closed until pressed.
    await view.again({ date: TODAY });
    expect(screen.queryByTestId('map-warning-region')).toBeNull();
    expect(screen.getByTestId('map-warning-row')).toHaveAttribute('aria-expanded', 'false');
  });
});
