import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { comingUpOutsideFacts, outsideLineText } from '../utils/comingUpOutside.js';
import { isCoastalLocation, isDarkSkyLocation } from '../utils/locationPredicates.js';
import { FIRST_CHOICE, OPEN, LOCKED_FULL } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

let mockBriefing = {};
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => mockBriefing,
}));

/**
 * Coming up for a Lite reader outside their picks (sub-regions-plan §11 S5b-2, items 10-12).
 *
 * <p><b>What breaks if these fail:</b> the line says a reader has no coastal or dark-sky place when they do (or
 * stays silent when they do not), the line and the map disagree about what coastal means, the door lands inside a
 * card button, twice, or on the tide-light entry, the copy slips into "upgrade" or "not rated", or a Pro, Admin or
 * flag-off reader sees any of it.
 */

const TODAY = '2026-08-09';
const COAST_LINE = /None of your sub-regions are on the coast\. These days open the map with coastal spots shown outside your sub-regions\./;
const DARK_LINE = /None of your sub-regions are dark-sky sites\. These nights open the map with dark-sky spots shown outside your sub-regions\./;

const LOCATIONS = [
  { id: 1, name: 'Bamburgh', locationType: ['SEASCAPE'], bortleClass: 3 },
  { id: 2, name: 'Saltburn', locationType: ['SEASCAPE', 'LANDSCAPE'], bortleClass: 5 },
  { id: 3, name: 'Malham', locationType: ['LANDSCAPE'], bortleClass: 4 },
  { id: 4, name: 'Hardraw', locationType: ['WATERFALL'], bortleClass: null, tideType: ['HIGH'] },
  { id: 5, name: 'Whitby', locationType: ['SEASCAPE'], bortleClass: 6 },
];

const entry = (id, kind, start, over = {}) => ({
  id, type: kind === 'coastal-spots' ? 'spring-tide' : 'meteor', startDate: start, endDate: start, kind: 'ALMANAC',
  family: kind === 'coastal-spots' ? 'coastal' : 'night-sky', title: `Entry ${id}`, kindTag: 'Almanac',
  superlative: null, metric: null, prose: null, facts: [], threshold: null,
  action: { label: 'Show spots →', kind, date: start }, ...over,
});

const COUNTS = { fixed: 4, forecast: 0, byFamily: { coastal: 2, 'night-sky': 2 } };

function renderPane(entries, { briefing = {}, status = OPEN, locations = LOCATIONS } = {}) {
  mockBriefing = { tideLocked: false, allowedLocationIds: null, ...briefing };
  getSubRegions.mockResolvedValue(status);
  const onOpen = vi.fn();
  const view = render(
    <SubRegionStatusProvider enabled={Array.isArray(mockBriefing.allowedLocationIds)} onOpen={onOpen}>
      <WindowFirstComingUp
        id="p"
        labelledBy="t"
        status="ready"
        events={{ entries, counts: COUNTS }}
        todayStr={TODAY}
        onRetry={vi.fn()}
        onGoToPlan={vi.fn()}
        onShowOnMap={vi.fn()}
        onMarkSeen={vi.fn()}
        locations={locations}
      />
    </SubRegionStatusProvider>,
  );
  return { ...view, onOpen };
}

const COAST = [entry('c1', 'coastal-spots', '2026-08-12'), entry('c2', 'coastal-spots', '2026-08-20')];
const DARK = [entry('d1', 'dark-sky-spots', '2026-08-13'), entry('d2', 'dark-sky-spots', '2026-08-22')];

beforeEach(() => { vi.clearAllMocks(); });

