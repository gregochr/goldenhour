/**
 * `components/map/WindowControl.jsx` — the EARLIER group and the older-day fetch
 * (outcome-rating-plan.md §2 #2, §3 O3b).
 *
 * Pinned here: the header and the Load row (their text, order, accessible name and the press that
 * closes the menu and moves focus to the pill BEFORE the fetch starts); the past sub-headers and
 * rows; today appearing in BOTH groups; `‹` and `←` at the oldest window fetching rather than
 * stepping, with `‹` naming the day; the loading pill with both steppers and the Load row disabled;
 * the O4b seam (`ratedCounts`); and the stylesheet rules, by reading the real `index.css` (jsdom
 * applies none).
 */
import React from 'react';
import {
  describe, it, expect, vi, afterEach,
} from 'vitest';
import {
  render, screen, fireEvent, within, cleanup,
} from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import WindowControl from '../components/map/WindowControl.jsx';

afterEach(() => cleanup());

function row(overrides = {}) {
  return {
    id: 'solar:2026-10-16:SUNSET',
    kind: 'solar',
    eventType: 'SUNSET',
    date: '2026-10-16',
    label: 'Today sunset',
    dayLabel: 'Today',
    time: '17:42',
    bestRating: 4,
    scored: true,
    served: true,
    badges: [],
    ...overrides,
  };
}

const past = (date, eventType, dayLabel, time) => row({
  id: `solar:${date}:${eventType}`,
  eventType,
  date,
  past: true,
  served: false,
  scored: false,
  bestRating: null,
  dayLabel,
  label: `${dayLabel} ${eventType.toLowerCase()}`,
  time,
});

const Y_RISE = past('2026-10-15', 'SUNRISE', 'Yesterday', '07:20');
const Y_SET = past('2026-10-15', 'SUNSET', 'Yesterday', '17:44');
const T_RISE = past('2026-10-16', 'SUNRISE', 'Today', '07:22');
const T_SET = row();
const TM_RISE = row({
  id: 'solar:2026-10-17:SUNRISE', eventType: 'SUNRISE', date: '2026-10-17', dayLabel: 'Tomorrow', label: 'Tomorrow sunrise', time: '07:24',
});
const EVENTS = [Y_RISE, Y_SET, T_RISE, T_SET, TM_RISE];

function renderControl(overrides = {}) {
  const onSelect = vi.fn();
  const onLoadOlderDay = vi.fn();
  const utils = render(
    <WindowControl
      events={EVENTS}
      activeIndex={3}
      onSelect={onSelect}
      canLoadOlder
      olderDayLabel="Wed 14"
      onLoadOlderDay={onLoadOlderDay}
      {...overrides}
    />,
  );
  return { onSelect, onLoadOlderDay, ...utils };
}

const openMenu = () => fireEvent.click(screen.getByTestId('wf-win-pill'));

