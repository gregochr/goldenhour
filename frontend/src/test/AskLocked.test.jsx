import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { AskProvider } from '../context/AskContext.jsx';
import MapPeekAsk from '../components/map/MapPeekAsk.jsx';
import { resetViewport } from './askViewport.js';
import { MAP_PANE, renderAskShell, shellCtx } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings,
} from './askFixtures.js';

/**
 * Ask PhotoCast is Pro and Admin only: to a LITE reader every entry is a live DOOR to a worded upsell
 * (`docs/engineering/ask-upsell-plan.md`), drawn like the PRO entry with the `wf-lens-pro` pill INSIDE the
 * button. Nothing of Ask itself is reachable: no sheet, no dock, no conversation, and no `/api/ask*` request.
 * The upsell's own behaviour (open, close paths, focus, Escape order) is `AskUpsell.test.jsx`'s; this file is
 * the lock suite: the entry's shape per band, the `/` key's refusal, and the dim a blocked trigger takes.
 *
 * <p>The phone Map peek's entry is U2's and still the older greyed lock in this series' first release; its
 * block below is unchanged.
 *
 * <p>One block per band: the phone bar, the phone Map peek, the tablet field, the desktop and wide field with
 * the `/` key. The absence of a dock or sheet is asserted only after the trigger has RENDERED and the upsell
 * has been opened (the lazy-absence lesson), and the stylesheet's half (the dims) is read from `index.css`
 * and composed.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));
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
import { ask, getAskSettings, getReady } from '../api/askApi.js';

const LITE = { isPro: false, isLiteUser: true };

/** Mounts the shell as a LITE reader at {@code width}, with the flag on. */
const renderLite = (width, options = {}) => renderAskShell({ width, ctx: LITE, ...options });

const keyPress = () => {
  const press = new KeyboardEvent('keydown', { key: '/', bubbles: true, cancelable: true });
  fireEvent(document, press);
  return press;
};

/** No `POST /api/ask` and no `GET /api/ask/ready`: the settings read (open to every role) is the only call. */
function expectNoAskRequest() {
  expect(ask).not.toHaveBeenCalled();
  expect(getReady).not.toHaveBeenCalled();
}

/** The drive filter's own shape, asserted on whichever lock wrapper and pill a band drew. */
function expectLockedShape(control, wrapper, pill, { wraps = true } = {}) {
  expect(control).toBeDisabled();
  expect(wrapper.className).toContain('wf-lens-locked');
  // The phone bar is the one exception: its lock is an INNER span, so the bar wraps the grey, not the reverse.
  expect(wrapper.contains(control)).toBe(wraps);
  expect(pill).toHaveTextContent('Pro');
  expect(pill.className).toContain('wf-lens-pro');
  // Outside the grey: inside it the pill measured 3.68:1 against AA's 4.5:1.
  expect(wrapper.contains(pill)).toBe(false);
  expect(pill.closest('a')).toBeNull();
  expect(pill.closest('button') === null || pill.closest('button') === control).toBe(true);
}

/** The upsell trigger's shape, per band: a live button, the pill inside it, a popup claimed, no `aria-label`. */
function expectUpsellTrigger(control, pill) {
  expect(control).toBeEnabled();
  expect(control.closest('.wf-lens-locked')).toBeNull();
  expect(pill).toHaveTextContent('Pro');
  expect(pill.className).toContain('wf-lens-pro');
  expect(control.contains(pill)).toBe(true);
  expect(pill.closest('a')).toBeNull();
  expect(control).toHaveAttribute('aria-haspopup', 'dialog');
  expect(control).toHaveAttribute('aria-expanded', 'false');
  expect(control).not.toHaveAttribute('aria-controls');
  expect(control).not.toHaveAttribute('aria-label');
  expect(control).not.toHaveAttribute('aria-keyshortcuts');
  expect(control.querySelector('kbd')).toBeNull();
  expect(control.querySelector('a')).toBeNull();
}

/** Asserts that nothing of Ask itself mounted: no sheet, no dock, no input, and no request at all. */
function expectNothingOfAskMounted() {
  expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
  expect(screen.queryByTestId('ask-sheet')).toBeNull();
  expect(screen.queryByTestId('ask-dock')).toBeNull();
  expect(screen.queryByRole('textbox')).toBeNull();
  expectNoAskRequest();
}

// The stylesheet's disabled dims and the lock, composed: jsdom loads no stylesheet, so the rules the
// lock depends on are lifted out of `index.css` (source order kept) into a <style> and asked.
const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const RULES = [
  '.wf-lens-locked',
  '.wf-ask-bar:disabled',
  '.wf-askf:disabled',
  '.wf-map-peek-ask-entry:disabled',
  '.wf-lens-locked .wf-map-peek-ask-entry:disabled',
];
function installLockRules() {
  const style = document.createElement('style');
  const rule = /([^{}]+)\{([^{}]*)\}/g;
  let text = '';
  let m = rule.exec(CSS);
  while (m !== null) {
    const kept = m[1].split(',').map((s) => s.trim()).filter((s) => RULES.includes(s));
    if (kept.length > 0) text += `${kept.join(',')}{${m[2]}}\n`;
    m = rule.exec(CSS);
  }
  style.textContent = text;
  document.head.appendChild(style);
  return style;
}
/** The opacity a reader sees: the product of the element's and every ancestor's computed opacity. */
function effectiveOpacity(node) {
  let product = 1;
  for (let el = node; el instanceof Element; el = el.parentElement) {
    const o = getComputedStyle(el).opacity;
    product *= o === '' ? 1 : Number(o);
  }
  return product;
}

