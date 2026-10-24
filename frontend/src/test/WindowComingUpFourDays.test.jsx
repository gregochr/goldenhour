import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import WindowComingUpFourDays from '../components/WindowComingUpFourDays.jsx';

/**
 * The "Next four days" lanes component (hot-topics-by-day-plan §3 C1). The model has its own
 * suite ({@code fourDayLanes.test.js}); what only the markup can be wrong about is placement,
 * names, taps, and the jump.
 *
 * <p>TODAY is Fri 23 Oct 2026 (days Fri 23 … Mon 26). jsdom loads no stylesheet in this suite, so
 * nothing here asserts a colour or a width: the grid's EXPLICIT PLACEMENT is read off each item's
 * inline style, which is the whole mechanism (auto-placement broke the prototype twice).
 */

const TODAY = '2026-10-23';
const SAT = '2026-10-24';
const MON = '2026-10-26';

const aurora = { type: 'AURORA', label: 'Aurora possible', date: TODAY, eventType: 'NIGHT' };
const inversion = { type: 'INVERSION', label: 'Inversion', date: SAT, eventType: 'SUNRISE' };
const tide = (date, tideRun) => ({
  type: 'SPRING_TIDE', label: 'Spring tide', date, eventType: null,
  tideRun: { dayNumber: 1, dayCount: 6, peak: false, ...tideRun },
});

const WINDOWS = new Set([`${TODAY}:SUNSET`, `${SAT}:SUNRISE`, `${MON}:SUNSET`, `${MON}:SUNRISE`]);

const renderLanes = (props = {}) => {
  const onGoToPlan = vi.fn();
  const onGoToPlanWindow = vi.fn();
  const view = render(
    <WindowComingUpFourDays
      todayStr={TODAY}
      hotTopics={[aurora, inversion]}
      openableKeys={WINDOWS}
      onGoToPlan={onGoToPlan}
      onGoToPlanWindow={onGoToPlanWindow}
      {...props}
    />,
  );
  return { ...view, onGoToPlan, onGoToPlanWindow };
};

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  vi.useRealTimers();
  document.querySelectorAll('[data-entry-id]').forEach((node) => node.remove());
  delete Element.prototype.scrollIntoView;
});

// What the server sends: a tide topic's tideRun counts only in-window days, so dayNumber == dayCount
// on the last one. "Open" is the straddling almanac entry below, not the topic.
const STRADDLER = {
  id: 'spring-tide:2026-10-24:2026-10-30', type: 'spring-tide', startDate: SAT, endDate: '2026-10-30',
};

