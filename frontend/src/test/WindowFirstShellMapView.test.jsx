import React, {
  useEffect, useRef, useState,
} from 'react';
import { createPortal } from 'react-dom';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { useMapScreen } from '../context/MapScreenContext.jsx';
import { markEscapeAnswered } from '../utils/mapEscape.js';
import { parseMapRoute } from '../utils/mapRoute.js';
import { installViewport, resetViewport } from './askViewport.js';
import { matrixCtx, shellCtx, TODAY } from './askShellHarness.jsx';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * The map view (`docs/engineering/one-screen-plan.md` O1), through the real shell: `openMap` per door, the region that
 * is deliberately not a dialog, the inert screen, scroll and focus return, the layer url, Back order, Escape, the
 * load-time route, Find in both contexts and the Ask entries with the view open.
 *
 * <p>`App` is not mounted: `onOpenMapTab` is a spy (`App.openMapTabFromPlan`) and the pane is a stub that does what
 * `MapView` does with the view's context (publishes the header and route, portals the window control into the header,
 * and publishes an Escape ladder).
 */
vi.mock('../api/askApi.js', async (original) => {
  const real = await original();
  return {
    ...real, getReady: vi.fn(), ask: vi.fn(), getAskSettings: vi.fn(),
  };
});
import { getAskSettings, getReady } from '../api/askApi.js';
import { readyResponse, settings } from './askFixtures.js';
vi.mock('../api/almanacApi.js', () => ({
  ALMANAC_DAYS: 90,
  getAlmanac: vi.fn().mockResolvedValue({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  }),
}));
vi.mock('../api/areasApi.js', () => ({ fetchAreaDirectory: vi.fn(), fetchAreaOutlook: vi.fn() }));
import { fetchAreaDirectory, fetchAreaOutlook } from '../api/areasApi.js';

const seen = vi.hoisted(() => ({ search: null }));
vi.mock('../components/PlanSearch.jsx', () => ({
  default: (props) => {
    seen.search = props;
    return <div data-testid="plan-search-panel" />;
  },
}));

vi.setConfig({ testTimeout: 30000 });

const mounts = { count: 0, unmounts: 0 };

/** What `MapView` does with the view's context, reduced to its observable parts. */
function StubPane() {
  const mapScreen = useMapScreen();
  const [inner, setInner] = useState(false);
  const innerRef = useRef(false);
  useEffect(() => { innerRef.current = inner; });
  useEffect(() => {
    mounts.count += 1;
    return () => { mounts.unmounts += 1; };
  }, []);
  useEffect(() => {
    mapScreen?.publishHeader({
      title: 'Tonight Sunset', verdictLabel: 'Worth it', bestRating: 5, confidence: 'HIGH',
    });
    mapScreen?.publishRoute({ date: TODAY, eventType: 'SUNSET', locationId: 5 });
  }, [mapScreen]);
  const publishEscape = mapScreen?.publishEscape;
  const closeInner = () => {
    if (!innerRef.current) return false;
    setInner(false);
    return true;
  };
  useEffect(() => { publishEscape?.(closeInner); }, [publishEscape]);
  return (
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      className="wf-map-tab"
      data-testid="map-pane-stub"
      tabIndex={-1}
      onKeyDown={(event) => { if (event.key === 'Escape' && closeInner()) markEscapeAnswered(event.nativeEvent); }}
    >
      <button type="button" data-testid="inner-open" onClick={() => setInner(true)}>open inner</button>
      {inner && <div data-testid="inner-layer">inner</div>}
      {mapScreen?.windowSlot && createPortal(
        <button type="button" data-testid="stub-window-control">window</button>,
        mapScreen.windowSlot,
      )}
    </div>
  );
}

let browser;
const view = () => screen.getByTestId('map-screen');
const viewIsOpen = () => !screen.getByTestId('map-screen').hasAttribute('hidden');
const door = () => screen.getByTestId('window-first-tab-map');
const layers = () => __inspectForTests().layers;
const hash = () => browser.win.location.hash;

function mount({
  width = 1280, props = {}, ctx = {},
} = {}) {
  installViewport(width);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx({ ...matrixCtx(), ...ctx }));
  const onOpenMapTab = vi.fn();
  const onFindOnMap = vi.fn();
  const base = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    locations: [{ id: 11, name: 'Whitby' }],
    mapPane: <StubPane />,
    onOpenMapTab,
    onFindOnMap,
    forecastDates: [TODAY],
    ...props,
  };
  const ui = (next = {}) => (
    <AskProvider>
      <WindowFirstShell {...base} {...next} />
    </AskProvider>
  );
  const rendered = render(ui());
  return { ...rendered, onOpenMapTab, onFindOnMap, rerenderShell: (next) => rendered.rerender(ui(next)) };
}