let lockStyle;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  lockStyle = installLockRules();
});

afterEach(() => {
  cleanup();
  lockStyle.remove();
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetViewport();
});

describe('the phone bar', () => {
  it('is a live trigger: the pill inside it, a popup claimed, no grey wrapper, no inner lock span', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expectUpsellTrigger(bar, screen.getByTestId('ask-bar-upsell'));
    expect(bar).toHaveAttribute('data-locked', 'true');
    expect(screen.queryByTestId('ask-bar-locked')).toBeNull();
    expect(bar).toHaveTextContent(/Ask/);
    // The pill stays a DIRECT child, between the prompt and the arrow: the stylesheet matches `.wf-ask-bar > .wf-lens-pro`.
    expect(Array.from(bar.children).map((c) => c.className.split(' ')[0]))
      .toEqual(['wf-ask-bar-k', 'wf-ask-bar-q', 'wf-lens-pro', 'wf-ask-bar-go']);
  });

  it('is named by its content, "{prompt} Pro", with no aria-label to replace it', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expect(bar).toHaveAccessibleName(/\bPro$/);
    expect(bar).toHaveAccessibleName(new RegExp(bar.querySelector('.wf-ask-bar-q').textContent));
  });

  it('opens the upsell, not a sheet, and makes no request when pressed', async () => {
    renderLite(390);

    fireEvent.click(await screen.findByTestId('ask-bar'));
    await act(async () => {});

    expect(await screen.findByTestId('wf-ask-upsell')).toBeInTheDocument();
    expectNothingOfAskMounted();
  });

  it('keeps its own box opaque: the bar is not inside .wf-lens-locked and nothing inside it is', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expect(bar.closest('.wf-lens-locked')).toBeNull();
    expect(bar.querySelector('.wf-lens-locked')).toBeNull();
    expect(bar.className).toContain('wf-ask-bar');
  });

  it('reads at full strength, and at the PRO bar\'s .5 when settings or a card popup disable it', async () => {
    renderLite(390);
    const bar = await screen.findByTestId('ask-bar');
    expect(effectiveOpacity(bar)).toBe(1);
    cleanup();

    renderLite(390, { props: { contentDisabled: true } });
    const blocked = await screen.findByTestId('ask-bar');
    expect(blocked).toBeDisabled();
    expect(effectiveOpacity(blocked)).toBeCloseTo(0.5, 5);
  });
});

describe('the phone Map peek entry', () => {
  function Peek() {
    return (
      <AskProvider>
        <MapPeekAsk
          mode="collapsed"
          onOpen={peekOpen}
          onClose={vi.fn()}
          onOpenLayers={vi.fn()}
          entryRef={{ current: null }}
          doorRef={{ current: null }}
          layersRef={{ current: null }}
        />
      </AskProvider>
    );
  }
  let peekOpen;
  const mountPeek = async () => {
    peekOpen = vi.fn();
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(LITE));
    getAskSettings.mockResolvedValue(settings());
    await act(async () => { render(<Peek />); });
  };

  it('is locked: the entry is disabled in a wf-lens-locked wrapper and the pill is outside it', async () => {
    await mountPeek();

    const entry = await screen.findByTestId('wf-map-peek-ask-entry');
    expectLockedShape(
      entry, screen.getByTestId('wf-map-peek-ask-locked'), screen.getByTestId('wf-map-peek-ask-upsell'),
    );
    expect(entry).not.toHaveAttribute('aria-expanded');
    // The pill is the row's sibling: the wrapper takes the row's flex share.
    expect(screen.getByTestId('wf-map-peek-ask-locked').parentElement)
      .toBe(screen.getByTestId('wf-map-peek-ask-upsell').parentElement);
  });

  it('opens nothing and makes no request when pressed', async () => {
    await mountPeek();

    fireEvent.click(await screen.findByTestId('wf-map-peek-ask-entry'));
    await act(async () => {});

    expect(peekOpen).not.toHaveBeenCalled();
    expectNoAskRequest();
  });

  it('reads at .45 with no double dim', async () => {
    await mountPeek();

    const entry = await screen.findByTestId('wf-map-peek-ask-entry');
    expect(effectiveOpacity(entry)).toBeCloseTo(0.45, 5);
    expect(effectiveOpacity(screen.getByTestId('wf-map-peek-ask-upsell'))).toBe(1);
  });
});

