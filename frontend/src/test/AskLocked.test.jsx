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
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import { ASK_UPSELL_BODY, ASK_UPSELL_HEADING } from '../components/ask/AskUpsell.jsx';
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
 * <p>The phone Map peek's entry (U2) is the same live door: its section draws the upsell inline.
 *
 * <p>One block per band: the phone bar, the phone Map peek, the tablet field, the desktop and wide field with
 * the `/` key. The absence of a dock or sheet is asserted only after the trigger has RENDERED and the upsell
 * has been opened (the lazy-absence lesson), and the stylesheet's half (the dims) is read from `index.css`
 * and composed.
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
    expect(bar).toHaveTextContent(/^Ask PhotoCast…Pro/);
    // The pill stays a DIRECT child, between the prompt and the arrow: the stylesheet matches `.wf-ask-bar > .wf-lens-pro`.
    expect(Array.from(bar.children).map((c) => c.className.split(' ')[0]))
      .toEqual(['wf-ask-bar-q', 'wf-lens-pro', 'wf-ask-bar-go']);
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
  // A stateful stand-in for MapView's gate: `mode` is `expanded` while the section is open.
  let ctxAsk;
  function Peek() {
    const [open, setOpen] = React.useState(false);
    const ref = React.useRef(null);
    ctxAsk = useAsk();
    return (
      <>
        <MapPeekAsk
          mode={open ? 'expanded' : 'collapsed'}
          onOpen={() => setOpen(true)}
          onClose={() => setOpen(false)}
          onOpenLayers={vi.fn()}
          entryRef={ref}
          doorRef={{ current: null }}
          layersRef={{ current: null }}
        />
        <button type="button" data-testid="outside">outside</button>
      </>
    );
  }
  const mountPeek = async () => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(shellCtx(LITE));
    getAskSettings.mockResolvedValue(settings());
    await act(async () => { render(<AskProvider><Peek /></AskProvider>); });
  };
  const openSection = async () => {
    fireEvent.click(await screen.findByTestId('wf-map-peek-ask-entry'));
    await act(async () => {});
  };

  it('is a live button holding the prompt, the pill and the arrow, with no lock wrapper', async () => {
    await mountPeek();

    const entry = await screen.findByTestId('wf-map-peek-ask-entry');
    expect(entry).toBeEnabled();
    expect(entry.closest('.wf-lens-locked')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-ask-locked')).toBeNull();
    expect(Array.from(entry.children).map((c) => c.className.split(' ')[0]))
      .toEqual(['wf-map-peek-ask-q', 'wf-lens-pro', 'wf-map-peek-ask-go']);
    expect(entry).toHaveAttribute('aria-expanded', 'false');
    expect(entry).toHaveAccessibleName(/\bPro$/);
    expect(entry.querySelector('a')).toBeNull();
  });

  it('opens the inline upsell: no field, no textbox, no conversation, no request, no old test id, no link', async () => {
    await mountPeek();
    await openSection();

    const inline = await screen.findByTestId('wf-map-peek-ask-upsell-inline');
    expect(inline).toHaveTextContent(ASK_UPSELL_HEADING);
    expect(inline).toHaveTextContent(ASK_UPSELL_BODY);
    expect(inline.querySelector('a')).toBeNull();
    expect(inline.textContent).not.toMatch(/upgrade/i);
    expect(screen.getByTestId('wf-map-peek-ask-upsell-pill')).toHaveTextContent('Pro');
    expect(screen.queryByTestId('wf-map-peek-ask-field')).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-ask-x')).toBeNull();
    expect(screen.queryByTestId('ask-conversation')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-ask-locked')).toBeNull();
    expectNoAskRequest();
  });

  it('moves focus to the heading on open', async () => {
    await mountPeek();
    await openSection();

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-upsell-heading'));
  });

  it('Not now closes and returns focus to the entry, never <body> (the park() before onClose)', async () => {
    await mountPeek();
    await openSection();

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-upsell-dismiss'));
    await act(async () => {});

    expect(screen.queryByTestId('wf-map-peek-ask-upsell-inline')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
    expect(ctxAsk.availability).toBe('locked');
    expectNoAskRequest();
  });

  it('Escape in the section parks focus so the entry gets it once the section closes', async () => {
    await mountPeek();
    await openSection();

    fireEvent.keyDown(screen.getByTestId('wf-map-peek-ask-upsell-heading'), { key: 'Escape' });
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
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
