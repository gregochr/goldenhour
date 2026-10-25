import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen, within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { resetViewport } from './askViewport.js';
import { MAP_PANE, matrixCtx, renderAskShell } from './askShellHarness.jsx';
import { NOW, readyResponse, settings } from './askFixtures.js';
import {
  ASK_UPSELL_BODY, ASK_UPSELL_HEADING, AskUpsellPreview,
} from '../components/ask/AskUpsell.jsx';

/**
 * The Ask upsell a LITE reader's entries open (`docs/engineering/ask-upsell-plan.md` §3, §4.1–§4.3, §5):
 * open, the content, every close path and where focus lands, the Escape order against the Map's landing
 * card, the foreign-dialog refusals, `selectTab`, the breakpoint swaps, and the Safari null-`relatedTarget`
 * case. Per band: the phone bar, the tablet field, the desktop field and the wide field.
 *
 * <p>`AskLocked.test.jsx` is the lock suite (the trigger's shape, the `/` key, the dims). The Map landing
 * card's Escape listener is a bubble-phase `document` listener in `MapView`; here it is stood in for by a
 * `document` listener the test adds, which is exactly what `stopPropagation` has to keep the key from.
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
const BANDS = [
  [390, 'ask-bar', 'the phone bar'],
  [800, 'ask-field', 'the tablet field'],
  [1280, 'ask-field', 'the desktop field'],
  [1400, 'ask-field', 'the wide field'],
];

const renderLite = (width, options = {}) => renderAskShell({ width, ctx: LITE, ...options });
const popover = () => screen.queryByTestId('wf-ask-upsell');
const heading = () => screen.getByTestId('wf-ask-upsell-heading');
const tabNamed = (name) => screen.getByRole('tab', { name: name === 'At a Glance' ? /^At a Glance/ : name });
const press = (node) => act(async () => { fireEvent.click(node); });

/** Mounts, waits for the trigger and opens the upsell by a click. */
async function openUpsell(width, testId, options = {}) {
  const view = renderLite(width, options);
  const trigger = await screen.findByTestId(testId);
  trigger.focus();
  await press(trigger);
  await screen.findByTestId('wf-ask-upsell');
  return { ...view, trigger };
}

/** A dialog this shell does not own, outside its root: the postcode prompt's and settings' shape. */
function addForeignDialog() {
  const foreign = document.createElement('div');
  foreign.setAttribute('role', 'dialog');
  foreign.setAttribute('data-foreign-dialog', '');
  const inside = document.createElement('button');
  inside.type = 'button';
  inside.textContent = 'inside the foreign dialog';
  foreign.appendChild(inside);
  document.body.appendChild(foreign);
  return { foreign, inside };
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
  document.querySelectorAll('[data-foreign-dialog]').forEach((node) => node.remove());
  resetViewport();
});