async function openView() {
  await act(async () => { fireEvent.click(door()); });
  await browser.settle();
}

beforeEach(() => {
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  fetchAreaDirectory.mockResolvedValue([]);
  fetchAreaOutlook.mockResolvedValue({ outlook: [] });
  browser = installBackBrowser();
  mounts.count = 0;
  mounts.unmounts = 0;
  seen.search = null;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  resetViewport();
  uninstallBackBrowser();
  document.documentElement.style.overflow = '';
  document.querySelectorAll('[data-test-foreign]').forEach((node) => node.remove());
});

describe('the Map door beside the tabs', () => {
  it('is a plain button outside the tablist, and the tablist holds no Map tab', () => {
    mount();
    expect(door().tagName).toBe('BUTTON');
    expect(door().getAttribute('role')).toBeNull();
    expect(screen.getByRole('tablist').contains(door())).toBe(false);
    expect(screen.getAllByRole('tab').map((tab) => tab.id)).not.toContain('window-first-tab-map');
  });

  it('is withheld when the shell has no map pane', () => {
    mount({ props: { mapPane: null } });
    expect(screen.queryByTestId('window-first-tab-map')).toBeNull();
    expect(screen.queryByTestId('map-screen')).toBeNull();
  });

  it('is out of the roving index: arrowing along the tabs never lands on it', () => {
    mount();
    const tabs = screen.getAllByRole('tab');
    tabs[0].focus();
    fireEvent.keyDown(tabs[0], { key: 'End' });
    expect(document.activeElement).toBe(tabs[tabs.length - 1]);
    expect(document.activeElement).not.toBe(door());
  });
});

describe('opening the view', () => {
  it('mounts the pane only on the first open, and the view is a labelled region, not a dialog', async () => {
    mount();
    expect(screen.queryByTestId('map-screen')).toBeNull();
    expect(mounts.count).toBe(0);

    await openView();

    expect(mounts.count).toBe(1);
    expect(view().getAttribute('role')).toBe('region');
    expect(view().hasAttribute('hidden')).toBe(false);
    const labelledBy = view().getAttribute('aria-labelledby');
    expect(document.getElementById(labelledBy)).toBe(screen.getByTestId('map-screen-title'));
    expect(view().hasAttribute('aria-modal')).toBe(false);
  });

  it('has no [aria-modal] on or above the map pane while nothing is stacked', async () => {
    mount();
    await openView();
    const pane = screen.getByTestId('map-pane-stub');
    for (let node = pane; node; node = node.parentElement) {
      expect(node.hasAttribute('aria-modal'), node.tagName).toBe(false);
    }
    expect(document.querySelectorAll('[aria-modal="true"]')).toHaveLength(0);
  });

  it('makes the screen and masthead column inert, never the view, the dock column or the body', async () => {
    mount();
    expect(screen.getByTestId('window-first-shell-col').hasAttribute('inert')).toBe(false);
    await openView();
    expect(screen.getByTestId('window-first-shell-col').hasAttribute('inert')).toBe(true);
    expect(view().closest('[inert]')).toBeNull();
    expect(document.body.hasAttribute('inert')).toBe(false);
  });

  it('is rendered inside the shell root, never portalled', async () => {
    mount();
    await openView();
    expect(screen.getByTestId('window-first-shell').contains(view())).toBe(true);
  });

  it('puts initial focus on the title, which carries tabIndex -1', async () => {
    mount();
    await openView();
    const title = screen.getByTestId('map-screen-title');
    expect(title.getAttribute('tabindex')).toBe('-1');
    expect(document.activeElement).toBe(title);
  });

  it('draws the served header fields and portals the one window control into the header', async () => {
    mount();
    await openView();
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent('Tonight Sunset');
    expect(screen.getByTestId('map-screen-verdict')).toHaveTextContent('Worth it');
    expect(screen.getByTestId('map-screen-best')).toHaveTextContent('5★');
    expect(screen.getByTestId('map-screen-confidence')).toHaveTextContent('high confidence');
    const slot = screen.getByTestId('map-screen-window-slot');
    expect(slot.contains(screen.getByTestId('stub-window-control'))).toBe(true);
    expect(screen.getAllByTestId('stub-window-control')).toHaveLength(1);
  });

  it('shows the origin chip and the Find button in the header', async () => {
    mount();
    await openView();
    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('Home · Newcastle');
    expect(screen.getByTestId('map-screen-find')).toHaveTextContent('Find');
  });

  it('locks the body scroll while open and restores it on close, never scrolling the page', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    mount();
    expect(document.documentElement.style.overflow).toBe('');
    await openView();
    expect(document.documentElement.style.overflow).toBe('hidden');
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    expect(document.documentElement.style.overflow).toBe('');
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it('keeps the pane mounted behind the hidden attribute when closed, and reopens without a remount', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(view().hasAttribute('hidden')).toBe(true);
    expect(screen.getByTestId('window-first-shell-col').hasAttribute('inert')).toBe(false);
    expect(mounts.unmounts).toBe(0);

    await openView();
    expect(viewIsOpen()).toBe(true);
    expect(mounts.count).toBe(1);
  });

  it('closes the view when the map pane is withdrawn', async () => {
    const { rerenderShell } = mount();
    await openView();
    expect(viewIsOpen()).toBe(true);
    await act(async () => { rerenderShell({ mapPane: null }); });
    expect(screen.queryByTestId('map-screen')).toBeNull();
    expect(screen.getByTestId('window-first-shell-col').hasAttribute('inert')).toBe(false);
  });
});