describe('WindowComingUpFourDays — layout', () => {
  it('places every grid item: a row inline on each, a column inline on each bar, the static '
      + 'columns in CSS', () => {
    renderLanes({
      hotTopics: [aurora, inversion, tide('2026-10-25', {}), tide(MON, {})],
      entries: [STRADDLER],
      renderedEntryIds: new Set([STRADDLER.id]),
    });
    const grid = screen.getByTestId('coming-up-fourdays-grid');
    const items = [...grid.children];
    expect(items.length).toBeGreaterThan(8);
    for (const item of items) {
      const isDayHeader = item.hasAttribute('data-col');
      // Day headers sit in row 1 by CSS; everything else carries its row inline.
      if (!isDayHeader) expect(item.style.gridRow, item.className).not.toBe('');
      if (item.classList.contains('wf-cu-fd-seg')) {
        expect(item.style.gridColumn, item.className).toMatch(/^\d \/ \d$/);
      }
    }
  });

  it('gives the four day headers their data-col, today first — the CSS places them in row 1', () => {
    renderLanes();
    const days = screen.getAllByTestId('coming-up-fourdays-day');
    expect(days.map((d) => d.getAttribute('data-col'))).toEqual(['0', '1', '2', '3']);
    expect(days[0]).toHaveAttribute('data-today', 'true');
    expect(days[1]).not.toHaveAttribute('data-today');
    expect(days[0]).toHaveTextContent('Fri23');
  });

  it('gives each topic its own row from row 2', () => {
    renderLanes();
    const labels = screen.getAllByTestId('coming-up-fourdays-label');
    expect(labels.map((l) => l.style.gridRow)).toEqual(['2', '3']);
    expect(labels[0]).toHaveTextContent('Aurora');
    expect(labels[1]).toHaveTextContent('Inversion');
  });

  it('spans a bar over its columns and puts a dot in its single one', () => {
    renderLanes();
    // Aurora tonight: Fri + Sat = columns 2–3 (grid lines 2 / 4). Inversion: Sat only = column 3.
    const bar = screen.getByTestId('coming-up-fourdays-bar');
    expect(bar.parentElement.style.gridColumn).toBe('2 / 4');
    expect(bar.parentElement.style.gridRow).toBe('2');
    const dot = screen.getByTestId('coming-up-fourdays-dot');
    expect(dot.parentElement.style.gridColumn).toBe('3 / 4');
    expect(dot.parentElement.style.gridRow).toBe('3');
  });

  it('shades today\'s column across every row, under the items', () => {
    renderLanes();
    const shade = screen.getByTestId('coming-up-fourdays-grid').querySelector('.wf-cu-fd-now');
    expect(shade).toHaveAttribute('aria-hidden', 'true');
    expect(shade.style.gridRow).toBe('1 / 4');
  });

  it('carries the topic colour as --c on the item, not as a literal in a class', () => {
    renderLanes();
    expect(screen.getByTestId('coming-up-fourdays-bar').parentElement.style.getPropertyValue('--c'))
      .toBe('#8E86D6');
  });

  it('renders the full and short topic names, for the phone rule to choose between', () => {
    renderLanes({ hotTopics: [tide(SAT, {})] });
    const label = screen.getByTestId('coming-up-fourdays-label');
    expect(label.querySelector('.wf-cu-fd-nl')).toHaveTextContent('Spring tide');
    expect(label.querySelector('.wf-cu-fd-ns')).toHaveTextContent('Tide');
  });

  it('names its region from the visible "Next four days" label', () => {
    renderLanes();
    const region = screen.getByTestId('coming-up-fourdays');
    const label = screen.getByText('Next four days');
    expect(region).toHaveAttribute('aria-labelledby', label.id);
    expect(screen.getByRole('region', { name: 'Next four days' })).toBe(region);
  });
});

describe('WindowComingUpFourDays — names and glyphs', () => {
  it('starts a bar\'s name with the spoken form of its visible text, then the topic and the tap', () => {
    renderLanes();
    const bar = screen.getByTestId('coming-up-fourdays-bar');
    expect(bar).toHaveTextContent('tonight → Sat');
    expect(bar).toHaveAccessibleName('tonight to Saturday, Aurora, opens At a Glance on the Friday sunset');
  });

  it('keeps raw symbols out of every name and hides them from assistive technology', () => {
    renderLanes({
      hotTopics: [
        aurora, inversion,
        tide(SAT, {}), tide('2026-10-25', { peak: true, alignedEvent: 'sunset' }),
      ],
    });
    for (const el of [
      ...screen.getAllByTestId('coming-up-fourdays-bar'),
      ...screen.getAllByTestId('coming-up-fourdays-dot'),
    ]) {
      expect(el.getAttribute('aria-label')).not.toMatch(/[→↑↓·]/);
    }
    for (const el of document.querySelectorAll('.wf-cu-fd-bt, .wf-cu-fd-pk, .wf-cu-fd-dv')) {
      expect(el).toHaveAttribute('aria-hidden', 'true');
    }
  });

  it('speaks the peak in the bar\'s name when the bar prints one', () => {
    renderLanes({
      hotTopics: [tide(SAT, {}), tide('2026-10-25', { peak: true, alignedEvent: 'sunset' })],
      openableKeys: new Set(),
    });
    const bar = screen.getByTestId('coming-up-fourdays-bar');
    expect(bar).toHaveTextContent('Sat → Sun peak Sun · ↓ sunset');
    expect(bar).toHaveAccessibleName(
      'Saturday to Sunday, peak Sunday at sunset, Spring tide, opens At a Glance',
    );
  });

  it('names a dot by topic, day and window, and a no-card tap as plain "opens At a Glance"', () => {
    renderLanes({ openableKeys: new Set() });
    expect(screen.getByTestId('coming-up-fourdays-dot'))
      .toHaveAccessibleName('Inversion, Saturday sunrise, opens At a Glance');
  });

  it('adds "runs on" to an open dot\'s name', () => {
    renderLanes({ hotTopics: [tide(MON, {})], entries: [STRADDLER] });
    const dot = screen.getByTestId('coming-up-fourdays-dot');
    expect(dot).toHaveAccessibleName(
      'Spring tide, Monday sunrise and sunset, runs on, opens At a Glance on the Monday sunrise',
    );
    expect(dot).toHaveAttribute('data-open', 'true');
  });

  it('names an open bar from its start day, since its visible text is only "runs on"', () => {
    renderLanes({
      hotTopics: [tide(SAT, {}), tide('2026-10-25', {}), tide(MON, {})],
      entries: [STRADDLER],
      openableKeys: new Set([`${SAT}:SUNSET`]),
    });
    const bar = screen.getByTestId('coming-up-fourdays-bar');
    expect(bar).toHaveAttribute('data-open', 'true');
    expect(bar).toHaveTextContent('runs on');
    expect(bar).toHaveAccessibleName(
      'Saturday onwards, Spring tide, opens At a Glance on the Saturday sunset',
    );
  });

  it('hides the header arrow from assistive technology and labels the row glyph hidden', () => {
    renderLanes();
    expect(screen.getAllByTestId('coming-up-fourdays-label')[0].querySelector('.wf-cu-fd-i'))
      .toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
  });

  it('draws the dot\'s window glyph', () => {
    renderLanes();
    expect(screen.getByTestId('coming-up-fourdays-dot')).toHaveTextContent('↑');
  });

  it('keeps a literal space between the header\'s label and its button', () => {
    renderLanes();
    const head = screen.getByTestId('coming-up-fourdays').querySelector('.wf-cu-fd-head');
    expect(head.textContent).toBe('Next four days At a GlanceGlance →');
  });
});

