import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowRegionBand from '../components/WindowRegionBand.jsx';
import { rampHex } from '../utils/scoreRamp.js';

/**
 * The open row's region drill-down band.
 *
 * <p>Two of its rules are the ones a refactor drops first, so both get named tests: the narrative is
 * the payload's own sentence <b>verbatim</b>, and its absence is a <b>stated</b> state rather than
 * an empty row. A reader cannot tell blankness apart from prose that failed to load, which is why
 * the design specifies a line for it.
 */

const WINDOWS = [
  { key: '2026-08-04:SUNSET', dow: 'Tue', sunrise: false, label: 'Tonight Sunset', time: '21:11' },
  { key: '2026-08-05:SUNRISE', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20' },
  { key: '2026-08-05:SUNSET', dow: 'Wed', sunrise: false, label: 'Tomorrow sunset', time: '21:09' },
];

const NARRATIVE = 'A clean eastern horizon with high cloud to catch the light.';

function row(overrides = {}) {
  return {
    name: 'Northumberland & Tyneside',
    verdict: 'WORTH_IT',
    summary: NARRATIVE,
    bestRating: 5,
    ...overrides,
  };
}

/** One figure by its own `data-fig`, so an assertion cannot land on the container holding all three. */
function figure(kind) {
  return screen.getAllByTestId('wf-region-band-fig').find((n) => n.getAttribute('data-fig') === kind);
}

function renderBand(props = {}) {
  const onClear = vi.fn();
  const onJumpWindow = vi.fn();
  render(
    <WindowRegionBand
      row={row()}
      windowKey="2026-08-04:SUNSET"
      windows={WINDOWS}
      series={new Map([
        ['2026-08-04:SUNSET', 3.2],
        ['2026-08-05:SUNRISE', 4.6],
        ['2026-08-05:SUNSET', 2.0],
      ])}
      filters={['Northumberland & Tyneside', '4★+', 'within 2h 30min']}
      atFloor={null}
      withinTier={null}
      onClear={onClear}
      onJumpWindow={onJumpWindow}
      {...props}
    />,
  );
  return { onClear, onJumpWindow };
}

describe('WindowRegionBand — the narrative', () => {
  it('renders the served summary verbatim, unshortened', () => {
    renderBand();
    expect(screen.getByTestId('wf-region-band-narrative')).toHaveTextContent(NARRATIVE);
  });

  it('states the absence rather than leaving the row empty', () => {
    // An AWAITING region can legitimately carry no summary. An empty row reads as a rendering
    // failure and a reader cannot tell it apart from prose that did not load.
    renderBand({ row: row({ summary: null }) });
    expect(screen.queryByTestId('wf-region-band-narrative')).toBeNull();
    expect(screen.getByTestId('wf-region-band-none'))
      .toHaveTextContent('No narrative generated for this window.');
  });

  it('points at the region’s own best window when a different one is better', () => {
    // The design's second clause: it turns "nothing to say about tonight" into a route to the night
    // there is something to say about.
    renderBand({ row: row({ summary: null }) });
    expect(screen.getByTestId('wf-region-band-none'))
      .toHaveTextContent('This region’s own best is tomorrow sunrise 05:20.');
  });

  it('points at no other window when THIS one is the region’s best', () => {
    // A sentence saying "its own best is tonight" on the row the reader already has open points at
    // itself.
    renderBand({
      row: row({ summary: null }),
      series: new Map([['2026-08-04:SUNSET', 4.9], ['2026-08-05:SUNRISE', 1.1]]),
    });
    const none = screen.getByTestId('wf-region-band-none');
    expect(none).toHaveTextContent('No narrative generated for this window.');
    expect(none.textContent).not.toContain('own best');
  });

  it('points at no other window when the region is scored in none of them', () => {
    renderBand({ row: row({ summary: null }), series: new Map() });
    expect(screen.getByTestId('wf-region-band-none').textContent).not.toContain('own best');
  });
});

describe('WindowRegionBand — the figures', () => {
  it('states the served best in field, and never a maximum of what is drawn', () => {
    renderBand({ row: row({ bestRating: 4 }) });
    // The FIGURE, not its container: `getByText` matches the figure span and `.parentElement` is
    // `wf-region-band-figures`, which holds all three — and `toHaveTextContent` is a substring
    // match, so multiplying a value by 11 passed against the concatenated row.
    expect(screen.getByTestId('wf-region-band-figures')).toBeInTheDocument();
    expect(figure('best')).toHaveTextContent('best in field4★');
  });

  it('omits the best figure when nothing in the region is rated', () => {
    renderBand({ row: row({ bestRating: null }) });
    expect(screen.queryByText('best in field')).toBeNull();
  });

  it('states the rating figure as M of N over everything the region holds', () => {
    renderBand({ atFloor: { label: '4★+', count: 2, total: 9 } });
    expect(figure('rating')).toHaveTextContent('at 4★+2 of 9');
  });

  it('states the reach figure as the count actually drawn', () => {
    renderBand({ withinTier: { label: '2h 30min', count: 6 } });
    expect(figure('reach')).toHaveTextContent('within 2h 30min6');
  });

  it('renders neither lens figure when neither control gates anything', () => {
    // "at any rating, 12 of 12" is a figure about a control that filtered nothing — the class of
    // number §6's sweep removed from four other surfaces on this arm.
    renderBand({ atFloor: null, withinTier: null });
    const figs = screen.getAllByTestId('wf-region-band-fig');
    expect(figs).toHaveLength(1);
    expect(figs[0]).toHaveAttribute('data-fig', 'best');
  });
});

describe('WindowRegionBand — the six-dot window strip', () => {
  it('draws one dot per rendered window, in chronological order', () => {
    renderBand();
    const dots = screen.getAllByTestId('wf-region-band-dot');
    expect(dots.map((d) => d.getAttribute('data-window')))
      .toEqual(['2026-08-04:SUNSET', '2026-08-05:SUNRISE', '2026-08-05:SUNSET']);
  });

  it('names each dot with its visible weekday, its event and this region’s own mean', () => {
    renderBand();
    expect(screen.getByRole('button', {
      name: 'Wed sunrise, Northumberland & Tyneside 4.6★',
    })).toBeInTheDocument();
  });

  it('names an unscored window as not scored, rather than as a zero', () => {
    renderBand({ series: new Map([['2026-08-04:SUNSET', 3.2]]) });
    expect(screen.getByRole('button', {
      name: 'Wed sunrise, Northumberland & Tyneside not scored',
    })).toBeInTheDocument();
  });

  it('gives an unscored dot no colour at all, because a red bar would say "poor"', () => {
    // The kernel's "non-finite resolves to the ramp's bottom" rule is about a FIELD that must paint
    // something; here a dark-red bar would say "poor" about a night nobody has looked at.
    renderBand({ series: new Map([['2026-08-04:SUNSET', 3.2]]) });
    const swatches = screen.getAllByTestId('wf-region-band-swatch');
    expect(swatches[0]).not.toHaveAttribute('data-unscored');
    expect(swatches[1]).toHaveAttribute('data-unscored', 'true');
    expect(swatches[1]).not.toHaveStyle({ background: rampHex(1) });
  });

  it('marks the window the region does best in, and marks only one', () => {
    renderBand();
    const dots = screen.getAllByTestId('wf-region-band-dot');
    expect(dots.filter((d) => d.textContent.includes('◎'))).toHaveLength(1);
    expect(dots[1].textContent).toContain('◎');
  });

  it('marks nothing when the region is scored in no window at all', () => {
    // A mark on an unscored window would recommend a night nobody has evaluated.
    renderBand({ series: new Map() });
    expect(screen.getAllByTestId('wf-region-band-dot')
      .filter((d) => d.textContent.includes('◎'))).toHaveLength(0);
  });

  it('marks the EARLIER of two equal windows, so the mark never appears to jump back', () => {
    renderBand({
      series: new Map([['2026-08-04:SUNSET', 4.0], ['2026-08-05:SUNRISE', 4.0]]),
    });
    const dots = screen.getAllByTestId('wf-region-band-dot');
    expect(dots[0].textContent).toContain('◎');
    expect(dots[1].textContent).not.toContain('◎');
  });

  it('marks the open window as current', () => {
    renderBand();
    expect(screen.getAllByTestId('wf-region-band-dot')[0])
      .toHaveAttribute('aria-current', 'true');
    expect(screen.getAllByTestId('wf-region-band-dot')[1]).not.toHaveAttribute('aria-current');
  });

  it('jumps to the window its dot names', () => {
    const { onJumpWindow } = renderBand();
    fireEvent.click(screen.getAllByTestId('wf-region-band-dot')[2]);
    expect(onJumpWindow).toHaveBeenCalledWith('2026-08-05:SUNSET');
  });
});

describe('WindowRegionBand — an away window', () => {
  const WITH_AWAY = [
    WINDOWS[0],
    { ...WINDOWS[1], away: true },
    WINDOWS[2],
  ];

  it('keeps its slot but is not a control, because there is no card to open', () => {
    // P2 made the strip's away thumbnail a `<div>` for exactly this reason: `revealWindow` finds no
    // element for a travel day, so the press scrolls nothing and focuses nothing — a control with no
    // visible effect, which §6 bans.
    renderBand({ windows: WITH_AWAY });
    const dots = screen.getAllByTestId('wf-region-band-dot');
    expect(dots).toHaveLength(3);
    expect(dots[1]).toHaveAttribute('data-away', 'true');
    expect(dots[1].tagName).toBe('SPAN');
    expect(screen.getAllByRole('button')).toHaveLength(3); // 2 live dots + the clear control
  });

  it('never takes the peak mark, however the payload rates it', () => {
    // The pipeline skips evaluation on a travel day, so a ◎ there would recommend a night nobody
    // looked at — and the null-summary line would point the reader at it.
    renderBand({
      windows: WITH_AWAY,
      series: new Map([['2026-08-04:SUNSET', 3.0], ['2026-08-05:SUNRISE', 4.9]]),
    });
    const dots = screen.getAllByTestId('wf-region-band-dot');
    expect(dots[1].textContent).not.toContain('◎');
    expect(dots[0].textContent).toContain('◎');
  });

  it('never becomes the window the null-summary line points at', () => {
    renderBand({
      row: row({ summary: null }),
      windows: WITH_AWAY,
      series: new Map([['2026-08-05:SUNRISE', 4.9], ['2026-08-05:SUNSET', 2.0]]),
    });
    expect(screen.getByTestId('wf-region-band-none'))
      .toHaveTextContent('This region’s own best is tomorrow sunset 21:09.');
  });

  it('says it was not forecast rather than announcing a score', () => {
    renderBand({ windows: WITH_AWAY });
    expect(screen.getAllByTestId('wf-region-band-dot')[1])
      .toHaveTextContent('Wed sunrise, not forecast');
  });
});

describe('WindowRegionBand — the header', () => {
  it('names the region', () => {
    renderBand();
    expect(screen.getByTestId('wf-region-band-name'))
      .toHaveTextContent('Northumberland & Tyneside');
  });

  it('names every filter in force, in the words the footer uses', () => {
    renderBand();
    expect(screen.getByTestId('wf-region-band-filters'))
      .toHaveTextContent('Spots below · Northumberland & Tyneside · 4★+ · within 2h 30min');
  });

  it('renders no filter line when nothing is in force', () => {
    renderBand({ filters: [] });
    expect(screen.queryByTestId('wf-region-band-filters')).toBeNull();
  });

  it('offers a labelled route back to every region', () => {
    const { onClear } = renderBand();
    fireEvent.click(screen.getByRole('button', { name: 'Show all regions' }));
    expect(onClear).toHaveBeenCalled();
  });
});

describe('WindowRegionBand — the ramp', () => {
  it('uses scoreRamp for the swatch, exactly at a stop', () => {
    // A hand-written colour beside the canvas is a second copy that can drift with nothing failing —
    // the reason `scoreRamp.js` exists at all.
    renderBand({ series: new Map([['2026-08-04:SUNSET', 5]]) });
    expect(screen.getAllByTestId('wf-region-band-swatch')[0])
      .toHaveStyle({ background: rampHex(5) });
  });

  it('interpolates between stops rather than snapping to one', () => {
    renderBand({ series: new Map([['2026-08-04:SUNSET', 3.5]]) });
    const swatch = screen.getAllByTestId('wf-region-band-swatch')[0];
    expect(swatch).toHaveStyle({ background: rampHex(3.5) });
    expect(swatch).not.toHaveStyle({ background: rampHex(3) });
  });
});