describe('closing: focus returns to the door, Back is the same close', () => {
  it('the close button returns focus to the element that opened the view', async () => {
    mount();
    door().focus();
    await openView();
    expect(document.activeElement).toBe(screen.getByTestId('map-screen-title'));

    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();

    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(door());
  });

  it('Back closes the view, returns focus, and leaves no layer', async () => {
    mount();
    door().focus();
    await openView();
    expect(layers()).toBe(1);

    await browser.back();

    expect(viewIsOpen()).toBe(false);
    expect(layers()).toBe(0);
    expect(document.activeElement).toBe(door());
  });

  it('falls back to the selected tab when the opener is no longer on screen', async () => {
    mount();
    await openView();
    // A fresh opener that is gone by the time the view closes.
    const gone = document.createElement('button');
    document.body.appendChild(gone);
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    gone.remove();
    expect(document.activeElement).toBe(door());
  });

  it('a Glance card opens the view, and close returns focus to that same card', async () => {
    const { onOpenMapTab } = mount();
    const card = await screen.findByTestId('wf-heat-card-button');
    card.focus();
    await act(async () => { fireEvent.click(card); });
    await browser.settle();

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      kind: 'event', date: TODAY, eventType: 'SUNSET', inPlace: false, limitMinutes: 45,
    }));
    expect(viewIsOpen()).toBe(true);
    // The tab in force never moved.
    expect(screen.getByRole('tab', { selected: true }).id).toBe('window-first-tab-plan');

    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(document.activeElement).toBe(card);
  });
});

describe('the layer url', () => {
  it('pushes one entry carrying the pane\'s route, and Back returns to the bare path', async () => {
    mount();
    await openView();
    expect(layers()).toBe(1);
    expect(browser.history.pushState).toHaveBeenCalledTimes(1);
    expect(hash()).toBe(`#map/${TODAY}/sunset/5`);

    await browser.back();
    expect(hash()).toBe('');
  });

  it('a Find stacked over the view inherits the address, and Back closes Find first, the view last', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    await browser.settle();
    expect(screen.getByTestId('plan-search-panel')).toBeInTheDocument();
    expect(layers()).toBe(2);
    expect(hash()).toBe(`#map/${TODAY}/sunset/5`);

    await browser.back();
    expect(screen.queryByTestId('plan-search-panel')).toBeNull();
    expect(viewIsOpen()).toBe(true);
    expect(layers()).toBe(1);
    expect(hash()).toBe(`#map/${TODAY}/sunset/5`);

    await browser.back();
    expect(viewIsOpen()).toBe(false);
    expect(layers()).toBe(0);
  });

  it('adds no layer and pushes nothing when an open view is re-targeted', async () => {
    const { onOpenMapTab } = mount();
    await openView();
    const pushes = browser.history.pushState.mock.calls.length;

    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    await act(async () => { seen.search.onPickWindow(`${TODAY}:SUNSET`); });
    await browser.settle();

    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({ inPlace: true, date: TODAY }));
    expect(screen.queryByTestId('plan-search-panel')).toBeNull();
    expect(viewIsOpen()).toBe(true);
    expect(layers()).toBe(1);
    // Find's own entry was unwound and the view's kept: no extra entry was pushed for the view.
    expect(browser.history.pushState.mock.calls.length).toBe(pushes + 1);
  });
});