describe('the EARLIER header', () => {
  it('reads EARLIER on the left and "rate how it went" on the right, above the windows', () => {
    renderControl();
    openMenu();
    const header = screen.getByTestId('wf-win-earlier');
    expect(header).toHaveTextContent('EARLIER');
    expect(within(header).getByText('rate how it went')).toHaveClass('wf-win-earlier-hint');
  });

  it('is drawn when only past rows exist (nothing left to fetch)', () => {
    renderControl({ canLoadOlder: false });
    openMenu();
    expect(screen.getByTestId('wf-win-earlier')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-win-load')).not.toBeInTheDocument();
  });

  it('is drawn when only a day to fetch exists (no past row yet)', () => {
    renderControl({ events: [T_SET, TM_RISE], activeIndex: 0 });
    openMenu();
    expect(screen.getByTestId('wf-win-earlier')).toBeInTheDocument();
    expect(screen.getByTestId('wf-win-load')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-win-sub')).not.toBeInTheDocument();
  });

  it('is absent when there is neither a past row nor a day to fetch', () => {
    renderControl({ events: [T_SET, TM_RISE], activeIndex: 0, canLoadOlder: false });
    openMenu();
    expect(screen.queryByTestId('wf-win-earlier')).not.toBeInTheDocument();
  });
});

describe('the Load row', () => {
  it('names the day it fetches, twice, with the prototype\'s sub-line', () => {
    renderControl();
    openMenu();
    const load = screen.getByTestId('wf-win-load');
    expect(load).toHaveTextContent('Load Wed 14');
    expect(load).toHaveTextContent('or press ‹ past the oldest window · fetches Wed 14');
  });

  it('has an accessible name that says what it fetches (the arrow glyph is decoration)', () => {
    renderControl();
    openMenu();
    expect(screen.getByRole('button', {
      name: 'Load Wed 14 or press ‹ past the oldest window · fetches Wed 14',
    })).toBe(screen.getByTestId('wf-win-load'));
  });

  it('sits first under the header, before the listbox, and outside it (it chooses no window)', () => {
    renderControl();
    openMenu();
    const menu = screen.getByTestId('wf-win-menu');
    const order = [...menu.children].map((el) => el.getAttribute('data-testid'));
    expect(order.indexOf('wf-win-earlier')).toBeLessThan(order.indexOf('wf-win-load'));
    expect(order.indexOf('wf-win-load')).toBeLessThan(order.indexOf('wf-win-listbox'));
    expect(screen.getByTestId('wf-win-listbox')).not.toContainElement(screen.getByTestId('wf-win-load'));
  });

  it('is absent once nothing older can be loaded', () => {
    renderControl({ canLoadOlder: false });
    openMenu();
    expect(screen.queryByTestId('wf-win-load')).not.toBeInTheDocument();
  });

  it('is absent when no loader was handed in, even if the flag says a day exists', () => {
    renderControl({ onLoadOlderDay: null });
    openMenu();
    expect(screen.queryByTestId('wf-win-load')).not.toBeInTheDocument();
  });

  it('closes the menu, focuses the pill BEFORE the fetch starts, then fetches once', () => {
    let focusedAtFetch = null;
    const onLoadOlderDay = vi.fn(() => { focusedAtFetch = document.activeElement; });
    renderControl({ onLoadOlderDay });
    openMenu();
    const load = screen.getByTestId('wf-win-load');
    load.focus();

    fireEvent.click(load);

    expect(onLoadOlderDay).toHaveBeenCalledTimes(1);
    // The row unmounts with the menu and `useRowFocusRescue` will not rescue it, so the pill takes
    // focus in the handler — and has it already when the parent's state change begins.
    expect(focusedAtFetch).toBe(screen.getByTestId('wf-win-pill'));
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
  });

  it('does not select a window', () => {
    const { onSelect } = renderControl();
    openMenu();
    fireEvent.click(screen.getByTestId('wf-win-load'));
    expect(onSelect).not.toHaveBeenCalled();
  });
});

describe('the past days under EARLIER', () => {
  it('draws a sub-header per past day with its rows, sunrise first, then the future days', () => {
    renderControl();
    openMenu();
    const subs = screen.getAllByTestId('wf-win-sub').map((el) => el.textContent);
    expect(subs).toEqual(['Yesterday', 'Today']);
    const ids = screen.getAllByTestId('wf-win-row').map((r) => r.getAttribute('data-ev-id'));
    expect(ids).toEqual([Y_RISE.id, Y_SET.id, T_RISE.id, T_SET.id, TM_RISE.id]);
  });

  it('prints the kind chip, the time and "Not rated" on a past row — and marks it data-past', () => {
    renderControl();
    openMenu();
    const r = screen.getAllByTestId('wf-win-row')[0];
    expect(r).toHaveAttribute('data-past', 'true');
    expect(r).toHaveTextContent('Sunrise');
    expect(r).toHaveTextContent('07:20');
    expect(within(r).getByTestId('wf-win-notrated')).toHaveTextContent('Not rated');
    expect(within(r).queryByTestId('wf-win-rated')).not.toBeInTheDocument();
  });

  it('carries the day in words for a screen reader (the sub-header is outside the option)', () => {
    renderControl();
    openMenu();
    expect(screen.getAllByRole('option')[1]).toHaveAccessibleName(/Sunset\s*Yesterday\s*17:44\s*Not rated/);
  });

  it('reads "{n} rated" from ratedCounts — the O4b seam — and "Not rated" for a zero or absent count', () => {
    renderControl({ ratedCounts: new Map([[Y_SET.id, 3], [T_RISE.id, 0]]) });
    openMenu();
    const byId = (id) => screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === id);
    expect(within(byId(Y_SET.id)).getByTestId('wf-win-rated')).toHaveTextContent('3 rated');
    expect(within(byId(Y_RISE.id)).getByTestId('wf-win-notrated')).toBeInTheDocument();
    expect(within(byId(T_RISE.id)).getByTestId('wf-win-notrated')).toBeInTheDocument();
  });

  it('marks the active past row selected and selecting one reports it', () => {
    const { onSelect } = renderControl({ activeIndex: 1 });
    openMenu();
    const rows = screen.getAllByTestId('wf-win-row');
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(rows[0]);
    expect(onSelect).toHaveBeenCalledWith(Y_RISE);
  });

  it('draws no pick, topic or score on a past row', () => {
    renderControl({
      events: [{ ...Y_RISE, pickKind: 'best', badges: [{ type: 'KING_TIDE' }], bestRating: 5 }, T_SET],
      activeIndex: 1,
    });
    openMenu();
    const r = screen.getAllByTestId('wf-win-row')[0];
    expect(within(r).queryByTestId('wf-win-pick')).not.toBeInTheDocument();
    expect(r.textContent).not.toMatch(/best/);
  });
});

describe('today in BOTH groups (no dedupe)', () => {
  it('shows today\'s elapsed sunrise under the EARLIER sub-header and its sunset under "THU 16 · today"', () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderControl();
    openMenu();
    expect(screen.getAllByTestId('wf-win-sub').map((el) => el.textContent)).toContain('Today');
    const days = screen.getAllByTestId('wf-win-day').map((el) => el.textContent);
    expect(days).toEqual(['FRI 16 · today', 'SAT 17']);
    // Two groups with one date must not collide on a React key.
    const keyWarnings = errors.mock.calls.filter((call) => /same key/.test(String(call[0])));
    expect(keyWarnings).toEqual([]);
    errors.mockRestore();
  });

  it('adds "· today" only where the date also heads an EARLIER sub-header', () => {
    renderControl({ events: [T_SET, TM_RISE], activeIndex: 0, canLoadOlder: false });
    openMenu();
    expect(screen.getAllByTestId('wf-win-day').map((el) => el.textContent)).toEqual(['FRI 16', 'SAT 17']);
  });

  it('puts back the rule that separates the past from the future', () => {
    renderControl();
    openMenu();
    const [first, second] = screen.getAllByTestId('wf-win-day');
    expect(first).toHaveClass('wf-win-day-after-earlier');
    expect(second).not.toHaveClass('wf-win-day-after-earlier');
  });
});