describe('the tablet field', () => {
  it('is a live trigger: the pill inside it, a popup claimed, no wrapper, no row', async () => {
    renderLite(800);

    const field = await screen.findByTestId('ask-field');
    expectUpsellTrigger(field, screen.getByTestId('ask-field-upsell'));
    expect(field).toHaveAttribute('data-locked', 'true');
    expect(screen.queryByTestId('ask-field-locked')).toBeNull();
    expect(screen.queryByTestId('ask-field-lockrow')).toBeNull();
    // Last child: the prompt ellipsizes in front of it and the pill is never pushed out.
    expect(field.lastElementChild).toBe(screen.getByTestId('ask-field-upsell'));
  });

  it('is named by its content, "{prompt} Pro", with no aria-label to replace it', async () => {
    renderLite(800);

    const field = await screen.findByTestId('ask-field');
    expect(field).toHaveAccessibleName(/\bPro$/);
    expect(field).toHaveAccessibleName(new RegExp(field.querySelector('.wf-askf-q').textContent));
  });

  it('opens the upsell, not the sheet, and makes no request when pressed', async () => {
    renderLite(800);

    fireEvent.click(await screen.findByTestId('ask-field'));
    await act(async () => {});

    expect(await screen.findByTestId('wf-ask-upsell')).toBeInTheDocument();
    expectNothingOfAskMounted();
  });

  it('is drawn on the Map tab too, as a trigger', async () => {
    renderLite(800, { props: { mapPane: MAP_PANE } });

    expect(await screen.findByTestId('ask-field-upsell')).toBeInTheDocument();
  });

  it('reads at full strength, and at the PRO field\'s .5 when settings or a card popup disable it', async () => {
    renderLite(800);
    expect(effectiveOpacity(await screen.findByTestId('ask-field'))).toBe(1);
    cleanup();

    renderLite(800, { props: { contentDisabled: true } });
    const blocked = await screen.findByTestId('ask-field');
    expect(blocked).toBeDisabled();
    expect(effectiveOpacity(blocked)).toBeCloseTo(0.5, 5);
  });
});

describe.each([[1280, 'desktop'], [1400, 'wide']])('the %i px %s field and the / key', (width) => {
  it('is a live trigger, and advertises no key and no hint', async () => {
    renderLite(width);

    const field = await screen.findByTestId('ask-field');
    expectUpsellTrigger(field, screen.getByTestId('ask-field-upsell'));
  });

  it('opens the upsell, not the dock, and makes no request when pressed', async () => {
    renderLite(width);

    fireEvent.click(await screen.findByTestId('ask-field'));
    await act(async () => {});

    expect(await screen.findByTestId('wf-ask-upsell')).toBeInTheDocument();
    expectNothingOfAskMounted();
  });

  it('refuses / BEFORE preventDefault: the key is not swallowed and focus does not move', async () => {
    renderLite(width);
    await screen.findByTestId('ask-field-upsell');
    await act(async () => {});
    const before = document.activeElement;

    const press = keyPress();
    await act(async () => {});

    expect(press.defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(before);
    expect(screen.queryByTestId('wf-ask-upsell')).toBeNull();
    expect(screen.queryByTestId('ask-dock')).toBeNull();
    expectNoAskRequest();
  });

  it('reads at full strength, and at .5 when it is disabled', async () => {
    renderLite(width);
    expect(effectiveOpacity(await screen.findByTestId('ask-field'))).toBe(1);
    cleanup();

    renderLite(width, { props: { contentDisabled: true } });
    expect(effectiveOpacity(await screen.findByTestId('ask-field'))).toBeCloseTo(0.5, 5);
  });
});

describe('the wide field shows no / cap while locked', () => {
  it('draws no <kbd> hint at 1400 px', async () => {
    renderLite(1400);

    const field = await screen.findByTestId('ask-field');
    expect(field.querySelector('.wf-askf-kbd')).toBeNull();
  });
});

describe('where Ask draws nothing at all, for a LITE reader as for any other', () => {
  it('draws nothing while the settings read is pending: no flash of a lock on a flag-off server', async () => {
    getAskSettings.mockReturnValue(new Promise(() => {}));
    renderLite(390, { askSettings: false });
    await screen.findByTestId('window-first-shell');
    await act(async () => {});

    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(screen.queryByTestId('ask-bar-upsell')).toBeNull();
  });

  it('draws nothing when the flag is off: off still wins over locked', async () => {
    renderLite(1280, { askSettings: settings({ enabled: false }) });
    await screen.findByTestId('window-first-shell');
    await act(async () => {});

    expect(screen.queryByTestId('ask-field')).toBeNull();
    expect(screen.queryByTestId('ask-field-upsell')).toBeNull();
    expectNoAskRequest();
  });
});

describe('a PRO reader is unchanged: no lock, no pill, a live control', () => {
  it.each([[390, 'ask-bar'], [800, 'ask-field'], [1280, 'ask-field']])('at %i px the %s is live', async (width, id) => {
    renderAskShell({ width });

    const control = await screen.findByTestId(id);
    expect(control).toBeEnabled();
    expect(control.closest('.wf-lens-locked')).toBeNull();
    expect(screen.queryByText('Pro')).toBeNull();
    expect(document.querySelector('.wf-lens-pro')).toBeNull();
  });
});