describe('Escape', () => {
  it('closes the view from the header when nothing is open inside it', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'Escape' }); });
    await browser.settle();
    expect(viewIsOpen()).toBe(false);
  });

  it('closes an inner layer first, from the header and from the pane, and the view on the next press', async () => {
    mount();
    await openView();
    fireEvent.click(screen.getByTestId('inner-open'));
    expect(screen.getByTestId('inner-layer')).toBeInTheDocument();

    // Pressed on the header: the pane's own handler never hears it, the view runs the ladder.
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'Escape' }); });
    expect(screen.queryByTestId('inner-layer')).toBeNull();
    expect(viewIsOpen()).toBe(true);

    fireEvent.click(screen.getByTestId('inner-open'));
    // Pressed on the pane: it answers, and the view does not answer the same press.
    await act(async () => { fireEvent.keyDown(screen.getByTestId('inner-open'), { key: 'Escape' }); });
    expect(screen.queryByTestId('inner-layer')).toBeNull();
    expect(viewIsOpen()).toBe(true);

    await act(async () => { fireEvent.keyDown(screen.getByTestId('inner-open'), { key: 'Escape' }); });
    await browser.settle();
    expect(viewIsOpen()).toBe(false);
  });

  it('stands down for a dialog over the pane', async () => {
    mount();
    await openView();
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-test-foreign', '');
    document.body.appendChild(foreign);
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'Escape' }); });
    expect(viewIsOpen()).toBe(true);
  });

  it('ignores other keys, and a press already handled', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'a' }); });
    expect(viewIsOpen()).toBe(true);
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    event.preventDefault();
    await act(async () => { screen.getByTestId('map-screen-close').dispatchEvent(event); });
    expect(viewIsOpen()).toBe(true);
  });
});

describe('the scrim', () => {
  it('closes the view when nothing is stacked over it', async () => {
    mount();
    await openView();
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('map-screen-scrim'));
      fireEvent.click(screen.getByTestId('map-screen-scrim'));
    });
    await browser.settle();
    expect(viewIsOpen()).toBe(false);
  });

  it('does not close on the pointer going down: a touch would hand the same tap to what is under the view', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.pointerDown(screen.getByTestId('map-screen-scrim')); });
    expect(viewIsOpen()).toBe(true);
  });

  it('does not close on a click that did not begin on the scrim', async () => {
    mount();
    await openView();
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('inner-open'));
      fireEvent.click(screen.getByTestId('map-screen-scrim'));
    });
    expect(viewIsOpen()).toBe(true);
  });

  it('forgets a scrim press once the pointer goes down anywhere else', async () => {
    mount();
    await openView();
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('map-screen-scrim'));
      fireEvent.pointerDown(screen.getByTestId('inner-open'));
      fireEvent.click(screen.getByTestId('map-screen-scrim'));
    });
    expect(viewIsOpen()).toBe(true);
  });

  it('leaves the view when Find stands over it', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('map-screen-scrim'));
      fireEvent.click(screen.getByTestId('map-screen-scrim'));
    });
    expect(viewIsOpen()).toBe(true);
  });
});

