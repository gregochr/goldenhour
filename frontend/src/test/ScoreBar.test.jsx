import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ScoreBar from '../components/ScoreBar.jsx';
import { rampHex, starFromScore } from '../utils/scoreRamp.js';

/**
 * jsdom normalises an inline `style.background`/`style.color` hex value it reads back to `rgb(r, g,
 * b)` — a jsdom quirk (real browsers keep whatever form you set), not a claim this app makes about
 * colour representation. Assertions below compare through this so a real ramp-colour match isn't
 * reported as a mismatch of string FORMAT.
 */
function toRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255})`;
}

/**
 * Stage 5b of heat-scale-unification-plan.md: `ScoreBar` merges the Plan pane's old `PlanScoreBar`
 * and the map popup's old `PopupScoreRow`. `WindowSpotPeek.test.jsx` and `LocationFourDaySheet.test.jsx`
 * already pin the Plan-side behaviour that must not move (label text, `data-score`, the `100 - pct`
 * rest-width, the null branch); this file is the component's own unit test, covering what the merge
 * itself introduces — the ramp-derived fill on both markup modes, the two null behaviours as a single
 * component's contract rather than two components', the invalid-metric throw, and the render-side
 * "two surfaces, one truth" sibling `planScoreConsistency.test.js`'s own module doc invites.
 */

describe('ScoreBar — the fill', () => {
  it('is the ramp\'s colour for the score, not a hard-coded hex or a gradient', () => {
    render(<ScoreBar label="Fiery Sky" score={68} metric="fiery" testId="bar" dense />);
    const track = screen.getByTestId('bar').querySelector('.wf-peek-bar');
    expect(track.style.background).toBe(toRgb(rampHex(starFromScore(68, 'fiery'))));
  });

  it('reads a different colour for the same score under the other metric', () => {
    // fiery and golden disagree at the same input (5a's own module doc gives 80 as the example: fiery
    // 4.43★, golden 4.33★) — proves `metric` is actually wired to `starFromScore`, not defaulted or
    // ignored, and isn't just checked against a value where the two tables happen to coincide (they
    // do at 50, both landing on 2.8★ — not a case this test should rely on).
    render(
      <>
        <ScoreBar label="Fiery Sky" score={80} metric="fiery" testId="fiery-bar" dense />
        <ScoreBar label="Golden Hour" score={80} metric="golden" testId="golden-bar" dense />
      </>,
    );
    const fieryFill = screen.getByTestId('fiery-bar').querySelector('.wf-peek-bar').style.background;
    const goldenFill = screen.getByTestId('golden-bar').querySelector('.wf-peek-bar').style.background;
    expect(fieryFill).toBe(toRgb(rampHex(starFromScore(80, 'fiery'))));
    expect(goldenFill).toBe(toRgb(rampHex(starFromScore(80, 'golden'))));
    expect(fieryFill).not.toBe(goldenFill);
  });

  it('clamps a value outside 0–100 before sampling the ramp, matching the track\'s own clamp', () => {
    render(<ScoreBar label="Fiery Sky" score={140} metric="fiery" testId="bar" dense />);
    const track = screen.getByTestId('bar').querySelector('.wf-peek-bar');
    expect(track.style.background).toBe(toRgb(rampHex(starFromScore(100, 'fiery'))));
  });
});

describe('ScoreBar — an invalid metric', () => {
  it('throws rather than rendering a plausible wrong colour', () => {
    // starFromScore's own guard (5a) — this pins that ScoreBar actually calls it with the caller's
    // metric rather than swallowing or defaulting it.
    expect(() => render(<ScoreBar label="Fiery Sky" score={50} metric="bogus" testId="bar" dense />))
      .toThrow(/unknown metric/);
  });

  it('⚠️ throws even when score is null — an adversarial review finding', () => {
    // The metric validation must not be gated behind `score != null`: a typo'd metric on a slot that
    // has not been scored yet would otherwise pass silently and only surface once that same call
    // site later receives a real score, far from where the typo was introduced.
    expect(() => render(<ScoreBar label="Fiery Sky" score={null} metric="bogus" testId="bar" />))
      .toThrow(/unknown metric/);
  });
});

describe('ScoreBar — the two null behaviours', () => {
  it('non-dense (popup) mode renders an em dash for a null score, not a vanished row', () => {
    render(<ScoreBar label="Fiery Sky" score={null} metric="fiery" testId="bar" />);
    expect(screen.getByTestId('bar')).toHaveTextContent('—');
  });

  it('non-dense mode masks the whole track for a null score (fully covered, not the fill colour)', () => {
    render(<ScoreBar label="Fiery Sky" score={null} metric="fiery" testId="bar" />);
    // The outermost measured child after the label row is the track; its masking span covers 100%.
    const track = screen.getByTestId('bar').lastChild;
    const mask = track.querySelector('div');
    expect(mask.style.width).toBe('100%');
  });

  it('dense mode does not crash on a null score (Plan callers guard upstream, but the component degrades rather than throwing)', () => {
    // Plan callers wrap every ScoreBar in `score != null &&`, so this state is unreachable in
    // production — but the component itself must not be the thing that breaks if a caller ever
    // forgets the guard.
    expect(() => render(<ScoreBar label="Fiery Sky" score={null} metric="fiery" testId="bar" dense />))
      .not.toThrow();
  });
});

describe('ScoreBar — dense vs popup markup', () => {
  it('dense mode renders the Plan pane\'s `.wf-peek-bar` markup with data-score and the rest-width contract', () => {
    render(<ScoreBar label="Fiery Sky" score={68} metric="fiery" testId="bar" dense />);
    const bar = screen.getByTestId('bar');
    expect(bar).toHaveAttribute('data-score', '68');
    expect(bar.querySelector('.wf-peek-bar-rest').style.width).toBe('32%');
    expect(bar).toHaveTextContent('Fiery Sky');
    expect(bar).toHaveTextContent('68');
  });

  it('non-dense (popup) mode carries no data-score attribute and uses the 11px inline-style markup', () => {
    render(<ScoreBar label="Fiery Sky" score={68} metric="fiery" testId="bar" />);
    const bar = screen.getByTestId('bar');
    expect(bar).not.toHaveAttribute('data-score');
    expect(bar.querySelector('.wf-peek-bar')).toBeNull();
  });

  it('passes tooltip through only in popup mode\'s markup — Plan callers pass none and dense mode never renders it', () => {
    render(
      <ScoreBar label="Fiery Sky" score={68} metric="fiery" testId="bar" tooltip={<span data-testid="tip">i</span>} />,
    );
    expect(screen.getByTestId('tip')).toBeInTheDocument();
  });

  it('applies labelClassName only in dense mode', () => {
    render(<ScoreBar label="Fiery Sky" score={68} metric="fiery" testId="bar" dense labelClassName="wf-loc-score-label" />);
    const bar = screen.getByTestId('bar');
    expect(bar.querySelector('.wf-loc-score-label')).toBeInTheDocument();
  });
});

describe('ScoreBar — the number is deliberately NOT tinted', () => {
  // Stage 5b tinted the number from the ramp, floored so it cleared AA as text. Design removed the
  // tint entirely (plan Stage 7): a ramp is a fill scale and cannot double as a text scale, and the
  // tint was a third encoding of a datum the bar already carries twice. This pins the absence, so a
  // future "tint it to match the fill" cannot quietly come back — it reads as the obvious
  // improvement right up until you measure it.
  it('paints the number from a text token, never from the ramp', () => {
    render(<ScoreBar label="Fiery Sky" score={95} metric="fiery" testId="t" />);
    const number = screen.getByTestId('t').querySelector('span[style*="font-weight"]');
    const colour = number.getAttribute('style') ?? '';
    expect(colour).toContain('--color-plex-text');
    expect(colour).not.toMatch(/#[0-9a-fA-F]{6}/);
  });

  it('does not vary the number\'s colour with the score', () => {
    const { unmount } = render(<ScoreBar label="Fiery Sky" score={5} metric="fiery" testId="lo" />);
    const lo = screen.getByTestId('lo').querySelector('span[style*="font-weight"]').getAttribute('style');
    unmount();
    render(<ScoreBar label="Fiery Sky" score={95} metric="fiery" testId="hi" />);
    const hi = screen.getByTestId('hi').querySelector('span[style*="font-weight"]').getAttribute('style');
    expect(lo).toBe(hi);
  });
});

describe('ScoreBar — one truth across surfaces (render-side sibling of planScoreConsistency.test.js)', () => {
  it('the same score and metric produce the identical bar fill in both dense and popup mode', () => {
    // `planScoreConsistency.test.js` pins that the peek and the sheet DATA reductions agree; this is
    // the render-side half — the two surfaces must never show the same measurement in two colours.
    render(
      <>
        <ScoreBar label="Golden Hour" score={73} metric="golden" testId="dense-bar" dense />
        <ScoreBar label="Golden Hour" score={73} metric="golden" testId="popup-bar" />
      </>,
    );
    const denseFill = screen.getByTestId('dense-bar').querySelector('.wf-peek-bar').style.background;
    const popupFill = screen.getByTestId('popup-bar').lastElementChild.style.background;
    expect(denseFill).toBe(toRgb(rampHex(starFromScore(73, 'golden'))));
    expect(popupFill).toBe(toRgb(rampHex(starFromScore(73, 'golden'))));
    expect(denseFill).toBe(popupFill);
  });
});
