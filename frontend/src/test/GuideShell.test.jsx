import React, { useCallback, useRef, useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import { GuideProvider } from '../context/GuideContext.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { GUIDE_ORIGIN } from '../utils/guide.js';
import { installViewport, resetViewport } from './askViewport.js';
import {
  MAP_PANE, OPERATIONS_PANE, matrixCtx, shellCtx,
} from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings,
} from './askFixtures.js';

/**
 * The guide as the Plan shell wires it (docs/engineering/user-guide-plan.md, G2): the masthead `?` on
 * desktop and tablet, the ⋯ menu's Guide row on a phone, the docked column taking the Ask dock's slot
 * (mutually exclusive both ways, restore without autofocus), the stacked protocol on the shell's own
 * dialogs, the Ask dock and sheet `?`, and the tab-change behaviour.
 *
 * <p>jsdom has no layout and no {@code inert}; what is asserted is the structure the shell builds and
 * the behaviour it has. The z-index, the docked layout and the frame itself are a browser check.
 */
vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(() => Promise.resolve({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  })),
  ALMANAC_DAYS: 90,
}));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { getAskSettings, getReady } from '../api/askApi.js';

// The first test to open a lazy chunk pays for fetching it.
vi.setConfig({ testTimeout: 30000 });

/** Mounts the shell the way App does: Ask provider, guide provider, the shell registering its closer. */
function Rig({ shellProps }) {
  const [tab, setTab] = useState('plan');
  const closer = useRef(null);
  const register = useCallback((fn) => { closer.current = fn; }, []);
  const closeShellDialogs = useCallback(() => closer.current?.(), []);
  return (
    <AskProvider>
      <GuideProvider closeShellDialogs={closeShellDialogs} activeTab={tab}>
        <WindowFirstShell
          onOpenSettings={vi.fn()}
          onSignOut={vi.fn()}
          onShowOnMap={vi.fn()}
          locations={[]}
          onTabChange={setTab}
          registerCloseDialogs={register}
          {...shellProps}
        />
      </GuideProvider>
    </AskProvider>
  );
}

function mount({ width = 1280, ctx = {}, props = {} } = {}) {
  const viewport = installViewport(width);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(ctx));
  getAskSettings.mockResolvedValue(settings());
  const view = render(<Rig shellProps={props} />);
  return { ...view, viewport, rerenderShell: (next) => view.rerender(<Rig shellProps={{ ...props, ...next }} />) };
}

// jsdom applies no Tailwind, so BOTH GlanceLabel spans count: the tab's name is "At a GlanceGlance" here.
const tab = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });
const door = () => screen.queryByTestId('window-first-guide');
const guideDock = () => screen.queryByTestId('guide-dock');
const askDock = () => screen.queryByTestId('ask-dock');
const frameSrc = () => screen.getByTestId('guide-frame').getAttribute('src');
const modalCount = () => document.querySelectorAll('[aria-modal="true"]').length;
const page = (name) => `${GUIDE_ORIGIN}/guide/${name}.html?embed=1`;

// The lazy chunks (the dock, the popup) are fetched on first use; the first test to open one pays for it.
const SLOW = { timeout: 15000 };

async function openAskDock() {
  fireEvent.click(await screen.findByTestId('ask-field', {}, SLOW));
  return screen.findByTestId('ask-dock', {}, SLOW);
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetViewport();
});