describe.each(BANDS)('at %i px, %s', (width, testId) => {
  describe('opening', () => {
    it('draws no popover at rest, and the trigger claims a collapsed popup', async () => {
      renderLite(width);

      const trigger = await screen.findByTestId(testId);
      expect(popover()).toBeNull();
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
      expect(trigger).not.toHaveAttribute('aria-controls');
    });

    it('opens a non-modal dialog named by its heading, with focus on the heading, and the trigger controls it', async () => {
      const { trigger } = await openUpsell(width, testId);

      const pop = popover();
      expect(pop).toHaveAttribute('role', 'dialog');
      expect(pop).not.toHaveAttribute('aria-modal');
      expect(pop).toHaveAttribute('aria-labelledby', heading().id);
      expect(pop).toHaveAccessibleName(ASK_UPSELL_HEADING);
      expect(document.activeElement).toBe(heading());
      expect(trigger).toHaveAttribute('aria-expanded', 'true');
      expect(trigger).toHaveAttribute('aria-controls', pop.id);
      expect(pop.id).not.toBe('');
      // Inline, never portalled: a portalled dialog would read as "foreign" to every dialog rule.
      expect(screen.getByTestId('window-first-shell').contains(pop)).toBe(true);
    });

    it('opens by Enter and by Space on the focused trigger', async () => {
      const user = userEvent.setup();
      renderLite(width);
      const trigger = await screen.findByTestId(testId);

      trigger.focus();
      await user.keyboard('{Enter}');
      expect(popover()).toBeInTheDocument();
      await user.keyboard('{Escape}');
      expect(popover()).toBeNull();

      trigger.focus();
      await user.keyboard(' ');
      expect(popover()).toBeInTheDocument();
    });

    it('reads the exported copy, a Pro pill, a Not now button, and nothing that links or says upgrade', async () => {
      await openUpsell(width, testId);

      const pop = popover();
      expect(within(pop).getByTestId('wf-ask-upsell-heading')).toHaveTextContent(ASK_UPSELL_HEADING);
      expect(within(pop).getByTestId('wf-ask-upsell-body')).toHaveTextContent(ASK_UPSELL_BODY);
      expect(within(pop).getByTestId('wf-ask-upsell-pill')).toHaveClass('wf-lens-pro');
      expect(within(pop).getByTestId('wf-ask-upsell-pill')).toHaveTextContent('Pro');
      expect(within(pop).getByRole('button', { name: 'Not now' })).toBeInTheDocument();
      expect(pop.querySelector('a[href]')).toBeNull();
      expect(pop.textContent).not.toMatch(/upgrade/i);
      expect(pop.querySelector('[aria-hidden="true"]')).not.toBeNull();
    });

    it('mounts nothing of Ask itself and asks the server nothing, after the lazy boundary has been awaited', async () => {
      await openUpsell(width, testId);
      await act(async () => {});

      expect(screen.queryByTestId('ask-sheet')).toBeNull();
      expect(screen.queryByTestId('ask-dock')).toBeNull();
      expect(screen.queryByRole('textbox')).toBeNull();
      expect(screen.queryByRole('dialog', { name: 'Ask PhotoCast' })).toBeNull();
      expect(ask).not.toHaveBeenCalled();
      expect(getReady).not.toHaveBeenCalled();
    });
  });

  describe('closing, and where focus goes', () => {
    it('Not now closes it and returns focus to the trigger', async () => {
      const { trigger } = await openUpsell(width, testId);

      await press(screen.getByTestId('wf-ask-upsell-dismiss'));

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(trigger);
    });

    it('Escape inside the popover closes it and returns focus to the trigger', async () => {
      const { trigger } = await openUpsell(width, testId);

      await act(async () => { fireEvent.keyDown(heading(), { key: 'Escape' }); });

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(trigger);
    });

    it('the trigger pressed again closes it and keeps focus on the trigger', async () => {
      const { trigger } = await openUpsell(width, testId);

      await press(trigger);

      expect(popover()).toBeNull();
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
      expect(document.activeElement).toBe(trigger);
    });

    it('an outside press closes it, and focus goes where the press put it, not to the trigger', async () => {
      const { trigger } = await openUpsell(width, testId);
      const outside = document.createElement('button');
      document.body.appendChild(outside);

      // A real click presses, then focuses the control it landed on.
      await act(async () => {
        fireEvent.pointerDown(outside);
        outside.focus();
      });

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(outside);
      expect(document.activeElement).not.toBe(trigger);
      outside.remove();
    });

    it('a press on a control that does NOT block Ask closes it (the pointerdown listener, not a blocked let-go)', async () => {
      await openUpsell(width, testId);
      const other = tabNamed('Coming up');
      const selectedBefore = screen.getByRole('tab', { selected: true });

      await act(async () => { fireEvent.pointerDown(other); });

      expect(popover()).toBeNull();
      // Only the press happened: no click, so no tab change.
      expect(screen.getByRole('tab', { selected: true })).toBe(selectedBefore);
    });

    it('clicking its non-focusable copy keeps focus inside, so Escape still closes it and returns focus', async () => {
      const user = userEvent.setup();
      const { trigger } = await openUpsell(width, testId);

      await user.click(screen.getByTestId('wf-ask-upsell-body'));
      expect(popover()).toBeInTheDocument();
      expect(popover().contains(document.activeElement)).toBe(true);

      await user.keyboard('{Escape}');

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(trigger);
    });

    it('focus leaving THROUGH the trigger closes it and stays where it landed (Shift+Tab twice)', async () => {
      const user = userEvent.setup();
      const { trigger } = await openUpsell(width, testId);

      await user.tab({ shift: true });
      expect(document.activeElement).toBe(trigger);
      expect(popover()).toBeInTheDocument();
      await user.tab({ shift: true });

      expect(popover()).toBeNull();
      expect(document.activeElement).not.toBe(trigger);
      expect(document.activeElement).not.toBe(document.body);
      if (width >= 640) expect(document.activeElement).toBe(screen.getByRole('tab', { selected: true }));
    });

    it('a press inside the popover, or on the trigger, is not an outside press', async () => {
      const { trigger } = await openUpsell(width, testId);

      await act(async () => { fireEvent.pointerDown(heading()); });
      expect(popover()).toBeInTheDocument();
      await act(async () => { fireEvent.pointerDown(trigger); });
      expect(popover()).toBeInTheDocument();
    });

    it('focus moving to something that is not a door closes it and leaves focus where the reader put it', async () => {
      await openUpsell(width, testId);
      const target = tabNamed('Coming up');

      await act(async () => { target.focus(); });

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(target);
    });

    it('Shift+Tab back onto the trigger keeps it open, and Escape THERE closes it, focus staying put', async () => {
      const user = userEvent.setup();
      const { trigger } = await openUpsell(width, testId);
      const doc = vi.fn();
      document.addEventListener('keydown', doc);

      await user.tab({ shift: true });
      expect(document.activeElement).toBe(trigger);
      expect(popover()).toBeInTheDocument();

      await user.keyboard('{Escape}');

      expect(popover()).toBeNull();
      expect(document.activeElement).toBe(trigger);
      // The Map landing card's `document` listener never sees this Escape (the stopPropagation).
      expect(doc.mock.calls.filter(([e]) => e.key === 'Escape')).toHaveLength(0);
      document.removeEventListener('keydown', doc);
    });

    it('a blur with a null relatedTarget does not close it (Safari does not focus a clicked button)', async () => {
      const { trigger } = await openUpsell(width, testId);

      await act(async () => { fireEvent.blur(heading(), { relatedTarget: null }); });
      expect(popover()).toBeInTheDocument();

      // ...so the trigger's click that follows toggles it shut exactly once.
      await press(trigger);
      expect(popover()).toBeNull();
    });
  });

  describe('Escape stops at the upsell', () => {
    it('with focus on the heading: the document-level (Map landing card) listener never sees the key', async () => {
      await openUpsell(width, testId);
      const doc = vi.fn();
      document.addEventListener('keydown', doc);

      await act(async () => { fireEvent.keyDown(heading(), { key: 'Escape' }); });

      expect(popover()).toBeNull();
      expect(doc).not.toHaveBeenCalled();
      document.removeEventListener('keydown', doc);
    });

    it('with no upsell open, Escape on the trigger is left alone (it reaches the document)', async () => {
      renderLite(width);
      const trigger = await screen.findByTestId(testId);
      const doc = vi.fn();
      document.addEventListener('keydown', doc);

      await act(async () => { fireEvent.keyDown(trigger, { key: 'Escape' }); });

      expect(doc).toHaveBeenCalledTimes(1);
      document.removeEventListener('keydown', doc);
    });
  });

  describe('over a dialog this shell does not own', () => {
    it('opening is refused', async () => {
      renderLite(width);
      const trigger = await screen.findByTestId(testId);
      addForeignDialog();

      await press(trigger);

      expect(popover()).toBeNull();
    });

    it('Escape does nothing, a press inside that dialog does not close it, and the trigger still can', async () => {
      const { trigger } = await openUpsell(width, testId);
      const { inside } = addForeignDialog();

      await act(async () => { fireEvent.keyDown(heading(), { key: 'Escape' }); });
      expect(popover()).toBeInTheDocument();
      await act(async () => { fireEvent.keyDown(trigger, { key: 'Escape' }); });
      expect(popover()).toBeInTheDocument();
      await act(async () => { fireEvent.pointerDown(inside); });
      expect(popover()).toBeInTheDocument();

      await press(trigger);
      expect(popover()).toBeNull();
    });
  });

  describe('letting go', () => {
    it('the backend going down (a block that is not a dialog) closes it and does not strand focus on <body>', async () => {
      const { rerenderShell } = await openUpsell(width, testId);
      expect(document.activeElement).toBe(heading());

      rerenderShell({ contentDisabled: true });
      await act(async () => {});

      expect(popover()).toBeNull();
      expect(document.activeElement).not.toBe(document.body);
      // The trigger is disabled now, so the tab in force takes it.
      expect(document.activeElement).toBe(screen.getByRole('tab', { selected: true }));
    });

    it('a shell dialog or settings opening closes it', async () => {
      const { rerenderShell } = await openUpsell(width, testId);

      rerenderShell({ settingsOpen: true });
      await act(async () => {});
      expect(popover()).toBeNull();

      // ...and it does not come back by itself when settings closes.
      rerenderShell({ settingsOpen: false });
      await act(async () => {});
      expect(popover()).toBeNull();
    });
  });
});

