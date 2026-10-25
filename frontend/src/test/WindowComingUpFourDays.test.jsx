import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';
import WindowComingUpFourDays from '../components/WindowComingUpFourDays.jsx';
import { scrollToEntry } from '../utils/scrollToEntry.js';

vi.mock('../utils/scrollToEntry.js', () => ({ scrollToEntry: vi.fn() }));

/**
 * The "Next four days" tiles component (coming-up-glance-plan §3 T1). The model has its own suite
 * ({@code fourDayTiles.test.js}); what only the markup can be wrong about is the tiles' structure,
 * the chips' accessible names, the three tap routes and the empty states.
 *
 * <p>TODAY is Sat 24 Oct 2026 (days Sat 24 … Tue 27). jsdom loads no stylesheet in this suite, so both
 * halves of every desktop / phone span pair count in a name, and nothing here asserts a colour or a
 * width — the stylesheet's own text is pinned in {@code comingUpFourDaysCss.test.js}.
 */

const TODAY = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';

const surge = {
  type: 'STORM_SURGE', label: 'Storm surge', date: TODAY, eventType: null,
  glance: { value: 'HW 16:02 · +0.4 m', shortValue: '+0.4 m pk', badge: 'PEAK', weather: false },
};
const tide = (date, glance) => ({
  type: 'SPRING_TIDE', label: 'Spring tide', date, eventType: null,
  tideRun: { dayNumber: 1, dayCount: 4, peak: false },
  ...(glance ? { glance } : {}),
});
const inversion = {
  type: 'INVERSION', label: 'Inversion', date: SUN, eventType: 'SUNRISE',
  glance: { value: 'dawn · 9/10 · strong', shortValue: '9/10', weather: true },
};
const dust = { type: 'DUST', label: 'Saharan dust', date: MON, eventType: 'SUNSET' };
const moon = {
  type: 'SUPERMOON', label: 'Supermoon', date: MON, eventType: 'SUNSET',
  glance: { value: 'rise 16:34', shortValue: 'super', badge: 'FULL', weather: false },
};

const RUN = {
  id: 'spring-tide:2026-10-24:2026-10-30', type: 'spring-tide', startDate: TODAY, endDate: '2026-10-30',
};

const renderTiles = (props = {}) => {
  const onGoToPlan = vi.fn();
  const onGoToPlanWindow = vi.fn();
  const onShowOnMap = vi.fn();
  const view = render(
    <WindowComingUpFourDays
      todayStr={TODAY}
      hotTopics={[surge, inversion, dust]}
      openableKeys={new Set([`${MON}:SUNSET`])}
      onGoToPlan={onGoToPlan}
      onGoToPlanWindow={onGoToPlanWindow}
      onShowOnMap={onShowOnMap}
      {...props}
    />,
  );
  return { ...view, onGoToPlan, onGoToPlanWindow, onShowOnMap };
};

