import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import WindowTopicRows from '../components/WindowTopicRows.jsx';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { windowKey } from '../utils/heatSpots.js';

/**
 * The popup's topic rows.
 *
 * <h2>⚠️ Every fixture here goes through the REAL join</h2>
 *
 * <p>The rows are built by {@code windowTopics} — the single implementation of plan-matrix A8's two
 * rules (M1) — and this file drives that function rather than hand-writing its output. That is the
 * point: both rules fail in ways a naive fixture passes. A hand-written {@code {badge, topic}} pair
 * would render perfectly while the NIGHT bucketing lost aurora on its morning card and an
 * unexempted {@code regions} intersection deleted it from every away plan, because neither failure
 * is visible at the render layer at all.
 *
 * <p>{@code windowFirstTopics.test.js} owns the rules themselves, including the literal sixteen-type
 * mirror. What this file owns is that the component consumes them faithfully — including the two
 * end-to-end cases M2's own test list names.
 */

afterEach(cleanup);

const D = '2026-08-20';
const NEXT = '2026-08-21';

function badge(overrides = {}) {
  return {
    type: 'KING_TIDE', label: 'King tide', detail: 'HW 06:18 · 58m after sunrise', rarityRank: 4, ...overrides,
  };
}

function topic(overrides = {}) {
  return {
    type: 'KING_TIDE',
    label: 'King tide',
    date: D,
    eventType: 'SUNSET',
    detail: 'HW 06:18 · 58m after sunrise',
    description: 'A perigean spring tide — the moon at its closest approach.',
    regions: ['Northumberland & Tyneside'],
    rarityRank: 4,
    ...overrides,
  };
}

/** Renders the rows the real join produces for one window. */
function renderJoined({ key, badges, topics, scope }) {
  const rows = windowTopics(key, badges, buildTopicIndex(topics), scope);
  render(<WindowTopicRows rows={rows} />);
  return rows;
}

describe('WindowTopicRows — what a row says', () => {
  it('names the topic, its detail, and puts the science behind the i', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic()],
      scope: ['Northumberland & Tyneside'],
    });
    const row = screen.getByTestId('wf-topic-row');
    expect(within(row).getByTestId('wf-topic-row-name')).toHaveTextContent('King tide');
    expect(within(row).getByTestId('wf-topic-row-detail'))
      .toHaveTextContent('HW 06:18 · 58m after sunrise');
    // The science note is served on the full topic and deliberately absent from the slim badge —
    // the join is what makes it reachable, and `InfoTip` is what makes it reachable on touch.
    expect(within(row).getByRole('button', { name: /more info/i })).toBeInTheDocument();
  });

  it('carries the topic’s channel, which is what the left border hangs off', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic()],
      scope: ['Northumberland & Tyneside'],
    });
    expect(screen.getByTestId('wf-topic-row')).toHaveAttribute('data-channel', 'tide');
  });

  it('renders nothing at all for a window with no topics', () => {
    const { container } = render(<WindowTopicRows rows={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('WindowTopicRows — the join’s two rules, at the render layer', () => {
  it('⚠️ shows a NIGHT topic dated D on D+1’s SUNRISE popup, with its science note', () => {
    // A8 rule 1's hard case, end to end. A `topic.date === card.date` equality loses every NIGHT
    // topic — aurora, NLC, meteor, supermoon — from the morning card it also belongs to, and every
    // naive test passes because the EVENING card still joins.
    const aurora = badge({ type: 'AURORA', label: 'Aurora', detail: 'Kp 6 expected' });
    renderJoined({
      key: windowKey(NEXT, 'SUNRISE'),
      badges: [aurora],
      topics: [topic({
        type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', date: D, description: 'Kp 6 pushes the oval south.', regions: ['Northumberland & Tyneside'],
      })],
      scope: ['The Lake District'],
    });
    const row = screen.getByTestId('wf-topic-row');
    expect(within(row).getByTestId('wf-topic-row-name')).toHaveTextContent('Aurora');
    expect(within(row).getByRole('button', { name: /more info/i })).toBeInTheDocument();
  });

  it('⚠️ keeps a whole-sky topic whose served regions do not intersect an away scope', () => {
    // A8 rule 2. Aurora's `regions` is Bortle-enrichment COVERAGE, not an eligibility roster — so a
    // naive intersection deletes aurora from every away plan while every naive test passes. The
    // fixture's list is deliberately POPULATED and deliberately disjoint from the scope.
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge({ type: 'AURORA', label: 'Aurora' })],
      topics: [topic({ type: 'AURORA', label: 'Aurora', regions: ['Northumberland & Tyneside'] })],
      scope: ['The Lake District'],
    });
    expect(screen.getByTestId('wf-topic-row')).toHaveAttribute('data-whole-sky', 'true');
  });

  it('drops a region-scoped topic when the scope intersection empties', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic({ regions: ['Northumberland & Tyneside'] })],
      scope: ['The Lake District'],
    });
    expect(screen.queryByTestId('wf-topic-row')).toBeNull();
  });

  it('⚠️ renders a badge with no matching topic from its own fields, and no science i', () => {
    // The degrade path. A badge the client cannot join is KEPT — the backend put it on this window
    // and the client has no scope information to judge it by — so the row must still be drawable
    // from the badge alone.
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [],
      scope: ['Northumberland & Tyneside'],
    });
    const row = screen.getByTestId('wf-topic-row');
    expect(within(row).getByTestId('wf-topic-row-name')).toHaveTextContent('King tide');
    expect(within(row).getByTestId('wf-topic-row-detail')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /more info/i })).toBeNull();
  });
});