describe('the load-time route', () => {
  const route = (extra = {}) => ({
    date: TODAY, eventType: 'SUNSET', locationId: 11, ...extra,
  });

  it('opens the view on the named window and place, activation-gated: the first gesture pushes the entry', async () => {
    const handled = vi.fn();
    const { onOpenMapTab } = mount({ props: { loadMapRoute: route(), onLoadMapRouteHandled: handled } });
    await act(async () => {});

    expect(viewIsOpen()).toBe(true);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({
      kind: 'location', locationName: 'Whitby', date: TODAY, eventType: 'SUNSET', inPlace: false,
    }));
    expect(handled).toHaveBeenCalledTimes(1);
    // No gesture yet: no entry, no address, Back is the browser's.
    expect(layers()).toBe(0);
    expect(browser.history.pushState).not.toHaveBeenCalled();

    await act(async () => { fireEvent.click(document.body); });
    await browser.settle();
    expect(layers()).toBe(1);
    expect(browser.history.pushState).toHaveBeenCalledTimes(1);
    expect(hash()).toMatch(/^#map\/2026-10-05\/sunset/);
  });

  it('opens the window with no place for an unknown or disabled place id', async () => {
    const { onOpenMapTab } = mount({ props: { loadMapRoute: route({ locationId: 999 }) } });
    await act(async () => {});
    expect(viewIsOpen()).toBe(true);
    const door = onOpenMapTab.mock.calls[0][0];
    expect(door.kind).toBe('event');
    expect(door).not.toHaveProperty('locationName');
    expect(screen.queryByTestId('screen-note')).toBeNull();
  });

  it('opens on the map\'s own window with a note for a window that has passed, and the note dismisses', async () => {
    const { onOpenMapTab } = mount({ props: { loadMapRoute: route({ date: '2026-10-04', eventType: 'SUNRISE' }) } });
    await act(async () => {});
    expect(viewIsOpen()).toBe(true);
    expect(onOpenMapTab).not.toHaveBeenCalled();
    expect(screen.getByTestId('screen-note')).toHaveTextContent('That sunrise has passed');
    await act(async () => { fireEvent.click(screen.getByTestId('screen-note-dismiss')); });
    expect(screen.queryByTestId('screen-note')).toBeNull();
  });

  it('says "Not forecast yet" for a future date the forecast does not cover', async () => {
    mount({ props: { loadMapRoute: route({ date: '2026-10-12', eventType: 'SUNRISE' }) } });
    await act(async () => {});
    expect(screen.getByTestId('screen-note')).toHaveTextContent('Not forecast yet');
  });

  it('judges today\'s window that no longer has a card as passed', async () => {
    mount({ props: { loadMapRoute: route({ eventType: 'SUNRISE' }) } });
    await act(async () => {});
    expect(screen.getByTestId('screen-note')).toHaveTextContent('That sunrise has passed');
  });

  it('waits for the briefing and for the forecast dates before judging the window', async () => {
    const { rerenderShell } = mount({ props: { forecastDates: null, loadMapRoute: route() } });
    await act(async () => {});
    expect(screen.queryByTestId('map-screen')).toBeNull();
    await act(async () => { rerenderShell({ forecastDates: [TODAY] }); });
    expect(viewIsOpen()).toBe(true);
  });

  it('opens on the bare #map route with no window, and acts once', async () => {
    const handled = vi.fn();
    const { onOpenMapTab, rerenderShell } = mount({
      props: { loadMapRoute: { date: null, eventType: null, locationId: null }, onLoadMapRouteHandled: handled },
    });
    await act(async () => {});
    expect(viewIsOpen()).toBe(true);
    expect(onOpenMapTab).not.toHaveBeenCalled();
    await act(async () => { rerenderShell({ forecastDates: [TODAY, '2026-10-06'] }); });
    expect(handled).toHaveBeenCalledTimes(1);
  });

  it('the device landing preference opens the view, but only until the reader acts', async () => {
    mount({ props: { loadMapRoute: { preferred: true } } });
    await act(async () => {});
    expect(viewIsOpen()).toBe(true);
  });

  it('drops the landing preference once the reader has acted first, and spends it', async () => {
    const handled = vi.fn();
    const { rerenderShell } = mount({
      props: { mapPane: null, loadMapRoute: { preferred: true }, onLoadMapRouteHandled: handled },
    });
    await act(async () => { fireEvent.pointerDown(screen.getByTestId('window-first-shell')); });
    await act(async () => { rerenderShell({ mapPane: <StubPane /> }); });
    expect(screen.queryByTestId('map-screen')).toBeNull();
    expect(handled).toHaveBeenCalledTimes(1);
  });

  it('honours an address even after the reader has acted (a request, not a preference)', async () => {
    const { rerenderShell } = mount({ props: { mapPane: null, loadMapRoute: route() } });
    await act(async () => { fireEvent.pointerDown(screen.getByTestId('window-first-shell')); });
    await act(async () => { rerenderShell({ mapPane: <StubPane /> }); });
    expect(viewIsOpen()).toBe(true);
  });
});

describe('the registered opener (the aurora banner, outside the shell)', () => {
  it('registers open and close with App and withdraws them on unmount', async () => {
    const register = vi.fn();
    const { unmount } = mount({ props: { registerOpenMap: register } });
    const doors = register.mock.calls[0][0];
    await act(async () => { doors.open({}); });
    await browser.settle();
    expect(viewIsOpen()).toBe(true);
    await act(async () => { doors.close({ restoreFocus: false }); });
    await browser.settle();
    expect(viewIsOpen()).toBe(false);
    unmount();
    expect(register).toHaveBeenLastCalledWith(null);
  });

  it('a plain open hands the map no door of its own (the caller already did)', async () => {
    const register = vi.fn();
    const { onOpenMapTab } = mount({ props: { registerOpenMap: register } });
    await act(async () => { register.mock.calls[0][0].open({}); });
    expect(onOpenMapTab).not.toHaveBeenCalled();
  });
});