describe('the shared predicates (item 10)', () => {
  it('coastal is SEASCAPE in locationType, never tideType', () => {
    expect(isCoastalLocation({ locationType: ['LANDSCAPE', 'SEASCAPE'] })).toBe(true);
    expect(isCoastalLocation({ locationType: ['LANDSCAPE'], tideType: ['HIGH'] })).toBe(false);
    expect(isCoastalLocation({})).toBe(false);
  });

  it('dark sky is Bortle 4 or darker, and no Bortle is not dark', () => {
    expect(isDarkSkyLocation({ bortleClass: 4 })).toBe(true);
    expect(isDarkSkyLocation({ bortleClass: 5 })).toBe(false);
    expect(isDarkSkyLocation({ bortleClass: null })).toBe(false);
  });

  it('counts the roster and the allowed places by those predicates; null when unshaped', () => {
    expect(comingUpOutsideFacts(LOCATIONS, null)).toBeNull();
    expect(comingUpOutsideFacts(LOCATIONS, undefined)).toBeNull();
    const facts = comingUpOutsideFacts(LOCATIONS, [2, 3]);
    expect(facts.coastal).toEqual({ roster: 3, allowed: 1 });
    expect(facts.darkSky).toEqual({ roster: 2, allowed: 1 });
    expect(facts.zeroPicks).toBe(false);
    expect(comingUpOutsideFacts(LOCATIONS, []).zeroPicks).toBe(true);
  });

  it('says nothing about a roster that has not loaded', () => {
    expect(outsideLineText('coastal', comingUpOutsideFacts([], [1]))).toBeNull();
  });
});

describe('Coming up — the line under the door row (item 11)', () => {
  it('shows the coastal line when no allowed place is coastal, and the dark-sky line likewise', async () => {
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [4] } });
    const lines = screen.getAllByTestId('coming-up-outside-line');
    expect(lines).toHaveLength(4);
    expect(lines[0]).toHaveTextContent(/None of your sub-regions are on the coast. This day opens the map with coastal spots shown outside your sub-regions./);
    expect(lines[2]).toHaveTextContent(/None of your sub-regions are dark-sky sites. This night opens/);
  });

  it('shows no coastal line when an allowed place is coastal, and no dark-sky line when one is dark', () => {
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [1] } });
    expect(screen.queryByText(COAST_LINE)).toBeNull();
    expect(screen.queryByText(DARK_LINE)).toBeNull();
    expect(screen.queryAllByTestId('coming-up-outside-line')).toHaveLength(0);
  });

  it('keeps the two subjects independent', () => {
    // Malham is dark (Bortle 4) but not coastal.
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [3] } });
    const lines = screen.getAllByTestId('coming-up-outside-line');
    expect(lines).toHaveLength(2);
    lines.forEach((l) => expect(l).toHaveTextContent(/None of your sub-regions are on the coast\./));
  });

  it('says to choose sub-regions when the reader holds none, for both subjects', () => {
    renderPane([COAST[0], DARK[0]], { briefing: { allowedLocationIds: [] }, status: FIRST_CHOICE });
    const lines = screen.getAllByTestId('coming-up-outside-line');
    expect(lines[0]).toHaveTextContent('Choose your sub-regions in Settings to see ratings at coastal spots.');
    expect(lines[1]).toHaveTextContent('Choose your sub-regions in Settings to see ratings at dark-sky spots.');
  });

  it('keeps the chips live under the line, and the line is inside the card, after the chips', () => {
    renderPane([COAST[0]], { briefing: { allowedLocationIds: [3] } });
    const card = screen.getByTestId('coming-up-card');
    const chips = within(card).getByTestId('coming-up-day-chips');
    const line = within(card).getByTestId('coming-up-outside-line');
    expect(chips.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(chips).getByTestId('coming-up-day-chip')).toBeEnabled();
    expect(line).toHaveTextContent(/This day opens/);
  });

  it('keeps the single map door live under the line for a run over the chip cap', () => {
    renderPane([entry('c1', 'coastal-spots', '2026-08-12', { endDate: '2026-09-30' })], {
      briefing: { allowedLocationIds: [3] },
    });
    const card = screen.getByTestId('coming-up-card');
    expect(card.tagName).toBe('BUTTON');
    expect(within(card).getByTestId('coming-up-action')).toBeInTheDocument();
    const line = within(card).getByTestId('coming-up-outside-line');
    expect(line).toHaveTextContent(COAST_LINE);
    fireEvent.click(card);
  });

  it('draws no line on an entry with no coastal or dark-sky door', () => {
    renderPane([entry('p1', 'plan', '2026-08-12')], { briefing: { allowedLocationIds: [3] } });
    expect(screen.queryByTestId('coming-up-outside-line')).toBeNull();
    expect(screen.queryByTestId('coming-up-subregion-door')).toBeNull();
  });

  it('never says upgrade, not rated or not scored', () => {
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [3] } });
    expect(screen.getByTestId('window-first-coming-up').textContent).not.toMatch(/upgrade|not rated|not scored/i);
  });
});

