import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';
import WindowGlanceDayTopics, { GlanceTopicDoor, GlanceTopicKey } from '../components/WindowGlanceDayTopics.jsx';
import { buildFourDayTiles, glanceTopicsByColumn } from '../utils/fourDayTiles.js';
import { MATRIX_ROW } from '../utils/windowFirstMatrix.js';

/**
 * The At a Glance day header's topic pills (glance-topic-strip-plan.md). The model has its own suite
 * ({@code fourDayTiles.test.js}) and the strip's wiring is in {@code WindowFirstHeatStrip.test.jsx}; what only the
 * markup can be wrong about is the pills' structure, their accessible names, the tap hand-off and the empty states.
 *
 * <p>TODAY is Sat 24 Oct 2026 (days Sat 24 … Tue 27). jsdom loads no stylesheet in this suite, so both halves of every
 * desktop / phone span pair count in a name, and nothing here asserts a colour or a width — the stylesheet's own text
 * is pinned in {@code glanceTopicStripCss.test.js}.
 */

const TODAY = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const TUE = '2026-10-27';
const DATES = [TODAY, SUN, MON, TUE];

const surge = {
  type: 'STORM_SURGE', label: 'Storm surge', date: TODAY, eventType: null,
  glance: { value: 'HW 16:02 · +0.4 m', shortValue: '+0.4 m pk', badge: 'PEAK', weather: false },
};
const tide = (date) => ({
  type: 'SPRING_TIDE', label: 'Spring tide', date, eventType: null,
  tideRun: { dayNumber: 1, dayCount: 4, peak: false },
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

/** A row of stacks the way the strip draws them: one header per date, then one stack per date. */
function Harness({ topics, columns = DATES, onTap = () => {}, phone = false, builderArgs = {} }) {
  const { days } = buildFourDayTiles({ todayStr: TODAY, hotTopics: topics, ...builderArgs });
  const byDate = glanceTopicsByColumn(days, columns);
  return (
    <div>
      {columns.map((date, i) => (
        <WindowGlanceDayTopics
          key={date}
          date={date}
          column={i + 1}
          row={MATRIX_ROW.topics}
          chips={byDate.get(date) ?? []}
          todayStr={TODAY}
          phone={phone}
          onTap={onTap}
        />
      ))}
    </div>
  );
}

const renderStacks = (props = {}) => render(<Harness topics={[surge, inversion, dust]} {...props} />);
const pill = (type) => screen.getAllByTestId('wf-glance-topic').find((c) => c.getAttribute('data-topic') === type);

afterEach(() => {
  cleanup();
});

describe('WindowGlanceDayTopics — the stacks', () => {
  it('draws each topic under the date it lands on, in that date\'s column', () => {
    renderStacks();
    const cells = screen.getAllByTestId(/wf-glance-topics(-empty)?$/);
    expect(cells).toHaveLength(4);
    expect(cells.map((c) => c.style.getPropertyValue('--c'))).toEqual(['1', '2', '3', '4']);
    expect(cells.map((c) => c.style.getPropertyValue('--r'))).toEqual(['2', '2', '2', '2']);
    expect(within(cells[0]).getByTestId('wf-glance-topic')).toHaveAttribute('data-topic', 'STORM_SURGE');
    expect(within(cells[1]).getByTestId('wf-glance-topic')).toHaveAttribute('data-topic', 'INVERSION');
    expect(within(cells[2]).getByTestId('wf-glance-topic')).toHaveAttribute('data-topic', 'DUST');
  });

  it('keeps an empty day\'s column but renders no group and no list for it', () => {
    renderStacks();
    const tuesday = screen.getAllByTestId('wf-glance-topics-empty');
    expect(tuesday).toHaveLength(1);
    expect(tuesday[0]).toBeEmptyDOMElement();
    expect(screen.getAllByRole('group')).toHaveLength(3);
    expect(screen.getAllByRole('list')).toHaveLength(3);
  });

  it('renders nothing at all for an empty day on the phone, whose grid flows', () => {
    renderStacks({ phone: true });
    expect(screen.queryByTestId('wf-glance-topics-empty')).toBeNull();
    expect(screen.getAllByTestId('wf-glance-topics')).toHaveLength(3);
  });

  it('names each group by its own long-form day: today, tomorrow, then the bare weekday', () => {
    renderStacks();
    expect(screen.getByRole('group', { name: 'Saturday 24, today' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Sunday 25, tomorrow' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Monday 26' })).toBeInTheDocument();
  });

  it('makes each pill a button inside a list item, with no aria-label', () => {
    renderStacks();
    for (const button of screen.getAllByTestId('wf-glance-topic')) {
      expect(button.tagName).toBe('BUTTON');
      expect(button.parentElement.tagName).toBe('LI');
      expect(button).not.toHaveAttribute('aria-label');
    }
  });

  it('sets the topic colour only as the --c custom property', () => {
    renderStacks();
    expect(pill('DUST').style.getPropertyValue('--c')).toBe('#f97316');
    expect(pill('DUST').getAttribute('style')).toBe('--c: #f97316;');
  });

  it('hides the swatch and the tags from assistive technology, and shows PEAK and WX in them', () => {
    const { container } = renderStacks({ topics: [surge, inversion] });
    for (const decoration of container.querySelectorAll('.wf-dt-sw, .wf-dt-tags')) {
      expect(decoration).toHaveAttribute('aria-hidden', 'true');
    }
    expect(container.querySelector('.wf-dt-badge')).toHaveTextContent('PEAK');
    expect(container.querySelector('.wf-dt-wx')).toHaveTextContent('WX');
  });

  it('prints both name spans and both value spans, one of each pair per media query', () => {
    renderStacks({ topics: [surge] });
    const button = pill('STORM_SURGE');
    expect(button.querySelector('.wf-dt-nl')).toHaveTextContent('Storm surge');
    expect(button.querySelector('.wf-dt-ns')).toHaveTextContent('Surge');
    expect(button.querySelector('.wf-dt-vl')).toHaveTextContent('HW 16:02 · +0.4 m');
    expect(button.querySelector('.wf-dt-vs')).toHaveTextContent('+0.4 m pk');
  });

  it('draws a name-only pill for a topic with no glance — no value line, no tags', () => {
    const { container } = renderStacks({ topics: [dust] });
    expect(container.querySelector('.wf-dt-val')).toBeNull();
    expect(container.querySelector('.wf-dt-tags')).toBeNull();
  });

  it('draws a topic that starts solid and one that continues dashed', () => {
    renderStacks({ topics: [tide(TODAY), tide(SUN)] });
    const [first, second] = screen.getAllByTestId('wf-glance-topic');
    expect(first).not.toHaveAttribute('data-continuation');
    expect(second).toHaveAttribute('data-continuation', 'true');
  });

  it('forces a continuation solid when its previous day is not a rendered column (R2)', () => {
    renderStacks({ topics: [tide(TODAY), tide(SUN)], columns: [SUN, MON, TUE] });
    expect(screen.getByTestId('wf-glance-topic')).not.toHaveAttribute('data-continuation');
  });

  it('keeps a continuation dashed when the previous day IS a rendered column', () => {
    renderStacks({ topics: [tide(TODAY), tide(SUN), tide(MON)], columns: [TODAY, SUN, MON] });
    const pills = screen.getAllByTestId('wf-glance-topic');
    expect(pills.map((p) => p.getAttribute('data-continuation'))).toEqual([null, 'true', 'true']);
  });
});

describe('WindowGlanceDayTopics — the accessible name is built from content', () => {
  it('starts with the visible name, then the value, the badge word, the weekday and the destination', () => {
    renderStacks({ topics: [moon], builderArgs: { openableKeys: new Set() } });
    expect(pill('SUPERMOON')).toHaveAccessibleName(/^Supermoon/);
    expect(pill('SUPERMOON').textContent).toContain('rise 16:34');
    expect(pill('SUPERMOON')).toHaveAccessibleName(/full moon, Monday, opens Coming up$/);
  });

  it('reads one peak pill as exactly this name (both name and value spans count in jsdom)', () => {
    renderStacks({ topics: [surge], builderArgs: { openableKeys: new Set() } });
    expect(pill('STORM_SURGE')).toHaveAccessibleName(
      'Storm surge Surge HW 16:02 · +0.4 m +0.4 m pk, peak, Saturday, opens Coming up',
    );
  });

  it('says "weather-driven" and names the Map door for a weather pill, morning or evening', () => {
    renderStacks({
      topics: [inversion, { ...inversion, type: 'MIST', date: MON, eventType: 'SUNSET' }],
    });
    expect(pill('INVERSION')).toHaveAccessibleName(/weather-driven, Sunday, opens the Map that morning$/);
    expect(pill('MIST')).toHaveAccessibleName(/weather-driven, Monday, opens the Map that evening$/);
  });

  it('says "shown in Coming up" for a pill that jumps', () => {
    const run = {
      id: 'spring-tide:2026-10-24:2026-10-30', type: 'spring-tide', startDate: TODAY, endDate: '2026-10-30',
    };
    renderStacks({
      topics: [tide(SUN)], builderArgs: { entries: [run], renderedEntryIds: new Set([run.id]) },
    });
    expect(pill('SPRING_TIDE')).toHaveAccessibleName(/^Spring tide/);
    expect(pill('SPRING_TIDE')).toHaveAccessibleName(/Sunday, shown in Coming up$/);
  });

  it('names the At a Glance window for a pill that opens one', () => {
    renderStacks({
      topics: [dust], builderArgs: { openableKeys: new Set([`${MON}:SUNSET`]) },
    });
    expect(pill('DUST')).toHaveAccessibleName(/Monday, opens the At a Glance window$/);
  });

  it('speaks a continuation, which the dashed border otherwise shows alone', () => {
    renderStacks({ topics: [tide(TODAY), tide(SUN)] });
    const [first, second] = screen.getAllByTestId('wf-glance-topic');
    expect(first).not.toHaveAccessibleName(/continues/);
    expect(second).toHaveAccessibleName(/, continues, Sunday/);
  });

  it('puts no decorative text (WX, PEAK) in the name', () => {
    renderStacks({ topics: [surge, inversion] });
    expect(pill('INVERSION').textContent).toContain('WX');
    expect(pill('INVERSION')).not.toHaveAccessibleName(/\bWX\b/);
    expect(pill('STORM_SURGE')).not.toHaveAccessibleName(/PEAK/);
  });

  it('keeps a literal space between a pill\'s name block and its value (the separator rule)', () => {
    renderStacks({ topics: [surge] });
    const value = pill('STORM_SURGE').querySelector('.wf-dt-val');
    expect(value.previousSibling.nodeType).toBe(Node.TEXT_NODE);
    expect(value.previousSibling.textContent).toBe(' ');
  });
});

describe('WindowGlanceDayTopics — a press hands the chip to onTap', () => {
  it('passes the whole chip, so the strip can pick the route', () => {
    const onTap = vi.fn();
    renderStacks({ onTap });
    fireEvent.click(pill('DUST'));
    expect(onTap).toHaveBeenCalledTimes(1);
    expect(onTap).toHaveBeenCalledWith(expect.objectContaining({ type: 'DUST', key: `${MON}:DUST` }));
  });
});

describe('GlanceTopicKey and GlanceTopicDoor', () => {
  it('states "starts" and "continues" — not the handoff\'s "could still move" — and is aria-hidden', () => {
    render(<GlanceTopicKey />);
    const key = screen.getByTestId('wf-glance-topic-key');
    expect(key).toHaveAttribute('aria-hidden', 'true');
    expect(key).toHaveTextContent('startscontinues');
    expect(key).not.toHaveTextContent(/could still move/);
  });

  it('is a button that opens Coming up with no entry, and says "Dated events", not "All"', () => {
    const onOpen = vi.fn();
    render(<GlanceTopicDoor onOpen={onOpen} />);
    const door = screen.getByRole('button', { name: /^Dated events in Coming up/ });
    expect(door).not.toHaveAccessibleName(/\bAll\b/);
    fireEvent.click(door);
    expect(onOpen).toHaveBeenCalledWith(null);
  });
});