describe('Find in both contexts', () => {
  it('on the screen it is the screen context; in the view it is the map context', async () => {
    mount();
    await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
    expect(seen.search.context).toBe('screen');
    await act(async () => { fireEvent.keyDown(document, { key: 'k', metaKey: true }); });
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    expect(seen.search.context).toBe('map');
  });

  it('on the map a place, area and region pick stay in the view: no sheet, no origin move', async () => {
    const setOrigin = vi.fn();
    const { onFindOnMap } = mount({ ctx: { setOrigin } });
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    await act(async () => { seen.search.onPickLocation({ id: 11, name: 'Whitby' }); });
    expect(onFindOnMap).toHaveBeenLastCalledWith({ kind: 'location', id: 11, name: 'Whitby' });
    await act(async () => { seen.search.onPickRegion({ name: 'North York Moors & Coast' }); });
    expect(onFindOnMap).toHaveBeenLastCalledWith({ kind: 'region', name: 'North York Moors & Coast' });
    await act(async () => { seen.search.onPickArea({ id: 3, name: 'Moors', locationIds: [11] }); });
    expect(onFindOnMap).toHaveBeenLastCalledWith({
      kind: 'area', id: 3, name: 'Moors', ids: [11],
    });
    expect(setOrigin).not.toHaveBeenCalled();
    expect(screen.queryByTestId('location-sheet')).toBeNull();
    expect(viewIsOpen()).toBe(true);
  });

  it('on the screen a window pick closes Find and opens the view, with Find\'s entry unwound for the view\'s', async () => {
    const { onOpenMapTab } = mount();
    await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
    await browser.settle();
    expect(layers()).toBe(1);

    await act(async () => { seen.search.onPickWindow(`${TODAY}:SUNSET`); });
    await browser.settle();

    expect(screen.queryByTestId('plan-search-panel')).toBeNull();
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'event', inPlace: false }));
    expect(viewIsOpen()).toBe(true);
    // One entry, the view's: Find's was taken over in the same commit.
    expect(layers()).toBe(1);
  });

  it('⌘K works with the view open, from the header', async () => {
    mount();
    await openView();
    await act(async () => { fireEvent.keyDown(document, { key: 'k', metaKey: true }); });
    expect(screen.getByTestId('plan-search-panel')).toBeInTheDocument();
    expect(seen.search.context).toBe('map');
  });
});

describe('Ask with the view open', () => {
  it('on a tablet the Ask field is in the header and not in the tab row', async () => {
    mount({ width: 800 });
    await screen.findByTestId('ask-field');
    expect(screen.getByTestId('window-first-tabrow').contains(screen.getByTestId('ask-field'))).toBe(true);

    await openView();

    const fields = screen.getAllByTestId('ask-field');
    expect(fields).toHaveLength(1);
    expect(screen.getByTestId('map-screen-header').contains(fields[0])).toBe(true);
    expect(screen.getByTestId('window-first-tabrow').contains(fields[0])).toBe(false);
  });

  it('on a phone the Ask bar is withdrawn while the view is open and returns when it closes', async () => {
    mount({ width: 390 });
    await screen.findByTestId('ask-bar');
    await openView();
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(await screen.findByTestId('ask-bar')).toBeInTheDocument();
  });

  it('on a desktop the Ask field opens the dock, which stays interactive beside the view', async () => {
    mount({ width: 1280 });
    const field = await screen.findByTestId('ask-field');
    await openView();
    const headerField = screen.getAllByTestId('ask-field')[0];
    expect(screen.getByTestId('map-screen-header').contains(headerField)).toBe(true);
    expect(field.isConnected && screen.getByTestId('window-first-tabrow').contains(field)).toBe(false);

    await act(async () => { fireEvent.click(headerField); });
    const dock = await screen.findByTestId('ask-dock');
    expect(dock.closest('[inert]')).toBeNull();
    expect(viewIsOpen()).toBe(true);
    expect(view().closest('[inert]')).toBeNull();
    expect(screen.getByTestId('window-first-shell-col').hasAttribute('inert')).toBe(true);
  });

  it('/ opens the dock with the view open', async () => {
    mount({ width: 1280 });
    await screen.findByTestId('ask-field');
    await openView();
    await act(async () => { fireEvent.keyDown(document.body, { key: '/' }); });
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
  });
});