describe('WindowTopicRows — the scope note', () => {
  it('states how much of the scope a region-scoped topic is about', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic({ regions: ['Northumberland & Tyneside', 'N. York Moors & Coast'] })],
      scope: ['Northumberland & Tyneside', 'N. York Moors & Coast', 'The Lake District'],
    });
    expect(screen.getByTestId('wf-topic-row-scope')).toHaveTextContent('2 regions in scope');
  });

  it('agrees with itself for one region', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic({ regions: ['Northumberland & Tyneside'] })],
      scope: ['Northumberland & Tyneside', 'The Lake District'],
    });
    expect(screen.getByTestId('wf-topic-row-scope')).toHaveTextContent('1 region in scope');
  });

  it('⚠️ gives a whole-sky topic no scope note at all', () => {
    // Its `regions` means coverage or clear sky, not eligibility, so a count drawn from it would
    // read as an eligibility statement the payload never made.
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge({ type: 'AURORA', label: 'Aurora' })],
      topics: [topic({ type: 'AURORA', label: 'Aurora', regions: ['Northumberland & Tyneside'] })],
      scope: ['Northumberland & Tyneside', 'The Lake District'],
    });
    expect(screen.queryByTestId('wf-topic-row-scope')).toBeNull();
  });

  it('says nothing rather than zero when the scope is not known yet', () => {
    // An empty scope means the locations payload has not landed. A "0 regions in scope" there would
    // claim the topic is about nowhere — and the filter would already have acted on that.
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic()],
      scope: [],
    });
    expect(screen.getByTestId('wf-topic-row')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-topic-row-scope')).toBeNull();
  });
});

describe('WindowTopicRows — the safety note', () => {
  it('⚠️ renders it in full, on the badge that carries it', () => {
    // The "do not look at the sun without a filter" class of warning. It rides the BADGE, so it
    // survives a missing join, and nothing here may gate, dim or truncate it.
    const warning = 'Never look at the sun without a certified solar filter.';
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge({ type: 'ECLIPSE', label: 'Deep partial eclipse', safetyNote: warning })],
      topics: [],
      scope: ['Northumberland & Tyneside'],
    });
    expect(screen.getByTestId('wf-topic-row-safety')).toHaveTextContent(warning);
  });

  it('draws none where no badge carries one', () => {
    renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [badge()],
      topics: [topic()],
      scope: ['Northumberland & Tyneside'],
    });
    expect(screen.queryByTestId('wf-topic-row-safety')).toBeNull();
  });
});

describe('WindowTopicRows — ordering', () => {
  it('draws the rarest first, on the payload’s own rank', () => {
    const rows = renderJoined({
      key: windowKey(D, 'SUNSET'),
      badges: [
        badge({ type: 'SNOW_TOPS', label: 'Snow on the tops', rarityRank: 9 }),
        badge({ type: 'AURORA', label: 'Aurora', rarityRank: 2 }),
      ],
      topics: [],
      scope: ['Northumberland & Tyneside'],
    });
    expect(rows.map((r) => r.badge.label)).toEqual(['Aurora', 'Snow on the tops']);
    expect(screen.getAllByTestId('wf-topic-row-name').map((n) => n.textContent))
      .toEqual(['Aurora', 'Snow on the tops']);
  });
});