describe('the tab buttons close it (selectTab), from every tab the entry is drawn on', () => {
  it.each([
    [800, 'At a Glance', 'Coming up'],
    [800, 'Coming up', 'Map'],
    [800, 'Map', 'At a Glance'],
    [1280, 'At a Glance', 'Map'],
    [1280, 'Coming up', 'At a Glance'],
    [1280, 'Map', 'Coming up'],
    [390, 'At a Glance', 'Coming up'],
    [390, 'Coming up', 'At a Glance'],
  ])('at %i px, from %s to %s', async (width, from, to) => {
    const testId = width < 640 ? 'ask-bar' : 'ask-field';
    renderLite(width, { props: { mapPane: MAP_PANE } });
    await screen.findByTestId(testId);
    await press(tabNamed(from));
    const trigger = await screen.findByTestId(testId);
    await press(trigger);
    expect(popover()).toBeInTheDocument();

    await press(tabNamed(to));

    expect(popover()).toBeNull();
  });
});

describe('a breakpoint change swaps the entry, and the popover is let go with it', () => {
  it('field -> dock field (crossing 1024px): closed, and focus is not stranded on <body>', async () => {
    const { viewport } = await openUpsell(800, 'ask-field');
    expect(document.activeElement).toBe(heading());

    act(() => viewport.resize(1280));
    await act(async () => {});

    expect(popover()).toBeNull();
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByTestId('ask-field'));
    expect(screen.getByTestId('ask-field')).toHaveAttribute('aria-expanded', 'false');
  });

  it('field -> bar (crossing 640px): closed, and focus goes to the new bar', async () => {
    const { viewport } = await openUpsell(800, 'ask-field');

    act(() => viewport.resize(390));
    await act(async () => {});

    expect(popover()).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('ask-bar'));
  });

  it('bar -> field (crossing 640px): closed, and it does not reopen on the new entry', async () => {
    const { viewport } = await openUpsell(390, 'ask-bar');

    act(() => viewport.resize(800));
    await act(async () => {});

    expect(popover()).toBeNull();
    expect(screen.getByTestId('ask-field')).toHaveAttribute('aria-expanded', 'false');
    expect(document.activeElement).toBe(screen.getByTestId('ask-field'));
  });
});