describe('openMap request shapes (the plan\'s window and focus arguments)', () => {
  const doors = async (props = {}) => {
    const register = vi.fn();
    const view = mount({ props: { registerOpenMap: register, ...props } });
    return { ...view, open: (request) => act(async () => { register.mock.calls[0][0].open(request); }) };
  };
  const win = { date: TODAY, eventType: 'SUNSET' };

  it('a window alone is the event door, and opens the view', async () => {
    const { open, onOpenMapTab } = await doors();
    await open({ window: win });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'event', date: TODAY, eventType: 'SUNSET', inPlace: false,
    }));
    expect(viewIsOpen()).toBe(true);
  });

  it('a place by id is resolved against the roster and sent as the location door, and seeds the address', async () => {
    const { open, onOpenMapTab } = await doors();
    await open({ window: win, focus: { kind: 'location', id: 11 } });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'location', locationName: 'Whitby', date: TODAY, eventType: 'SUNSET',
    }));
    await browser.settle();
    expect(hash()).toMatch(/^#map\/2026-10-05\/sunset/);
  });

  it('a region focus is the region door', async () => {
    const { open, onOpenMapTab } = await doors();
    await open({ window: win, focus: { kind: 'region', name: 'North York Moors & Coast' } });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({
      kind: 'region', region: 'North York Moors & Coast', date: TODAY, eventType: 'SUNSET',
    }));
  });

  it('an aurora focus is the aurora door, named for its night', async () => {
    const { open, onOpenMapTab } = await doors();
    await open({ window: { date: TODAY, eventType: 'AURORA' }, focus: { kind: 'aurora' } });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({ kind: 'aurora', date: TODAY }));
  });

  it('a names focus carries its spots as a plain door', async () => {
    const { open, onOpenMapTab } = await doors();
    await open({ window: win, focus: { kind: 'names', focusNames: ['Whitby'] } });
    expect(onOpenMapTab).toHaveBeenLastCalledWith(expect.objectContaining({
      date: TODAY, targetType: 'SUNSET', focusNames: ['Whitby'], region: null,
    }));
    expect(onOpenMapTab.mock.calls.at(-1)[0]).not.toHaveProperty('kind');
  });

  it('with no map door to hand the request to (nothing to map), the view still opens and the layers close', async () => {
    const { open } = await doors({ onOpenMapTab: undefined });
    await open({ window: win });
    expect(viewIsOpen()).toBe(true);
  });

  it('the dock\'s context label names the map while the view is open', async () => {
    mount({ width: 1280 });
    await screen.findByTestId('ask-field');
    await openView();
    await act(async () => { fireEvent.click(screen.getAllByTestId('ask-field')[0]); });
    expect(await screen.findByTestId('ask-dock-context')).toHaveTextContent('on Map');
  });
});