describe('the masthead door on desktop and tablet', () => {
  it.each([
    ['At a Glance', 'at-a-glance'],
    ['Coming up', 'coming-up'],
    ['Map', 'map'],
  ])('on %s it opens that tab\'s page in the docked column', async (name, guidePage) => {
    mount({ props: { mapPane: MAP_PANE } });
    await screen.findByTestId('ask-field');
    fireEvent.click(tab(name));
    expect(door()).toHaveAccessibleName('Guide for this tab');
    fireEvent.click(door());
    expect(frameSrc()).toBe(page(guidePage));
    expect(guideDock()).toHaveAttribute('data-guide-surface');
    expect(guideDock().tagName).toBe('ASIDE');
    expect(modalCount()).toBe(0);
  });

  it('sits between the cog and Sign out, styled as the cog', async () => {
    mount();
    await screen.findByTestId('ask-field');
    const cog = screen.getByTestId('window-first-settings');
    const signOut = screen.getByTestId('window-first-signout');
    expect(cog.nextElementSibling).toBe(door());
    expect(door().nextElementSibling).toBe(signOut);
    expect(door().className).toBe(cog.className);
  });

  it('is a toggle: aria-expanded follows it, aria-controls names the docked column, a second press closes', async () => {
    mount();
    await screen.findByTestId('ask-field');
    expect(door()).toHaveAttribute('aria-expanded', 'false');
    expect(door()).not.toHaveAttribute('aria-controls');
    fireEvent.click(door());
    expect(door()).toHaveAttribute('aria-expanded', 'true');
    expect(door()).toHaveAttribute('aria-controls', guideDock().id);
    fireEvent.click(door());
    expect(guideDock()).toBeNull();
    expect(door()).toHaveAttribute('aria-expanded', 'false');
  });

  it('is absent on Operations, and entering it closes the docked guide', async () => {
    mount({ props: { operationsPane: OPERATIONS_PANE } });
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(guideDock()).not.toBeNull();
    fireEvent.click(tab('Operations'));
    expect(door()).toBeNull();
    expect(guideDock()).toBeNull();
  });

  it('turns the shell root into the docked row, as the Ask dock does', async () => {
    mount();
    await screen.findByTestId('ask-field');
    expect(screen.getByTestId('window-first-shell')).not.toHaveClass('wf-shell--docked');
    fireEvent.click(door());
    expect(screen.getByTestId('window-first-shell')).toHaveClass('wf-shell--docked');
    expect(guideDock().parentElement).toBe(screen.getByTestId('window-first-shell'));
    expect(guideDock()).toHaveAttribute('data-band', 'wide');
    fireEvent.click(door());
    expect(screen.getByTestId('window-first-shell')).not.toHaveClass('wf-shell--docked');
  });

  it('follows the reader across tabs when it is showing the old tab\'s page', async () => {
    mount({ props: { mapPane: MAP_PANE } });
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(frameSrc()).toBe(page('at-a-glance'));
    fireEvent.click(tab('Coming up'));
    await waitFor(() => expect(frameSrc()).toBe(page('coming-up')));
    fireEvent.click(tab('Map'));
    await waitFor(() => expect(frameSrc()).toBe(page('map')));
  });

  it('at tablet width opens the sheet, not the column, and names no controlled element', async () => {
    mount({ width: 800 });
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(guideDock()).toBeNull();
    expect(screen.getByRole('dialog', { name: 'PhotoCast guide' })).toBe(screen.getByTestId('bottom-sheet'));
    expect(door()).toHaveAttribute('aria-expanded', 'true');
    expect(door()).not.toHaveAttribute('aria-controls');
  });
});

describe('review round 1', () => {
  it('a docked guide following the reader to another tab reloads the frame WITHOUT taking focus (arrow keys keep working)', async () => {
    mount({ props: { mapPane: MAP_PANE } });
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(screen.getByTestId('guide-close')).toHaveFocus();
    const plan = tab('At a Glance');
    plan.focus();
    fireEvent.keyDown(plan, { key: 'ArrowRight' });
    await waitFor(() => expect(frameSrc()).toBe(page('coming-up')));
    expect(tab('Coming up')).toHaveFocus();
    expect(screen.getByTestId('guide-close')).not.toHaveFocus();
    fireEvent.keyDown(tab('Coming up'), { key: 'ArrowRight' });
    await waitFor(() => expect(frameSrc()).toBe(page('map')));
    expect(tab('Map')).toHaveFocus();
  });

  it('a click that left focus on <body> (Safari) still returns focus to the masthead ? on close', async () => {
    mount();
    await screen.findByTestId('ask-field');
    document.activeElement?.blur();
    fireEvent.click(door());
    expect(guideDock()).not.toBeNull();
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(door()).toHaveFocus();
  });

  it('the phone menu\'s Guide row returns focus to the ⋯ trigger', async () => {
    mount({ width: 390 });
    await screen.findByTestId('ask-bar');
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('masthead-menu-guide'));
    document.activeElement?.blur();
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    await waitFor(() => expect(screen.getByTestId('masthead-menu-button')).toHaveFocus());
  });

  it('the popup\'s ArrowLeft/Right stepping stands down under a stacked guide and returns after', async () => {
    const base = matrixCtx();
    const second = { ...base.windowCards[0], key: '2026-10-06:SUNRISE', date: '2026-10-06', targetType: 'SUNRISE' };
    mount({ ctx: { ...base, windowCards: [base.windowCards[0], second] } });
    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('window-sheet', {}, SLOW);
    // Stepping is live with nothing over the popup: the key is consumed.
    expect(fireEvent.keyDown(document.body, { key: 'ArrowRight' })).toBe(false);
    fireEvent.click(door());
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    expect(fireEvent.keyDown(document.body, { key: 'ArrowRight' })).toBe(true);
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(fireEvent.keyDown(document.body, { key: 'ArrowRight' })).toBe(false);
  });
});

