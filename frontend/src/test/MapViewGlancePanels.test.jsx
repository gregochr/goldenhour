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
import { METOFFICE_URL } from './warningsFixture.js';

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

describe('the Map warning expand', () => {
  it('leaves the chip a Met Office link and adds a separate, finger-sized toggle beside it', async () => {
    await renderMap();
    const chip = screen.getByTestId('warning-chip');
    expect(chip).toHaveAttribute('href', METOFFICE_URL);
    const toggle = screen.getByTestId('map-warning-toggle');
    expect(toggle.contains(chip)).toBe(false);
    expect(chip.contains(toggle)).toBe(false);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle.className).toContain('min-h-[32px]');
    expect(toggle.className).toContain('max-sm:min-h-[44px]');
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('opens the warning in full in a panel under the chrome group, with the attribution printed once', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const panel = screen.getByTestId('map-warning-panel');
    const group = screen.getByTestId('wf-map-chrome-tl');
    expect(group.contains(panel)).toBe(false);
    expect(panel).toHaveTextContent('Amber warning of snow');
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-controls', panel.id);
    expect(screen.getAllByTestId('warning-attribution')).toHaveLength(1);
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
  });

  it('returns focus to the Details toggle when it closes by its button or by Escape', async () => {
    await renderMap();
    const toggle = screen.getByTestId('map-warning-toggle');
    fireEvent.click(toggle);
    screen.getByTestId('map-warning-panel-close').focus();
    fireEvent.click(screen.getByTestId('map-warning-panel-close'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(document.activeElement).toBe(toggle);
    fireEvent.click(toggle);
    document.body.focus();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(document.activeElement).toBe(toggle);
  });

  it('closes before the callout on Escape: one press takes exactly one layer', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    await act(async () => { door.onOpenList.mock.calls[0][0].onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout-name')).toBeNull();
  });

  it('does not steal focus when the panel closes because the reader pressed a menu', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const chip = screen.getByTestId('wf-jump-chip');
    chip.focus();
    fireEvent.click(chip);
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(document.activeElement).toBe(chip);
  });

  it('is exclusive with the Regions menu, the window drilldown and the Best bet reason, in both directions', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('wf-jump-menu')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('wf-jump-menu')).toBeNull();
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    // The drilldown (a value of the same menu state).
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('wf-win-panel')).toBeNull();
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    // The Best bet reason.
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('map-best-bet-body')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    // A menu closes the Best bet reason too.
    fireEvent.click(screen.getByTestId('map-best-bet-why'));
    fireEvent.click(screen.getByTestId('wf-jump-chip'));
    expect(screen.queryByTestId('map-best-bet-body')).toBeNull();
  });

  it('is a bottom sheet on a phone', async () => {
    mapViewState.isMobile = true;
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const panel = screen.getByTestId('map-warning-panel');
    expect(panel).toHaveTextContent('Amber warning of snow');
    expect(screen.queryByTestId('map-warning-panel-close')).toBeNull();
    expect(screen.getByTestId('wf-map-chrome-tl').contains(panel)).toBe(false);
  });
});

describe('a Lite reader', () => {
  beforeEach(() => { mapViewState.role = 'LITE_USER'; });

  it('gets the List, the warning expand and the ranked Regions menu as a Pro reader does', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    expect(door.onOpenList).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
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
  it('on a phone, hiding the pane closes the warning sheet and it does not return with the pane', async () => {
    mapViewState.isMobile = true;
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    await view.again({ panelShown: false });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    await view.again({ panelShown: true });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-expanded', 'false');
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

  it('off a phone the warning panel is left exactly as it was (desktop and tablet unchanged)', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    await view.again({ panelShown: false });
    await view.again({ panelShown: true });
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
  });
});

describe('a warning the next poll withdraws (glance-to-map review L4)', () => {
  it('leaves no stale layer for Escape to spend a press on, and does not reopen when it returns', async () => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('map-list-button'));
    await act(async () => { door.onOpenList.mock.calls[0][0].onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    // The window moves to a day the warning is not in force: the panel goes, its id must not linger.
    await view.again({ date: TOMORROW });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    // One Escape takes the callout, not a phantom warning layer.
    fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout-name')).toBeNull();
    // The warning returning with its window stays closed until pressed.
    await view.again({ date: TODAY });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('map-warning-toggle')).toHaveAttribute('aria-expanded', 'false');
  });
});