describe('review round 1: focus, the address and the origin', () => {
  const selectedTab = () => screen.getByRole('tab', { selected: true });

  it('✕ after a load-time open puts focus on the selected tab, never on the hidden title', async () => {
    mount({ props: { loadMapRoute: { date: TODAY, eventType: 'SUNSET', locationId: 11 } } });
    await act(async () => {});
    expect(viewIsOpen()).toBe(true);

    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();

    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(selectedTab());
    expect(document.activeElement).not.toBe(screen.getByTestId('map-screen-title'));
  });

  it('✕ after the registered opener (the aurora banner\'s door) also lands on the selected tab', async () => {
    const register = vi.fn();
    mount({ props: { registerOpenMap: register } });
    await act(async () => { register.mock.calls[0][0].open({}); });
    await browser.settle();
    expect(viewIsOpen()).toBe(true);

    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();

    expect(document.activeElement).toBe(selectedTab());
  });

  it('Escape and Back put focus on the selected tab too when the opener left no element behind', async () => {
    mount({ props: { loadMapRoute: { date: TODAY, eventType: 'SUNSET', locationId: null } } });
    await act(async () => {});
    await act(async () => { fireEvent.click(document.body); });
    await browser.settle();
    await browser.back();
    expect(viewIsOpen()).toBe(false);
    expect(document.activeElement).toBe(selectedTab());
  });

  it('the callout\'s "Open in Plan" takes the #map address down, with the view alone', async () => {
    const { rerenderShell } = mount();
    await openView();
    expect(hash()).toMatch(/^#map\//);

    await act(async () => {
      rerenderShell({ locationSheetHandoff: { id: 11, name: 'Whitby', regionName: null, inPlan: true, nonce: 1 } });
    });
    await browser.settle();

    expect(viewIsOpen()).toBe(false);
    expect(hash()).toBe('');
  });

  it('...and with Find already stacked over the view', async () => {
    const { rerenderShell } = mount();
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-find')); });
    await browser.settle();
    expect(layers()).toBe(2);

    await act(async () => {
      rerenderShell({ locationSheetHandoff: { id: 11, name: 'Whitby', regionName: null, inPlan: true, nonce: 1 } });
    });
    await browser.settle();

    expect(viewIsOpen()).toBe(false);
    expect(hash()).toBe('');
  });

  it('an inPlan handoff with the view CLOSED does not leave a skip that costs the next ✕ its focus return', async () => {
    const { rerenderShell } = mount();
    await act(async () => {
      rerenderShell({ locationSheetHandoff: { id: 11, name: 'Whitby', regionName: null, inPlan: true, nonce: 1 } });
    });
    await browser.settle();
    expect(screen.queryByTestId('map-screen')).toBeNull();

    door().focus();
    await openView();
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();

    expect(document.activeElement).toBe(door());
  });

  it('reopening through the Map door writes the pane\'s current route, not a bare or stale address', async () => {
    mount();
    await openView();
    expect(hash()).toBe(`#map/${TODAY}/sunset/5`);
    await act(async () => { fireEvent.click(screen.getByTestId('map-screen-close')); });
    await browser.settle();
    expect(hash()).toBe('');

    await openView();

    expect(hash()).toBe(`#map/${TODAY}/sunset/5`);
  });

  it('a night\'s address reopens the night: the aurora and astro doors, not a solar window', async () => {
    const aurora = mount({ props: { loadMapRoute: { date: TODAY, eventType: 'AURORA', locationId: null } } });
    await act(async () => {});
    expect(aurora.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'aurora', date: TODAY }));
    cleanup();
    const astro = mount({ props: { loadMapRoute: { date: TODAY, eventType: 'ASTRO', locationId: null } } });
    await act(async () => {});
    expect(astro.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'astro', date: TODAY }));
    expect(viewIsOpen()).toBe(true);
  });

  it('a night dated YESTERDAY opens its door with no "passed" note (the running night in the small hours)', async () => {
    const { onOpenMapTab } = mount({
      props: { loadMapRoute: { date: '2026-10-04', eventType: 'AURORA', locationId: null } },
    });
    await act(async () => {});
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'aurora', date: '2026-10-04' }));
    expect(screen.queryByTestId('screen-note')).toBeNull();
  });

  it('a night from two days ago is passed: the map\'s own window, with the note', async () => {
    const { onOpenMapTab } = mount({
      props: { loadMapRoute: { date: '2026-10-03', eventType: 'AURORA', locationId: null } },
    });
    await act(async () => {});
    expect(onOpenMapTab).not.toHaveBeenCalled();
    expect(screen.getByTestId('screen-note')).toHaveTextContent('That aurora has passed');
  });

  it('a night round-trips: open it, read the address it wrote, and the route reopens the same night door', async () => {
    const register = vi.fn();
    const first = mount({ props: { registerOpenMap: register } });
    await act(async () => {
      register.mock.calls[0][0].open({ window: { date: TODAY, eventType: 'ASTRO' }, focus: { kind: 'astro' } });
    });
    await browser.settle();
    expect(hash()).toMatch(new RegExp(`^#map/${TODAY}/(night|sunset)`));
    expect(first.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'astro', date: TODAY }));
    cleanup();

    const route = parseMapRoute(`#map/${TODAY}/night`);
    const again = mount({ props: { loadMapRoute: route } });
    await act(async () => {});
    expect(again.onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ kind: 'astro', date: TODAY }));
  });

  it('reports no tab at all when the shell goes away with the view open, so the page behind never stays inert', async () => {
    const onTabChange = vi.fn();
    const { unmount } = mount({ props: { onTabChange } });
    await openView();
    expect(onTabChange).toHaveBeenLastCalledWith('map');

    unmount();

    expect(onTabChange).toHaveBeenLastCalledWith(null);
  });

  it('a re-render with a new onTabChange does not report null while the shell is still mounted', async () => {
    const first = vi.fn();
    const { rerenderShell } = mount({ props: { onTabChange: first } });
    await openView();
    const second = vi.fn();
    await act(async () => { rerenderShell({ onTabChange: second }); });
    expect(first).not.toHaveBeenCalledWith(null);
    expect(second).not.toHaveBeenCalledWith(null);
  });

  it('an away origin can be undone from the view\'s header, and focus lands on the title, not <body>', async () => {
    const setOrigin = vi.fn();
    mount({ ctx: { origin: { id: 'lakes', name: 'Lake District', baseName: 'Keswick' }, setOrigin } });
    await openView();

    expect(screen.getByTestId('map-screen-origin')).toHaveTextContent('From Keswick');
    const home = screen.getByTestId('map-screen-origin-home');
    expect(home).toHaveAccessibleName('Plan from home again');
    await act(async () => { fireEvent.click(home); });

    expect(setOrigin).toHaveBeenCalledExactlyOnceWith(null);
    expect(document.activeElement).toBe(screen.getByTestId('map-screen-title'));
  });

  it('draws no ⌂ at home', async () => {
    mount();
    await openView();
    expect(screen.queryByTestId('map-screen-origin-home')).toBeNull();
  });
});