describe('the phone', () => {
  it('has no masthead `?`; the ⋯ menu carries a Guide row that closes the menu and opens the sheet', async () => {
    mount({ width: 390 });
    await screen.findByTestId('ask-bar');
    expect(door()).toBeNull();
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('masthead-menu-guide'));
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(screen.getByRole('dialog', { name: 'PhotoCast guide' })).toBe(screen.getByTestId('bottom-sheet'));
    expect(frameSrc()).toBe(page('at-a-glance'));
  });

  it('the Guide row follows the tab, and is absent on Operations', async () => {
    mount({ width: 390, props: { mapPane: MAP_PANE, operationsPane: OPERATIONS_PANE } });
    await screen.findByTestId('ask-bar');
    fireEvent.click(tab('Map'));
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    fireEvent.click(screen.getByTestId('masthead-menu-guide'));
    expect(frameSrc()).toBe(page('map'));
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    fireEvent.click(tab('Operations'));
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    expect(screen.queryByTestId('masthead-menu-guide')).toBeNull();
  });

  it('leaves the existing rows in order, with Guide before Sign out', async () => {
    mount({ width: 390 });
    await screen.findByTestId('ask-bar');
    fireEvent.click(screen.getByTestId('masthead-menu-button'));
    const rows = Array.from(screen.getByTestId('masthead-menu').querySelectorAll('.wf-mast-menu-row'))
      .map((row) => row.textContent);
    expect(rows).toEqual(['Settings', 'Change home postcode', 'Guide', 'Sign out']);
  });
});

describe('the docked guide and the Ask dock share one slot', () => {
  it('opening the guide hides an open dock, and closing the guide brings it back', async () => {
    mount();
    await openAskDock();
    fireEvent.click(door());
    expect(askDock()).toBeNull();
    expect(guideDock()).not.toBeNull();
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(guideDock()).toBeNull();
    expect(askDock()).not.toBeNull();
  });

  it('restores the dock WITHOUT stealing focus from the door that opened the guide', async () => {
    mount();
    await openAskDock();
    door().focus();
    fireEvent.click(door());
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(askDock()).not.toBeNull();
    expect(door()).toHaveFocus();
    expect(screen.getByTestId('ask-input')).not.toHaveFocus();
  });

  it('a dock that was not open stays closed when the guide closes', async () => {
    mount();
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(askDock()).toBeNull();
  });

  it('opening the dock closes the guide, and focus goes to the dock\'s field', async () => {
    mount();
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(guideDock()).not.toBeNull();
    fireEvent.click(screen.getByTestId('ask-field'));
    expect(guideDock()).toBeNull();
    expect(await screen.findByTestId('ask-dock')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('ask-input')).toHaveFocus());
  });

  it('the dock\'s own `?` swaps it for the guide; closing the guide then focuses the restored dock\'s field', async () => {
    mount();
    await openAskDock();
    fireEvent.click(screen.getByTestId('ask-dock-guide'));
    expect(askDock()).toBeNull();
    expect(frameSrc()).toBe(page('ask-photocast'));
    fireEvent.click(screen.getByTestId('guide-close'));
    expect(askDock()).not.toBeNull();
    // The door lived in the dock and went with it, so there is nothing to return to: the field it is.
    await waitFor(() => expect(screen.getByTestId('ask-input')).toHaveFocus());
  });

  it('a dock reopened later by the reader focuses its field as ever (the quiet restore is one-shot)', async () => {
    mount();
    await openAskDock();
    door().focus();
    fireEvent.click(door());
    fireEvent.click(screen.getByTestId('guide-close'));
    fireEvent.click(screen.getByTestId('ask-dock-close'));
    expect(askDock()).toBeNull();
    fireEvent.click(screen.getByTestId('ask-field'));
    await screen.findByTestId('ask-dock');
    await waitFor(() => expect(screen.getByTestId('ask-input')).toHaveFocus());
  });

  it('the guide is inert, never the page, under a shell dialog', async () => {
    const { rerenderShell } = mount();
    await screen.findByTestId('ask-field');
    fireEvent.click(door());
    expect(guideDock()).not.toHaveAttribute('inert');
    rerenderShell({ settingsOpen: true });
    expect(guideDock()).toHaveAttribute('inert');
  });
});