const chip = (type) => screen.getAllByTestId('coming-up-fourdays-chip')
  .find((c) => c.getAttribute('data-topic') === type);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('WindowComingUpFourDays — the tiles', () => {
  it('always renders four named groups, today\'s marked aria-current="date"', () => {
    renderTiles();
    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(4);
    expect(screen.getByRole('group', { name: 'Saturday 24, today' })).toHaveAttribute('aria-current', 'date');
    expect(screen.getByRole('group', { name: 'Sunday 25, tomorrow' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('group', { name: 'Monday 26, in 2 days' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Tuesday 27, in 3 days' })).toBeInTheDocument();
  });

  it('renders no list at all for an empty day, but keeps the tile', () => {
    renderTiles();
    const tuesday = screen.getByRole('group', { name: /^Tuesday 27/ });
    expect(within(tuesday).queryByRole('list')).toBeNull();
    // Saturday, Sunday and Monday have chips and so a list each.
    expect(screen.getAllByRole('list')).toHaveLength(3);
  });

  it('makes each chip a button inside a list item, with no aria-label', () => {
    renderTiles();
    for (const button of screen.getAllByTestId('coming-up-fourdays-chip')) {
      expect(button.tagName).toBe('BUTTON');
      expect(button.parentElement.tagName).toBe('LI');
      expect(button).not.toHaveAttribute('aria-label');
    }
  });

  it('sets the topic colour only as the --c custom property', () => {
    renderTiles();
    expect(chip('DUST').style.getPropertyValue('--c')).toBe('#f97316');
    expect(chip('DUST').getAttribute('style')).toBe('--c: #f97316;');
  });

  it('hides the swatch, the badge box and the WX box from assistive technology', () => {
    const { container } = renderTiles({ hotTopics: [surge, inversion] });
    expect(container.querySelectorAll('.wf-cu-fd-sw')).toHaveLength(2);
    for (const decoration of container.querySelectorAll('.wf-cu-fd-sw, .wf-cu-fd-badge, .wf-cu-fd-wx')) {
      expect(decoration).toHaveAttribute('aria-hidden', 'true');
    }
    expect(container.querySelector('.wf-cu-fd-badge')).toHaveTextContent('PEAK');
    expect(container.querySelector('.wf-cu-fd-wx')).toHaveTextContent('WX');
  });

  it('prints both name spans and both value spans, one of each pair per media query', () => {
    renderTiles({ hotTopics: [surge] });
    const button = chip('STORM_SURGE');
    expect(button.querySelector('.wf-cu-fd-nl')).toHaveTextContent('Storm surge');
    expect(button.querySelector('.wf-cu-fd-ns')).toHaveTextContent('Surge');
    expect(button.querySelector('.wf-cu-fd-vl')).toHaveTextContent('HW 16:02 · +0.4 m');
    expect(button.querySelector('.wf-cu-fd-vs')).toHaveTextContent('+0.4 m pk');
  });

  it('draws a name-only chip for a topic with no glance — no value line, no badge, no WX', () => {
    const { container } = renderTiles({ hotTopics: [dust] });
    expect(container.querySelector('.wf-cu-fd-val')).toBeNull();
    expect(container.querySelector('.wf-cu-fd-badge')).toBeNull();
    expect(container.querySelector('.wf-cu-fd-wx')).toBeNull();
  });

  it('marks a continuation chip', () => {
    renderTiles({ hotTopics: [tide(TODAY), tide(SUN)] });
    const chips = screen.getAllByTestId('coming-up-fourdays-chip');
    expect(chips[0]).not.toHaveAttribute('data-continuation');
    expect(chips[1]).toHaveAttribute('data-continuation', 'true');
  });
});

describe('WindowComingUpFourDays — the accessible name is built from content', () => {
  it('starts with the visible name, then the value, the badge word, the weekday and the destination', () => {
    renderTiles({ hotTopics: [moon] });
    const name = chip('SUPERMOON').textContent;
    expect(chip('SUPERMOON')).toHaveAccessibleName(/^Supermoon/);
    expect(name).toContain('rise 16:34');
    expect(chip('SUPERMOON')).toHaveAccessibleName(/full moon, Monday, opens At a Glance$/);
  });

  it('says "peak" for a PEAK badge', () => {
    renderTiles({ hotTopics: [surge] });
    expect(chip('STORM_SURGE')).toHaveAccessibleName(/^Storm surge/);
    expect(chip('STORM_SURGE')).toHaveAccessibleName(/peak, Saturday, opens At a Glance$/);
  });

  it('says "weather-driven" and names the Map door for a weather chip, morning or evening', () => {
    renderTiles({
      hotTopics: [
        inversion,
        { ...inversion, type: 'MIST', date: MON, eventType: 'SUNSET' },
      ],
    });
    expect(chip('INVERSION')).toHaveAccessibleName(/^Inversion/);
    expect(chip('INVERSION')).toHaveAccessibleName(/weather-driven, Sunday, opens the Map that morning$/);
    expect(chip('MIST')).toHaveAccessibleName(/weather-driven, Monday, opens the Map that evening$/);
  });

  it('says "shown in the list below" for a chip that jumps', () => {
    renderTiles({
      hotTopics: [tide(SUN)], entries: [RUN], renderedEntryIds: new Set([RUN.id]),
    });
    expect(chip('SPRING_TIDE')).toHaveAccessibleName(/^Spring tide/);
    expect(chip('SPRING_TIDE')).toHaveAccessibleName(/Sunday, shown in the list below$/);
  });

  it('reads one peak chip as exactly this name (both name and value spans count in jsdom)', () => {
    renderTiles({ hotTopics: [surge] });
    expect(chip('STORM_SURGE')).toHaveAccessibleName(
      'Storm surge Surge HW 16:02 · +0.4 m +0.4 m pk, peak, Saturday, opens At a Glance',
    );
  });

  it('speaks a continuation, which the dashed border otherwise shows alone', () => {
    renderTiles({ hotTopics: [tide(TODAY), tide(SUN)] });
    const [first, second] = screen.getAllByTestId('coming-up-fourdays-chip');
    expect(first).not.toHaveAccessibleName(/continues/);
    expect(second).toHaveAccessibleName(/, continues, Sunday/);
  });

  it('puts no decorative text (the swatch, WX, PEAK) in the name', () => {
    renderTiles({ hotTopics: [surge, inversion] });
    expect(chip('INVERSION').textContent).toContain('WX');
    expect(chip('INVERSION')).not.toHaveAccessibleName(/\bWX\b/);
    expect(chip('STORM_SURGE')).not.toHaveAccessibleName(/PEAK/);
  });
});

describe('WindowComingUpFourDays — what a tap does (first match wins)', () => {
  it('scrolls to the entry for a chip with a jump', () => {
    const { onShowOnMap, onGoToPlanWindow, onGoToPlan } = renderTiles({
      hotTopics: [tide(SUN)], entries: [RUN], renderedEntryIds: new Set([RUN.id]),
    });
    fireEvent.click(chip('SPRING_TIDE'));
    expect(scrollToEntry).toHaveBeenCalledWith(RUN.id);
    expect(onShowOnMap).not.toHaveBeenCalled();
    expect(onGoToPlanWindow).not.toHaveBeenCalled();
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('opens the Map at that morning for a weather chip with a door', () => {
    const { onShowOnMap, onGoToPlanWindow, onGoToPlan } = renderTiles({
      hotTopics: [inversion], openableKeys: new Set([`${SUN}:SUNRISE`]),
    });
    fireEvent.click(chip('INVERSION'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      kind: 'event', date: SUN, eventType: 'SUNRISE', topicType: 'INVERSION',
    });
    expect(scrollToEntry).not.toHaveBeenCalled();
    expect(onGoToPlanWindow).not.toHaveBeenCalled();
    expect(onGoToPlan).not.toHaveBeenCalled();
  });

  it('opens the Plan window when there is a card for it', () => {
    const { onGoToPlanWindow, onGoToPlan, onShowOnMap } = renderTiles();
    fireEvent.click(chip('DUST'));
    expect(onGoToPlanWindow).toHaveBeenCalledWith(`${MON}:SUNSET`, 'DUST');
    expect(onGoToPlan).not.toHaveBeenCalled();
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('goes to plain At a Glance when no window card exists for the chip', () => {
    const { onGoToPlanWindow, onGoToPlan } = renderTiles({ openableKeys: new Set() });
    fireEvent.click(chip('DUST'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
    expect(onGoToPlan).toHaveBeenCalledWith();
    expect(onGoToPlanWindow).not.toHaveBeenCalled();
  });

  it('falls back to the Plan window for a weather chip whose date has no forecast', () => {
    const { onShowOnMap, onGoToPlanWindow } = renderTiles({
      hotTopics: [inversion], forecastDates: [TODAY], openableKeys: new Set([`${SUN}:SUNRISE`]),
    });
    fireEvent.click(chip('INVERSION'));
    expect(onShowOnMap).not.toHaveBeenCalled();
    expect(onGoToPlanWindow).toHaveBeenCalledWith(`${SUN}:SUNRISE`, 'INVERSION');
  });

  it('⚠️ builds no Map door without onShowOnMap: the name and the tap agree on the Plan window', () => {
    const { onGoToPlanWindow } = renderTiles({
      hotTopics: [inversion], onShowOnMap: undefined, openableKeys: new Set([`${SUN}:SUNRISE`]),
    });
    expect(chip('INVERSION')).toHaveAccessibleName(/weather-driven, Sunday, opens At a Glance$/);
    fireEvent.click(chip('INVERSION'));
    expect(onGoToPlanWindow).toHaveBeenCalledWith(`${SUN}:SUNRISE`, 'INVERSION');
  });

  it('prefers the list jump to the Map door for a weather chip that has an entry', () => {
    const nlc = {
      type: 'NLC', label: 'NLC', date: SUN, eventType: 'SUNSET',
      glance: { value: 'dusk', shortValue: 'dusk', weather: true },
    };
    const season = {
      id: 'nlc-season:2026-10-20:2026-10-30', type: 'nlc-season',
      startDate: '2026-10-20', endDate: '2026-10-30',
    };
    const { onShowOnMap } = renderTiles({
      hotTopics: [nlc], entries: [season], renderedEntryIds: new Set([season.id]),
    });
    fireEvent.click(chip('NLC'));
    expect(scrollToEntry).toHaveBeenCalledWith(season.id);
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('falls back to plain onGoToPlan when no onGoToPlanWindow was supplied', () => {
    const { onGoToPlan } = renderTiles({
      onGoToPlanWindow: undefined, openableKeys: new Set([`${MON}:SUNSET`]),
    });
    fireEvent.click(chip('DUST'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
  });

  it('keeps a literal space between the header label and its button', () => {
    renderTiles();
    const space = screen.getByTestId('coming-up-fourdays-plan').previousSibling;
    expect(space.nodeType).toBe(Node.TEXT_NODE);
    expect(space.textContent).toBe(' ');
  });

  it('names the route the tap takes (a weather chip with no door says it opens At a Glance)', () => {
    renderTiles({ hotTopics: [inversion], forecastDates: [] });
    expect(chip('INVERSION')).toHaveAccessibleName(/opens At a Glance$/);
  });
});

describe('WindowComingUpFourDays — the header and footnote', () => {
  it('names the section "Next four days" and pluralises the desktop suffix', () => {
    renderTiles({ hotTopics: [surge, inversion, dust] });
    expect(screen.getByRole('region', { name: 'Next four days' })).toBeInTheDocument();
    expect(screen.getByTestId('coming-up-fourdays-count')).toHaveTextContent('3 topics · from the forecast');
  });

  it('says "1 topic" for one, and hides the suffix at zero or before the briefing', () => {
    renderTiles({ hotTopics: [surge] });
    expect(screen.getByTestId('coming-up-fourdays-count')).toHaveTextContent('1 topic · from the forecast');
    cleanup();
    renderTiles({ hotTopics: [] });
    expect(screen.queryByTestId('coming-up-fourdays-count')).toBeNull();
    cleanup();
    renderTiles({ hotTopics: undefined });
    expect(screen.queryByTestId('coming-up-fourdays-count')).toBeNull();
  });

  it('has an At a Glance link button that calls onGoToPlan with no date', () => {
    const { onGoToPlan } = renderTiles();
    fireEvent.click(screen.getByRole('button', { name: /^At a Glance/ }));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
    expect(onGoToPlan).toHaveBeenCalledWith();
  });

  it('prints no explanatory footnote, with topics or without', () => {
    renderTiles();
    expect(screen.queryByTestId('coming-up-fourdays-foot')).toBeNull();
    cleanup();
    renderTiles({ hotTopics: [] });
    expect(screen.queryByTestId('coming-up-fourdays-foot')).toBeNull();
  });
});

describe('WindowComingUpFourDays — empty states', () => {
  it('shows the header and four empty tiles before the briefing arrives', () => {
    renderTiles({ hotTopics: undefined });
    expect(screen.getByTestId('coming-up-fourdays')).toHaveTextContent('Next four days');
    expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
    expect(screen.queryByRole('list')).toBeNull();
    expect(screen.queryByTestId('coming-up-fourdays-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-fourdays-foot')).toBeNull();
  });

  it('says "Nothing on those four days" once the briefing has arrived empty, keeping the tiles', () => {
    renderTiles({ hotTopics: [] });
    expect(screen.getByTestId('coming-up-fourdays-empty')).toHaveTextContent('Nothing on those four days');
    expect(screen.getAllByTestId('coming-up-fourdays-day')).toHaveLength(4);
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('draws only the header with no todayStr', () => {
    renderTiles({ todayStr: undefined, hotTopics: [surge] });
    expect(screen.queryAllByTestId('coming-up-fourdays-day')).toHaveLength(0);
    expect(screen.getByRole('button', { name: /^At a Glance/ })).toBeInTheDocument();
  });
});