describe('stepping back past the oldest window fetches', () => {
  it('turns ‹ on the oldest window into the fetch, named for the day, instead of disabling it', () => {
    const { onSelect, onLoadOlderDay } = renderControl({ activeIndex: 0 });
    const prev = screen.getByTestId('wf-win-prev');
    expect(prev).toBeEnabled();
    expect(prev).toHaveAccessibleName('Load Wed 14');

    fireEvent.click(prev);

    expect(onLoadOlderDay).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('does the same on ArrowLeft', () => {
    const { onSelect, onLoadOlderDay } = renderControl({ activeIndex: 0 });
    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'ArrowLeft' });
    expect(onLoadOlderDay).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('moves focus to the pill before the fetch, so a ‹ about to be disabled does not drop it to <body>', () => {
    let focusedAtFetch = null;
    const onLoadOlderDay = vi.fn(() => { focusedAtFetch = document.activeElement; });
    renderControl({ activeIndex: 0, onLoadOlderDay });
    screen.getByTestId('wf-win-prev').focus();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(focusedAtFetch).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('keeps ‹ as "Previous event" and an ordinary step anywhere but the oldest window', () => {
    const { onSelect, onLoadOlderDay } = renderControl({ activeIndex: 1 });
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Previous event, past');
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(onSelect).toHaveBeenCalledWith(Y_RISE);
    expect(onLoadOlderDay).not.toHaveBeenCalled();
  });

  it('disables ‹ on the oldest window once nothing older can be loaded (T-4, or loaded-empty)', () => {
    const { onLoadOlderDay } = renderControl({ activeIndex: 0, canLoadOlder: false });
    const prev = screen.getByTestId('wf-win-prev');
    expect(prev).toBeDisabled();
    expect(prev).toHaveAccessibleName('Previous event');
    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'ArrowLeft' });
    expect(onLoadOlderDay).not.toHaveBeenCalled();
  });

  it('leaves › alone', () => {
    const { onSelect } = renderControl({ activeIndex: 0 });
    fireEvent.click(screen.getByTestId('wf-win-next'));
    expect(onSelect).toHaveBeenCalledWith(Y_SET);
  });
});

describe('while an older day is loading', () => {
  const loadingProps = { olderDayLoadingLabel: 'Wed 14' };

  it('reads "Loading Wed 14…" on the pill, which is aria-busy', () => {
    renderControl({ activeIndex: 0, ...loadingProps });
    const pill = screen.getByTestId('wf-win-pill');
    expect(pill).toHaveTextContent('Loading Wed 14…');
    expect(pill).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByTestId('wf-win-loading')).toHaveClass('wf-win-label');
    expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
  });

  it('is not busy otherwise', () => {
    renderControl();
    expect(screen.getByTestId('wf-win-pill')).not.toHaveAttribute('aria-busy');
  });

  it('disables both steppers, and neither key steps or fetches again', () => {
    const { onSelect, onLoadOlderDay } = renderControl({ activeIndex: 2, ...loadingProps });
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    expect(screen.getByTestId('wf-win-next')).toBeDisabled();
    const pill = screen.getByTestId('wf-win-pill');
    fireEvent.keyDown(pill, { key: 'ArrowLeft' });
    fireEvent.keyDown(pill, { key: 'ArrowRight' });
    expect(onSelect).not.toHaveBeenCalled();
    expect(onLoadOlderDay).not.toHaveBeenCalled();
  });

  it('names the disabled ‹ "Previous event", not the fetch it is not offering', () => {
    renderControl({ activeIndex: 0, ...loadingProps });
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Previous event');
  });

  it('disables the Load row in an open menu', () => {
    const { onLoadOlderDay } = renderControl({ activeIndex: 2, ...loadingProps });
    openMenu();
    const load = screen.getByTestId('wf-win-load');
    expect(load).toBeDisabled();
    fireEvent.click(load);
    expect(onLoadOlderDay).not.toHaveBeenCalled();
  });

  it('announces the fetch in an always-mounted status region, and clears it when the fetch ends', () => {
    const { rerender } = renderControl({ activeIndex: 2 });
    const status = screen.getByTestId('wf-win-status');
    expect(status).toHaveAttribute('role', 'status');
    expect(status).toBeEmptyDOMElement();

    rerender(
      <WindowControl
        events={EVENTS}
        activeIndex={2}
        onSelect={() => {}}
        canLoadOlder
        olderDayLabel="Wed 14"
        olderDayLoadingLabel="Wed 14"
        onLoadOlderDay={() => {}}
      />,
    );
    // The SAME element: a live region added together with its text is not announced.
    expect(screen.getByTestId('wf-win-status')).toBe(status);
    expect(status).toHaveTextContent('Loading Wed 14…');

    rerender(
      <WindowControl
        events={EVENTS}
        activeIndex={2}
        onSelect={() => {}}
        canLoadOlder
        olderDayLabel="Tue 13"
        onLoadOlderDay={() => {}}
      />,
    );
    expect(status).toBeEmptyDOMElement();
  });

  it('keeps the pill\'s own menu reachable (the pill is not disabled)', () => {
    renderControl({ activeIndex: 2, ...loadingProps });
    expect(screen.getByTestId('wf-win-pill')).toBeEnabled();
  });
});

describe('opening the menu keeps the window on screen in view', () => {
  // jsdom has no layout, so the offsets the scroll reads are supplied: the menu is 420px tall and the
  // active row sits below its fold (the EARLIER group above it is what pushes it there).
  const originals = {};
  let scrollTop = 0;
  const define = (prop, getter, setter) => {
    originals[prop] = Object.getOwnPropertyDescriptor(HTMLElement.prototype, prop);
    Object.defineProperty(HTMLElement.prototype, prop, { configurable: true, get: getter, set: setter });
  };
  const isMenu = (el) => el.getAttribute('data-testid') === 'wf-win-menu';

  afterEach(() => {
    for (const prop of Object.keys(originals)) {
      if (originals[prop]) Object.defineProperty(HTMLElement.prototype, prop, originals[prop]);
      else delete HTMLElement.prototype[prop];
      delete originals[prop];
    }
    scrollTop = 0;
  });

  function layout({ rowTop, rowHeight = 47, menuScrollTop = 0 }) {
    scrollTop = menuScrollTop;
    define('offsetTop', function offsetTop() { return this.getAttribute('aria-selected') === 'true' ? rowTop : 0; });
    define('offsetHeight', function offsetHeight() { return this.getAttribute('aria-selected') === 'true' ? rowHeight : 0; });
    define('clientHeight', function clientHeight() { return isMenu(this) ? 420 : 0; });
    define('scrollTop', function get() { return isMenu(this) ? scrollTop : 0; }, function set(v) { if (isMenu(this)) scrollTop = v; });
  }

  it('scrolls the menu just far enough to show an active row below its fold', () => {
    layout({ rowTop: 500 });
    renderControl({ activeIndex: 3 });
    openMenu();
    // bottom 547, menu 420 tall: 547 - 420 + 6 breathing room.
    expect(scrollTop).toBe(133);
  });

  it('scrolls back up to an active row above the fold', () => {
    layout({ rowTop: 40, menuScrollTop: 200 });
    renderControl({ activeIndex: 3 });
    openMenu();
    expect(scrollTop).toBe(34);
  });

  it('leaves a menu whose active row is already in view exactly where it was', () => {
    layout({ rowTop: 100, menuScrollTop: 0 });
    renderControl({ activeIndex: 3 });
    openMenu();
    expect(scrollTop).toBe(0);
  });
});

describe('the stylesheet (jsdom applies none, so the real rules are read)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const bodyOf = (selector) => {
    const escaped = selector.replace(/[.[\]]/g, '\\$&');
    const match = css.match(new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`));
    return match ? match[1] : null;
  };
  const decl = (body, prop) => {
    const m = body?.match(new RegExp(`(?:^|;|\\s)${prop}\\s*:\\s*([^;]+);`));
    return m ? m[1].trim() : null;
  };

  it('.wf-win-earlier is the prototype\'s .wday.ear: mono 8.5px / 600, .13em, uppercase, space-between', () => {
    const body = bodyOf('.wf-win-earlier');
    expect(body).not.toBeNull();
    expect(decl(body, 'font-size')).toBe('8.5px');
    expect(decl(body, 'font-weight')).toBe('600');
    expect(decl(body, 'letter-spacing')).toBe('0.13em');
    expect(decl(body, 'text-transform')).toBe('uppercase');
    expect(decl(body, 'justify-content')).toBe('space-between');
  });

  it('the hint is not uppercase, not tracked, weight 400', () => {
    const body = bodyOf('.wf-win-earlier-hint');
    expect(decl(body, 'text-transform')).toBe('none');
    expect(decl(body, 'letter-spacing')).toBe('0');
    expect(decl(body, 'font-weight')).toBe('400');
  });

  it('.wf-win-sub is mono 9.5px / 600 in ink-2', () => {
    const body = bodyOf('.wf-win-sub');
    expect(decl(body, 'font-size')).toBe('9.5px');
    expect(decl(body, 'font-weight')).toBe('600');
    expect(decl(body, 'color')).toBe('var(--color-plex-text-secondary)');
  });

  it('the Load row\'s chip is teal on a 1px rgba(111,168,176,.35) border, its title teal', () => {
    const chip = bodyOf('.wf-win-load-chip');
    expect(decl(chip, 'color')).toBe('var(--color-tide)');
    expect(decl(chip, 'border')).toBe('1px solid rgba(111, 168, 176, 0.35)');
    expect(decl(bodyOf('.wf-win-load-title'), 'color')).toBe('var(--color-tide)');
  });

  it('the divider under the EARLIER group out-specifies `.wf-win-day:first-child`, which would otherwise strip it', () => {
    // Every day heading is the first child of its own wrapper, so `:first-child` (a class' worth of
    // specificity) matches them all; a bare single-class selector loses to it whatever the source order.
    expect(css).toMatch(/\.wf-win-day\.wf-win-day-after-earlier\s*\{[^}]*border-top:\s*1px solid/);
    expect(css).not.toMatch(/(^|\})\s*\.wf-win-day-after-earlier\s*\{/);
  });

  it('the phone Load row\'s chip does not shrink (a long sub-line would clip it)', () => {
    expect(decl(bodyOf('.wf-map-peek-load .wf-hc-sun'), 'flex-shrink')).toBe('0');
  });

  it('a disabled Load row dims as the disabled steppers do', () => {
    expect(decl(bodyOf('.wf-win-load:disabled'), 'opacity')).toBe(decl(bodyOf('.wf-win-step:disabled'), 'opacity') === '0.35' ? '0.35' : null);
  });

  it('the Load row\'s sub-line wraps (it is wider than the menu at 320px)', () => {
    expect(decl(bodyOf('.wf-win-load-sub'), 'white-space')).toBe('normal');
  });

  it('the loading label adds colour and weight only — nothing that could move the fixed pill width', () => {
    const body = bodyOf('.wf-win-loading');
    expect(body).not.toBeNull();
    const props = [...body.matchAll(/([a-z-]+)\s*:/g)].map((m) => m[1]).sort();
    expect(props).toEqual(['color', 'font-weight']);
  });

  it('a past row\'s time is ink-2 and "Not rated" is quieter than a real score', () => {
    expect(decl(bodyOf('.wf-win-row-pasttime'), 'color')).toBe('var(--color-plex-text-secondary)');
    expect(decl(bodyOf('.wf-win-notrated'), 'color')).toBe('rgba(242, 231, 211, 0.6)');
  });
});