describe('the preview is a static sketch', () => {
  it('takes no props and holds no text', () => {
    expect(AskUpsellPreview.length).toBe(0);
  });

  it('draws identical markup for two different briefings', async () => {
    await openUpsell(800, 'ask-field', { ctx: { ...LITE, homePlace: 'Newcastle' } });
    const first = screen.getByTestId('wf-ask-upsell-preview').outerHTML;
    cleanup();
    await openUpsell(800, 'ask-field', { ctx: { ...LITE, homePlace: 'Elsewhere', windowCards: [] } });

    expect(screen.getByTestId('wf-ask-upsell-preview').outerHTML).toBe(first);
    expect(first).not.toMatch(/<text|<title/);
  });
});

describe('a press on a Plan card while the upsell is open', () => {
  it.each([[390, 'ask-bar'], [1280, 'ask-field']])('at %i px closes it AND opens the card popup, on one click', async (width, testId) => {
    renderLite(width, { ctx: { ...LITE, ...matrixCtx() } });
    const trigger = await screen.findByTestId(testId);
    await screen.findByTestId('wf-heat-strip');
    await press(trigger);
    expect(popover()).toBeInTheDocument();
    const card = screen.getAllByTestId('wf-heat-card')[0];

    await act(async () => {
      fireEvent.pointerDown(card);
      fireEvent.click(card);
    });

    expect(popover()).toBeNull();
    expect(await screen.findByTestId('window-sheet')).toBeInTheDocument();
  });
});