describe('WindowComingUpFourDays — taps', () => {
  it('opens that window and carries the topic type when a Plan card exists', () => {
    const { onGoToPlan, onGoToPlanWindow } = renderLanes();
    fireEvent.click(screen.getByTestId('coming-up-fourdays-bar'));
    expect(onGoToPlanWindow).toHaveBeenCalledWith(`${TODAY}:SUNSET`, 'AURORA');
    fireEvent.click(screen.getByTestId('coming-up-fourdays-dot'));
    expect(onGoToPlanWindow).toHaveBeenLastCalledWith(`${SAT}:SUNRISE`, 'INVERSION');
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('is plain goToPlan() when the target is null', () => {
    const { onGoToPlan, onGoToPlanWindow } = renderLanes({ openableKeys: new Set() });
    fireEvent.click(screen.getByTestId('coming-up-fourdays-bar'));
    expect(onGoToPlan).toHaveBeenCalledWith();
    expect(onGoToPlanWindow).not.toHaveBeenCalled();
  });

  it('falls back to goToPlan() when no window door is wired at all', () => {
    const { onGoToPlan } = renderLanes({ onGoToPlanWindow: undefined });
    fireEvent.click(screen.getByTestId('coming-up-fourdays-bar'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
  });

  it('"At a Glance" calls onGoToPlan with no date', () => {
    const { onGoToPlan } = renderLanes();
    fireEvent.click(screen.getByTestId('coming-up-fourdays-plan'));
    expect(onGoToPlan).toHaveBeenCalledWith();
  });
});

describe('WindowComingUpFourDays — the jump button', () => {
  const ENTRY = STRADDLER;
  const open = (props = {}) => renderLanes({
    hotTopics: [tide('2026-10-25', {}), tide(MON, {})],
    entries: [ENTRY],
    renderedEntryIds: new Set([ENTRY.id]),
    ...props,
  });
  /** The list card the jump aims at, as the chronology renders it: a wrapper, day chips, then the card. */
  const mountCard = ({ interactive = true } = {}) => {
    const wrap = document.createElement('div');
    wrap.setAttribute('data-entry-id', ENTRY.id);
    const chip = document.createElement('button');
    chip.setAttribute('data-testid', 'coming-up-day-chip');
    wrap.appendChild(chip);
    const card = document.createElement(interactive ? 'button' : 'div');
    card.setAttribute('data-testid', 'coming-up-card');
    wrap.appendChild(card);
    document.body.appendChild(wrap);
    return { wrap, card, chip };
  };

  it('names its topic and what it does, with the tooltip as the short form, in its own row', () => {
    open();
    const jump = screen.getByTestId('coming-up-fourdays-jump');
    expect(jump).toHaveAccessibleName('Spring tide: show the run in the list, 24–30 Oct');
    expect(jump).toHaveAttribute('title', 'In the list · 24–30 Oct');
    expect(jump.style.gridRow).toBe('2');
    expect(within(jump).getByText('↓')).toHaveAttribute('aria-hidden', 'true');
  });

  it('says "next run" when the matching entry has not started', () => {
    const next = { ...ENTRY, id: 'next', startDate: '2026-10-29', endDate: '2026-10-31' };
    open({ entries: [ENTRY, next], renderedEntryIds: new Set(['next']) });
    expect(screen.getByTestId('coming-up-fourdays-jump'))
      .toHaveAccessibleName('Spring tide: show the next run in the list, 29–31 Oct');
  });

  it('scrolls the card into view, rings it for 1.6 s and focuses the CARD — not a day chip', () => {
    vi.useFakeTimers();
    open();
    const { wrap, card, chip } = mountCard();
    fireEvent.click(screen.getByTestId('coming-up-fourdays-jump'));

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
    expect(wrap).toHaveClass('wf-cu-ent-highlight');
    expect(card).toHaveFocus();
    expect(chip).not.toHaveFocus();

    act(() => { vi.advanceTimersByTime(1599); });
    expect(wrap).toHaveClass('wf-cu-ent-highlight');
    act(() => { vi.advanceTimersByTime(1); });
    expect(wrap).not.toHaveClass('wf-cu-ent-highlight');
  });

  it('scrolls instantly when the reader prefers reduced motion', () => {
    window.matchMedia = vi.fn(() => ({ matches: true }));
    try {
      open();
      mountCard();
      fireEvent.click(screen.getByTestId('coming-up-fourdays-jump'));
      expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'center' });
    } finally {
      delete window.matchMedia;
    }
  });

  it('focuses the wrapper with a temporary tabindex of -1 when the card is not a button, '
      + 'and removes it on blur', () => {
    open();
    const { wrap } = mountCard({ interactive: false });
    fireEvent.click(screen.getByTestId('coming-up-fourdays-jump'));
    expect(wrap).toHaveAttribute('tabindex', '-1');
    expect(wrap).toHaveFocus();
    act(() => { wrap.blur(); });
    expect(wrap).not.toHaveAttribute('tabindex');
  });

  it('is harmless when the card is not in the DOM', () => {
    open();
    expect(() => fireEvent.click(screen.getByTestId('coming-up-fourdays-jump'))).not.toThrow();
  });

  it('renders no jump button when there is no target — no dead buttons', () => {
    open({ renderedEntryIds: new Set() });
    expect(screen.queryByTestId('coming-up-fourdays-jump')).toBeNull();
  });

  it('renders none for a run that does not continue past Monday', () => {
    renderLanes({
      hotTopics: [tide(SAT, {})],
      entries: [{ ...ENTRY, startDate: '2026-10-29' }],
      renderedEntryIds: new Set([ENTRY.id]),
    });
    expect(screen.queryByTestId('coming-up-fourdays-jump')).toBeNull();
  });
});

describe('WindowComingUpFourDays — degrade states', () => {
  it('shows only the header and the day headers before the briefing has arrived', () => {
    renderLanes({ hotTopics: undefined });
    expect(screen.getByText('Next four days')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
    expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
    expect(screen.queryByTestId('coming-up-fourdays-label')).toBeNull();
    expect(screen.queryByTestId('coming-up-fourdays-empty')).toBeNull();
  });

  it('shows the header and one line once an empty list has arrived, synthesising nothing', () => {
    renderLanes({ hotTopics: [] });
    expect(screen.getByTestId('coming-up-fourdays-empty')).toHaveTextContent('Nothing on those four days');
    expect(screen.queryByTestId('coming-up-fourdays-label')).toBeNull();
    expect(screen.queryByTestId('coming-up-fourdays-day')).toBeNull();
    expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
  });

  it('shows only the header while today is not yet known', () => {
    renderLanes({ todayStr: '' });
    expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
    expect(screen.queryByTestId('coming-up-fourdays-day')).toBeNull();
  });
});
