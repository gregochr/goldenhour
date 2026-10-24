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
 * Ask PhotoCast is Pro and Admin only: to a LITE reader every entry is LOCKED, with exactly the drive
 * filter's treatment (`WindowFirstLensBar.test.jsx`'s locked cases are the model) — the wrapper takes
 * `wf-lens-locked`, the control is really `disabled`, a `wf-lens-pro` pill is a sibling OUTSIDE the grey,
 * there are no upsell words and no link — and nothing opens and no `/api/ask` request is ever made.
 *
 * <p>One block per band: the phone bar, the phone Map peek, the tablet field, the desktop and wide field with
 * the `/` key. The absence of a dock or dialog is asserted only after the locked control has RENDERED (the
 * lazy-absence lesson), and the stylesheet's half (no double dim) is read from `index.css` and composed.
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

// The stylesheet's disabled dims and the lock, composed: jsdom loads no stylesheet, so the rules the
// lock depends on are lifted out of `index.css` (source order kept) into a <style> and asked.
const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
const RULES = [
  '.wf-lens-locked',
  '.wf-ask-bar:disabled',
  '.wf-ask-bar[data-locked]:disabled',
  '.wf-askf:disabled',
  '.wf-lens-locked .wf-askf:disabled',
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
  it('is locked: disabled, the lock on an INNER span, the Pro pill beside it, no words, no link', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expectLockedShape(bar, screen.getByTestId('ask-bar-locked'), screen.getByTestId('ask-bar-upsell'), { wraps: false });
    expect(screen.getByTestId('ask-bar-upsell').closest('[data-testid="ask-bar"]')).toBe(bar);
    expect(bar).not.toHaveAttribute('aria-haspopup');
    expect(bar).not.toHaveAttribute('aria-expanded');
    expect(bar.querySelector('a')).toBeNull();
    expect(bar).toHaveTextContent(/Ask/);
  });

  it('keeps its own box opaque and hit-testable: the bar is not inside .wf-lens-locked', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expect(bar.closest('.wf-lens-locked')).toBeNull();
    expect(bar.className).toContain('wf-ask-bar');
    // The 45% grey is on the inner span, which is inside the bar, never around it.
    expect(bar.contains(screen.getByTestId('ask-bar-locked'))).toBe(true);
  });

  it('opens nothing and makes no request when pressed', async () => {
    renderLite(390);

    fireEvent.click(await screen.findByTestId('ask-bar'));
    await act(async () => {});

    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    expectNoAskRequest();
  });

  it('reads at the drive row\'s .45, not .45 composed with the disabled dim', async () => {
    renderLite(390);

    const bar = await screen.findByTestId('ask-bar');
    expect(getComputedStyle(bar).opacity).toBe('1');
    expect(effectiveOpacity(screen.getByTestId('ask-bar-locked'))).toBeCloseTo(0.45, 5);
    expect(effectiveOpacity(screen.getByTestId('ask-bar-upsell'))).toBe(1);
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
  it('is locked: disabled, wrapped in .wf-lens-locked, the pill outside it, no popup claimed', async () => {
    renderLite(800);

    const field = await screen.findByTestId('ask-field');
    expectLockedShape(field, screen.getByTestId('ask-field-locked'), screen.getByTestId('ask-field-upsell'));
    expect(field).not.toHaveAttribute('aria-haspopup');
    expect(field).not.toHaveAttribute('aria-expanded');
  });

  it('opens no sheet and makes no request when pressed', async () => {
    renderLite(800);

    fireEvent.click(await screen.findByTestId('ask-field'));
    await act(async () => {});

    expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
    expectNoAskRequest();
  });

  it('is drawn on the Map tab too, locked', async () => {
    renderLite(800, { props: { mapPane: MAP_PANE } });

    expect(await screen.findByTestId('ask-field-upsell')).toBeInTheDocument();
  });

  it('reads at .45 with no double dim', async () => {
    renderLite(800);

    const field = await screen.findByTestId('ask-field');
    expect(effectiveOpacity(field)).toBeCloseTo(0.45, 5);
    expect(effectiveOpacity(screen.getByTestId('ask-field-upsell'))).toBe(1);
  });
});

describe.each([[1280, 'desktop'], [1400, 'wide']])('the %i px %s field and the / key', (width) => {
  it('is locked, and advertises neither the key nor a popup nor an expansion', async () => {
    renderLite(width);

    const field = await screen.findByTestId('ask-field');
    expectLockedShape(field, screen.getByTestId('ask-field-locked'), screen.getByTestId('ask-field-upsell'));
    expect(field).not.toHaveAttribute('aria-keyshortcuts');
    expect(field).not.toHaveAttribute('aria-haspopup');
    expect(field).not.toHaveAttribute('aria-expanded');
    expect(field).not.toHaveAttribute('aria-controls');
    expect(field.querySelector('kbd')).toBeNull();
  });

  it('opens no dock and makes no request when pressed', async () => {
    renderLite(width);

    fireEvent.click(await screen.findByTestId('ask-field'));
    await act(async () => {});

    expect(screen.queryByTestId('ask-dock')).toBeNull();
    expectNoAskRequest();
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
    expect(screen.queryByTestId('ask-dock')).toBeNull();
    expectNoAskRequest();
  });

  it('reads at .45 with no double dim', async () => {
    renderLite(width);

    const field = await screen.findByTestId('ask-field');
    expect(effectiveOpacity(field)).toBeCloseTo(0.45, 5);
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
