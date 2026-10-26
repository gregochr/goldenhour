import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import WindowTopicRows from '../components/WindowTopicRows.jsx';
import { buildTopicIndex, windowTopics } from '../utils/windowFirstTopics.js';
import { windowKey } from '../utils/windowKeys.js';

let mockTideLocked = true;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => ({ tideLocked: mockTideLocked }),
}));

afterEach(cleanup);

/**
 * tide-light-pro-plan.md F1: a Lite window never shows the OTHER window's alignment sentence.
 *
 * <p>The topic is one object shared by both of a spring run's windows, so its {@code detail} is the
 * aligned window's. The row used to fall back to it when the badge carried no detail; for the tide
 * types that fallback is off while the tide is locked. Rows come through the real join.
 */

const D = '2026-08-20';
const KEY = windowKey(D, 'SUNSET');

function rowsFor(type, badgeDetail) {
  const topic = {
    type, label: type === 'KING_TIDE' ? 'King tide' : 'Spring tide', date: D, eventType: 'SUNRISE',
    detail: 'tide aligned with sunrise at 47 of 61 coastal locations',
    description: 'A big tide.', regions: ['North East'], rarityRank: 4,
  };
  const badge = { type, label: topic.label, detail: badgeDetail, rarityRank: 4 };
  return windowTopics(KEY, [badge], buildTopicIndex([topic]), undefined);
}

describe.each(['KING_TIDE', 'SPRING_TIDE'])('WindowTopicRows — %s badge with no detail of its own', (type) => {
  it('does not fall back to the topic sentence when the tide is locked (Lite)', () => {
    mockTideLocked = true;
    render(<WindowTopicRows rows={rowsFor(type, null)} />);
    expect(screen.getByTestId('wf-topic-row')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-topic-row-detail')).toBeNull();
    expect(document.body.textContent).not.toMatch(/aligned with sunrise/);
  });

  it.each([['PRO', false], ['ADMIN', false]])('keeps the fallback for %s (positive control)', (_role, locked) => {
    mockTideLocked = locked;
    render(<WindowTopicRows rows={rowsFor(type, null)} />);
    expect(screen.getByTestId('wf-topic-row-detail'))
      .toHaveTextContent('tide aligned with sunrise at 47 of 61 coastal locations');
  });

  it('still shows the badge\'s own detail to a locked reader', () => {
    mockTideLocked = true;
    render(<WindowTopicRows rows={rowsFor(type, '12 coastal locations')} />);
    expect(screen.getByTestId('wf-topic-row-detail')).toHaveTextContent('12 coastal locations');
  });
});

describe('WindowTopicRows — a non-tide badge keeps its fallback for a locked reader', () => {
  it('falls back to the topic detail for an inversion', () => {
    mockTideLocked = true;
    const topic = {
      type: 'CLOUD_INVERSION', label: 'Cloud inversion', date: D, eventType: 'SUNSET',
      detail: 'Cloud inversion likely', description: 'x', regions: ['North East'], rarityRank: 3,
    };
    const badge = { type: 'CLOUD_INVERSION', label: 'Cloud inversion', detail: null, rarityRank: 3 };
    render(<WindowTopicRows rows={windowTopics(KEY, [badge], buildTopicIndex([topic]), undefined)} />);
    expect(screen.getByTestId('wf-topic-row-detail')).toHaveTextContent('Cloud inversion likely');
  });
});