describe('stacked over the shell\'s own dialogs', () => {
  const openPopup = async () => {
    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    return screen.findByTestId('window-sheet', {}, SLOW);
  };

  it('over the window popup it is a dialog, and the popup is told it is stacked: inert, no aria-modal', async () => {
    mount({ ctx: matrixCtx() });
    const popup = await openPopup();
    expect(popup).toHaveAttribute('aria-modal', 'true');
    fireEvent.click(door());
    expect(screen.getByTestId('guide-dialog')).toHaveAttribute('aria-modal', 'true');
    expect(popup).not.toHaveAttribute('aria-modal');
    expect(popup).toHaveAttribute('inert');
    expect(modalCount()).toBe(1);
    expect(guideDock()).toBeNull();
  });

  it('Escape closes only the guide; the popup answers the next one', async () => {
    mount({ ctx: matrixCtx() });
    await openPopup();
    fireEvent.click(door());
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('window-sheet')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByTestId('window-sheet')).not.toHaveAttribute('inert');
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
  });

  it('over search it is a dialog, and search is stacked and withholds its Escape', async () => {
    mount({ ctx: matrixCtx() });
    await screen.findByTestId('wf-heat-strip');
    fireEvent.click(screen.getByTestId('window-first-search'));
    const search = await screen.findByTestId('plan-search', {}, SLOW);
    fireEvent.click(door());
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    expect(search).toHaveAttribute('inert');
    expect(search).not.toHaveAttribute('aria-modal');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(screen.getByTestId('plan-search')).toBeInTheDocument();
  });

  it('over the pick dialog (itself stacked on the popup) it is the only modal: the pick goes inert too', async () => {
    const base = matrixCtx();
    const windowCards = [{
      ...base.windowCards[0],
      pick: {
        kind: 'best', regionName: 'North York Moors & Coast', headline: 'Breaking clear',
        detail: 'Low cloud clears.', locationName: 'Whitby', locationId: 1,
      },
    }];
    mount({ ctx: { ...base, windowCards } });
    await openPopup();
    fireEvent.click(screen.getByTestId('window-sheet-pick'));
    const pick = screen.getByTestId('window-pick-dialog');
    expect(pick).toHaveAttribute('aria-modal', 'true');
    fireEvent.click(door());
    expect(screen.getByTestId('guide-dialog')).toBeInTheDocument();
    expect(pick).not.toHaveAttribute('aria-modal');
    expect(pick).toHaveAttribute('inert');
    expect(modalCount()).toBe(1);
    // One Escape takes the guide, the next the pick dialog, the third the popup: one layer per press.
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('guide-dialog')).toBeNull();
    expect(screen.getByTestId('window-pick-dialog')).toHaveAttribute('aria-modal', 'true');
  });
});

describe('the Ask surfaces\' own `?`', () => {
  it('the dock header carries one before the ✕, and it opens the Ask page', async () => {
    mount();
    await openAskDock();
    const help = screen.getByTestId('ask-dock-guide');
    expect(help).toHaveAccessibleName('Guide for Ask PhotoCast');
    expect(help.nextElementSibling).toBe(screen.getByTestId('ask-dock-close'));
    fireEvent.click(help);
    expect(frameSrc()).toBe(page('ask-photocast'));
  });

  it('the tablet\'s Ask sheet carries one, which opens the guide as a second sheet; Escape closes only the guide', async () => {
    mount({ width: 800 });
    fireEvent.click(await screen.findByTestId('ask-field'));
    const askSheet = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    fireEvent.click(screen.getByTestId('ask-sheet-guide'));
    expect(screen.getByRole('dialog', { name: 'PhotoCast guide' })).toBeInTheDocument();
    expect(frameSrc()).toBe(page('ask-photocast'));
    // The guide sheet is the later one in the document, so BottomSheet's last-dialog rule answers for it.
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'PhotoCast guide' })).toBeNull();
    expect(askSheet.isConnected).toBe(true);
  });

  it('is on the phone sheet too', async () => {
    mount({ width: 390 });
    fireEvent.click(await screen.findByTestId('ask-bar'));
    await screen.findByRole('dialog', { name: 'Ask PhotoCast' });
    expect(screen.getByTestId('ask-sheet-guide')).toHaveAccessibleName('Guide for Ask PhotoCast');
  });
});