describe('Coming up — the sub-regions door (item 12)', () => {
  it('draws exactly one door, a sibling after the first entry card, with the S4 status line and action', async () => {
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [3] } });
    expect(screen.getAllByTestId('coming-up-subregion-door')).toHaveLength(1);
    const rows = screen.getAllByTestId('coming-up-entry');
    const door = within(rows[0]).getByTestId('coming-up-subregion-door');
    expect(door.previousElementSibling).toBe(within(rows[0]).getByTestId('coming-up-card'));
    expect(within(rows[0]).getByTestId('coming-up-card').contains(door)).toBe(false);
    expect(await within(door).findByTestId('coming-up-subregion-status-line')).toBeInTheDocument();
    expect(within(door).getByTestId('coming-up-subregion-action')).toHaveTextContent('Change your sub-regions');
    expect(within(rows[1]).queryByTestId('coming-up-subregion-door')).toBeNull();
  });

  it('puts the door outside the card button for an over-cap, button-card entry', () => {
    renderPane([entry('c1', 'coastal-spots', '2026-08-12', { endDate: '2026-09-30' })], {
      briefing: { allowedLocationIds: [3] },
    });
    const card = screen.getByTestId('coming-up-card');
    const door = screen.getByTestId('coming-up-subregion-door');
    expect(card.tagName).toBe('BUTTON');
    expect(card.contains(door)).toBe(false);
    expect(door.parentElement).toBe(screen.getByTestId('coming-up-entry'));
  });

  it('skips the first entry that shows the line only if it carries the tide-light door', () => {
    const entries = [
      entry('c1', 'coastal-spots', '2026-08-12', { lightRowsWithheld: true }),
      entry('c2', 'coastal-spots', '2026-08-20'),
    ];
    renderPane(entries, { briefing: { tideLocked: true, allowedLocationIds: [3] } });
    const rows = screen.getAllByTestId('coming-up-entry');
    expect(within(rows[0]).getByTestId('coming-up-tide-light-upsell-door')).toBeInTheDocument();
    expect(within(rows[0]).queryByTestId('coming-up-subregion-door')).toBeNull();
    expect(within(rows[1]).getByTestId('coming-up-subregion-door')).toBeInTheDocument();
    // The skipped entry still carries the line.
    expect(within(rows[0]).getByTestId('coming-up-outside-line')).toBeInTheDocument();
  });

  it('lets the line stand alone when the only entry carries the tide-light door', () => {
    renderPane([entry('c1', 'coastal-spots', '2026-08-12', { lightRowsWithheld: true })], {
      briefing: { tideLocked: true, allowedLocationIds: [3] },
    });
    expect(screen.getByTestId('coming-up-outside-line')).toBeInTheDocument();
    expect(screen.queryByTestId('coming-up-subregion-door')).toBeNull();
  });

  it('names the coastal door with the roster count', () => {
    renderPane(COAST, { briefing: { allowedLocationIds: [3] } });
    const trigger = screen.getByTestId('coming-up-subregions-coast-upsell-trigger');
    expect(trigger).toHaveTextContent('Coastal spots everywhere');
    expect(within(trigger).getByText('Pro')).toBeInTheDocument();
    fireEvent.click(trigger);
    expect(screen.getByTestId('coming-up-subregions-coast-upsell-heading')).toHaveTextContent('Coastal spots everywhere');
    expect(screen.getByTestId('coming-up-subregions-coast-upsell-body')).toHaveTextContent(
      'Pro rates all 3 coastal spots, Berwick to Bridlington, on every morning and evening of the run.',
    );
  });

  it('names the dark-sky door with the roster count', () => {
    renderPane(DARK, { briefing: { allowedLocationIds: [2] } });
    const trigger = screen.getByTestId('coming-up-subregions-darksky-upsell-trigger');
    expect(trigger).toHaveTextContent('Dark-sky spots everywhere');
    fireEvent.click(trigger);
    expect(screen.getByTestId('coming-up-subregions-darksky-upsell-body')).toHaveTextContent(
      'Pro rates all 2 dark-sky spots, in every sub-region, on every night of the run.',
    );
  });

  it('uses the singular for a roster of one', () => {
    renderPane(COAST, {
      briefing: { allowedLocationIds: [3] },
      locations: [LOCATIONS[0], LOCATIONS[2]],
    });
    fireEvent.click(screen.getByTestId('coming-up-subregions-coast-upsell-trigger'));
    const body = screen.getByTestId('coming-up-subregions-coast-upsell-body');
    expect(body).toHaveTextContent('Pro rates the coastal spot on every morning and evening of the run.');
    expect(body.textContent).not.toMatch(/1 coastal spots/);
  });

  it('opens a non-modal dialog with no link, no upgrade, and Escape closes the door only', () => {
    renderPane(COAST, { briefing: { allowedLocationIds: [3] } });
    const trigger = screen.getByTestId('coming-up-subregions-coast-upsell-trigger');
    fireEvent.click(trigger);
    const pop = screen.getByTestId('coming-up-subregions-coast-upsell-popover');
    expect(pop).toHaveAttribute('role', 'dialog');
    expect(pop).not.toHaveAttribute('aria-modal');
    expect(pop.querySelector('a')).toBeNull();
    expect(pop.textContent).not.toMatch(/upgrade/i);
    const outer = vi.fn();
    document.addEventListener('keydown', outer);
    fireEvent.keyDown(pop, { key: 'Escape' });
    document.removeEventListener('keydown', outer);
    expect(outer).not.toHaveBeenCalled();
    expect(screen.queryByTestId('coming-up-subregions-coast-upsell-popover')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(screen.getByTestId('window-first-coming-up')).toBeInTheDocument();
  });

  it('opens Settings through the status provider, and opens no dialog of its own', async () => {
    const { onOpen } = renderPane(COAST, { briefing: { allowedLocationIds: [3] } });
    const action = await screen.findByTestId('coming-up-subregion-action');
    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    fireEvent.click(action);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
  });

  it('offers no change button at the limit, only the line that says when', async () => {
    renderPane(COAST, { briefing: { allowedLocationIds: [3] }, status: LOCKED_FULL });
    expect(await screen.findByTestId('coming-up-subregion-status-line')).toHaveTextContent(/25 November/);
    expect(screen.queryByTestId('coming-up-subregion-action')).toBeNull();
  });

  it('gives a reader with no picks the Choose button only', async () => {
    renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds: [] }, status: FIRST_CHOICE });
    const door = screen.getByTestId('coming-up-subregion-door');
    expect(await within(door).findByTestId('coming-up-subregion-action')).toHaveTextContent('Choose your sub-regions');
    expect(within(door).queryByTestId('coming-up-subregion-status-line')).toBeNull();
    expect(screen.queryByTestId('coming-up-subregions-coast-upsell-door')).toBeNull();
    expect(screen.getAllByTestId('coming-up-subregion-door')).toHaveLength(1);
  });
});

describe('Coming up — unshaped bodies are unchanged', () => {
  it.each([
    ['Pro, Admin or flag off (allowedLocationIds null)', null],
    ['no list served at all', undefined],
  ])('draws no line and no door for %s', (_name, allowedLocationIds) => {
    const { container } = renderPane([...COAST, ...DARK], { briefing: { allowedLocationIds } });
    expect(screen.queryByTestId('coming-up-outside-line')).toBeNull();
    expect(screen.queryByTestId('coming-up-subregion-door')).toBeNull();
    expect(container.querySelector('.wf-cu-outside, .wf-cu-subregion-door')).toBeNull();
    expect(getSubRegions).not.toHaveBeenCalled();
  });

  it('renders the same markup with or without the roster for an unshaped body', () => {
    const norm = (html) => html.replace(/_r_[0-9a-z]+_/g, '_id_');
    const a = norm(renderPane([...COAST, ...DARK]).container.innerHTML);
    document.body.innerHTML = '';
    const b = norm(renderPane([...COAST, ...DARK], { locations: undefined }).container.innerHTML);
    expect(b).toBe(a);
  });
});
