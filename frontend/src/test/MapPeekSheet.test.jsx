import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapPeekSheet, { OtherWindowValue, MapPeekWindowsSection } from '../components/map/MapPeekSheet.jsx';

/**
 * `components/map/MapPeekSheet.jsx` — map-mobile-sheet-plan.md §3 M2.
 *
 * <p>The sheet owns no toggle logic of its own (D-1: the open section is a value of `MapView`'s own
 * `openMapMenu`) — every case below drives it purely off the `section` prop, matching how `MapView`
 * actually uses it.
 */

describe('MapPeekSheet — collapsed/open height class', () => {
  it('carries no `wf-map-peek-open` class, and renders no body, while collapsed (section null)', () => {
    render(
      <MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} />,
    );
    const sheet = screen.getByTestId('wf-map-peek');
    expect(sheet.className).toContain('wf-map-peek');
    expect(sheet.className).not.toContain('wf-map-peek-open');
    expect(screen.queryByTestId('wf-map-peek-body')).not.toBeInTheDocument();
  });

  it('carries `wf-map-peek-open` and renders a body when a section is open', () => {
    render(
      <MapPeekSheet
        section="win"
        onPressWindows={() => {}}
        onPressLayers={() => {}}
        windowsBody={<div data-testid="win-body">windows</div>}
      />,
    );
    expect(screen.getByTestId('wf-map-peek').className).toContain('wf-map-peek-open');
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    expect(screen.getByTestId('win-body')).toBeInTheDocument();
  });
});

describe('MapPeekSheet — one body at a time', () => {
  it('renders ONLY the Windows body while section is "win", never the Layers body', () => {
    render(
      <MapPeekSheet
        section="win"
        onPressWindows={() => {}}
        onPressLayers={() => {}}
        windowsBody={<div data-testid="win-body" />}
        layersBody={<div data-testid="lay-body" />}
      />,
    );
    expect(screen.getByTestId('win-body')).toBeInTheDocument();
    expect(screen.queryByTestId('lay-body')).not.toBeInTheDocument();
  });

  it('renders ONLY the Layers body while section is "lay", never the Windows body', () => {
    render(
      <MapPeekSheet
        section="lay"
        onPressWindows={() => {}}
        onPressLayers={() => {}}
        windowsBody={<div data-testid="win-body" />}
        layersBody={<div data-testid="lay-body" />}
      />,
    );
    expect(screen.getByTestId('lay-body')).toBeInTheDocument();
    expect(screen.queryByTestId('win-body')).not.toBeInTheDocument();
  });
});

describe('MapPeekSheet — the peek row: presses, aria-expanded, the CLOSE key swap', () => {
  it('the "Other windows" button reads "OTHER WINDOWS" when collapsed and calls onPressWindows on press', () => {
    const onPressWindows = vi.fn();
    render(<MapPeekSheet section={null} onPressWindows={onPressWindows} onPressLayers={() => {}} />);
    const btn = screen.getByTestId('wf-map-peek-btn-win');
    expect(btn.textContent).toContain('OTHER WINDOWS');
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(btn);
    expect(onPressWindows).toHaveBeenCalledTimes(1);
  });

  it('⚠️ the key swaps to "CLOSE" while the Windows section is the one open — the reader\'s only cue a second tap collapses it', () => {
    render(<MapPeekSheet section="win" onPressWindows={() => {}} onPressLayers={() => {}} />);
    const btn = screen.getByTestId('wf-map-peek-btn-win');
    expect(btn.textContent).toContain('CLOSE');
    expect(btn.textContent).not.toContain('OTHER WINDOWS');
    expect(btn).toHaveAttribute('aria-expanded', 'true');
    expect(btn.className).toContain('wf-map-peek-btn-on');
  });

  it('the Layers button never swaps its own key text, and calls onPressLayers on press', () => {
    const onPressLayers = vi.fn();
    render(<MapPeekSheet section="win" onPressWindows={() => {}} onPressLayers={onPressLayers} />);
    const btn = screen.getByTestId('wf-map-peek-btn-lay');
    expect(btn.textContent).toContain('LAYERS');
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(btn);
    expect(onPressLayers).toHaveBeenCalledTimes(1);
  });

  it('both peek buttons carry `aria-controls="wf-map-peek-body"` — the pill override points at the identical id', () => {
    render(<MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} />);
    expect(screen.getByTestId('wf-map-peek-btn-win')).toHaveAttribute('aria-controls', 'wf-map-peek-body');
    expect(screen.getByTestId('wf-map-peek-btn-lay')).toHaveAttribute('aria-controls', 'wf-map-peek-body');
  });

  it('attaches `layersButtonRef` to the Layers button — the Regions/Filters `BottomSheet` hosts\' restore target', () => {
    const ref = React.createRef();
    render(
      <MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} layersButtonRef={ref} />,
    );
    expect(ref.current).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
  });

  it('⚠️ M2 task 2: renders NO Tide button at all when its gate is not passed — a released control must never open an empty panel', () => {
    render(<MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} />);
    // Exactly the peek row's two buttons, never a third, with neither `tideVisible` nor
    // `onPressTide` supplied — the default, withheld shape every caller starts from.
    expect(screen.getAllByRole('button')).toHaveLength(2);
    expect(screen.queryByText(/tide/i)).not.toBeInTheDocument();
  });

  it('aria-expanded tracks the SAME pill through a real open → close cycle, not just two separate mounts', () => {
    const { rerender } = render(
      <MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} />,
    );
    const btn = screen.getByTestId('wf-map-peek-btn-win');
    expect(btn).toHaveAttribute('aria-expanded', 'false');

    rerender(<MapPeekSheet section="win" onPressWindows={() => {}} onPressLayers={() => {}} />);
    // Same DOM node re-queried, not a fresh render — `getByTestId` throws if it were replaced.
    expect(screen.getByTestId('wf-map-peek-btn-win')).toHaveAttribute('aria-expanded', 'true');

    rerender(<MapPeekSheet section={null} onPressWindows={() => {}} onPressLayers={() => {}} />);
    expect(screen.getByTestId('wf-map-peek-btn-win')).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('MapPeekSheet — the Tide button (map-mobile-sheet-plan.md §3 M3 task 4)', () => {
  it('is absent when `onPressTide` is given but `tideVisible` is false — the gate, not merely the handler, controls presence', () => {
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible={false}
      />,
    );
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
  });

  it('is absent when `tideVisible` is true but `onPressTide` is null — never a button with nothing to press', () => {
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        tideVisible
      />,
    );
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).not.toBeInTheDocument();
  });

  it('renders with a FIXED key ("TIDE AT THIS LIGHT") that never swaps to CLOSE, even while its own section is open', () => {
    const onPressTide = vi.fn();
    const { rerender } = render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={onPressTide} tideVisible
        tideButtonContent="High"
      />,
    );
    const btn = screen.getByTestId('wf-map-peek-btn-tide');
    expect(btn.textContent).toContain('TIDE AT THIS LIGHT');
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    expect(btn).toHaveAttribute('aria-controls', 'wf-map-peek-body');
    fireEvent.click(btn);
    expect(onPressTide).toHaveBeenCalledTimes(1);

    rerender(
      <MapPeekSheet
        section="tide" onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={onPressTide} tideVisible
        tideButtonContent="High"
      />,
    );
    const openBtn = screen.getByTestId('wf-map-peek-btn-tide');
    expect(openBtn.textContent).toContain('TIDE AT THIS LIGHT');
    expect(openBtn).toHaveAttribute('aria-expanded', 'true');
    expect(openBtn.className).toContain('wf-map-peek-btn-on');
  });

  it('renders the tideButtonContent value line', () => {
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible
        tideButtonContent="Mid ↑ · 6 dim"
      />,
    );
    expect(screen.getByTestId('wf-map-peek-btn-tide')).toHaveTextContent('Mid ↑ · 6 dim');
  });

  it('the Tide body renders only while section is "tide", never alongside the Windows/Layers bodies', () => {
    render(
      <MapPeekSheet
        section="tide" onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible
        tideBody={<div data-testid="tide-body" />}
        windowsBody={<div data-testid="win-body" />}
        layersBody={<div data-testid="lay-body" />}
      />,
    );
    expect(screen.getByTestId('tide-body')).toBeInTheDocument();
    expect(screen.queryByTestId('win-body')).not.toBeInTheDocument();
    expect(screen.queryByTestId('lay-body')).not.toBeInTheDocument();
  });

  it('§3 M5 task 3: carries no pulse class by default, and none when `tidePulse` is explicitly false', () => {
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible
      />,
    );
    expect(screen.getByTestId('wf-map-peek-btn-tide').className).not.toContain('wf-map-peek-btn-pulse');
  });

  it('§3 M5 task 3: carries the pulse class when `tidePulse` is true', () => {
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible tidePulse
      />,
    );
    expect(screen.getByTestId('wf-map-peek-btn-tide').className).toContain('wf-map-peek-btn-pulse');
  });

  // ⚠️ No `fireEvent.animationEnd` test of `onTidePulseEnd` here (§3 M5 task 3's clearing rule).
  // One was written and DID pass in isolation, but proved non-deterministically flaky the moment
  // any OTHER test file shares its vitest worker: a plain `<div onAnimationEnd>` canary reproduced
  // the same "handler never called, though the native event demonstrably reaches the node" failure
  // when paired with `MapLabels.test.jsx` or `MapViewMobilePeekSheet.test.jsx` (unrelated, already-
  // merged files) — repeat runs of the IDENTICAL file combination passed sometimes and failed
  // others, so this is a pre-existing jsdom/React "animationend" cross-file delegation quirk in
  // this suite, not a defect in `onAnimationEnd={onTidePulseEnd}` above (which a source read
  // confirms is wired correctly, and which the OTHER Tide-button pulse tests already exercise
  // structurally — the class appears/disappears exactly on `tidePulse`; only the LIVE DOM EVENT
  // dispatch is what breaks). Kept out rather than shipped flaky.
  it('attaches `tideButtonRef` to the Tide button and `windowsButtonRef` to the Other windows button', () => {
    const tideButtonRef = React.createRef();
    const windowsButtonRef = React.createRef();
    render(
      <MapPeekSheet
        section={null} onPressWindows={() => {}} onPressLayers={() => {}}
        onPressTide={() => {}} tideVisible
        tideButtonRef={tideButtonRef} windowsButtonRef={windowsButtonRef}
      />,
    );
    expect(tideButtonRef.current).toBe(screen.getByTestId('wf-map-peek-btn-tide'));
    expect(windowsButtonRef.current).toBe(screen.getByTestId('wf-map-peek-btn-win'));
  });
});

describe('OtherWindowValue', () => {
  it('renders an em-dash placeholder when there is no other window', () => {
    render(<OtherWindowValue row={null} />);
    expect(screen.getByTestId('wf-map-peek-other-empty')).toBeInTheDocument();
  });

  it('a solar row renders `{day} {AM|PM}` plus the verdict word, coloured by tier', () => {
    render(
      <OtherWindowValue
        row={{
          kind: 'solar', eventType: 'SUNRISE', dayLabel: 'Tomorrow', label: 'Tomorrow sunrise',
        }}
        verdict={{ tier: 'MAYBE' }}
      />,
    );
    const verdict = screen.getByTestId('wf-map-peek-other-verdict');
    expect(screen.getByText(/Tomorrow AM/)).toBeInTheDocument();
    expect(verdict).toHaveAttribute('data-tier', 'MAYBE');
  });

  it('a night row with no verdict renders the licensed `{bestRating}★ best` figure, never a verdict word', () => {
    render(
      <OtherWindowValue
        row={{
          kind: 'astro', dayLabel: 'Friday', label: 'Friday night', scored: true, bestRating: 4,
        }}
        verdict={null}
      />,
    );
    expect(screen.getByTestId('wf-map-peek-other-best')).toHaveTextContent('4★ best');
    expect(screen.queryByTestId('wf-map-peek-other-verdict')).not.toBeInTheDocument();
  });

  it('an unscored night row renders no verdict/star clause at all — no AM/PM either', () => {
    render(
      <OtherWindowValue row={{ kind: 'aur', dayLabel: 'Saturday', label: 'Saturday night', scored: false }} />,
    );
    expect(screen.queryByTestId('wf-map-peek-other-best')).not.toBeInTheDocument();
    expect(screen.getByText('Saturday')).toBeInTheDocument();
    expect(screen.queryByText(/AM|PM/)).not.toBeInTheDocument();
  });
});

describe('MapPeekWindowsSection', () => {
  const ROWS = [
    {
      id: 'a', kind: 'solar', eventType: 'SUNSET', dayLabel: 'Tonight', label: 'Tonight sunset', time: '20:14',
    },
    {
      id: 'b', kind: 'astro', dayLabel: 'Tonight', label: 'Tonight, astro', time: '22:00', scored: true, bestRating: 3,
    },
  ];
  const VERDICTS = new Map([['a', { tier: 'WORTH_IT' }]]);

  it('renders the served-derived heading, never a fixed string', () => {
    render(
      <MapPeekWindowsSection heading="Tonight, or tomorrow?" rows={ROWS} verdicts={VERDICTS} onSelect={() => {}} />,
    );
    expect(screen.getByTestId('wf-map-peek-windows')).toHaveTextContent('Tonight, or tomorrow?');
  });

  it('a solar row shows its verdict word; a night row shows the licensed best-of-night figure', () => {
    render(<MapPeekWindowsSection heading="H" rows={ROWS} verdicts={VERDICTS} onSelect={() => {}} />);
    const rows = screen.getAllByTestId('wf-map-peek-win-row');
    expect(rows[0]).toHaveTextContent('Worth it');
    expect(screen.getByTestId('wf-map-peek-wr-best-of-night')).toHaveTextContent('3★ best');
  });

  it('tapping a row calls onSelect with that row — the sheet itself decides whether to stay open', () => {
    const onSelect = vi.fn();
    render(<MapPeekWindowsSection heading="H" rows={ROWS} verdicts={VERDICTS} onSelect={onSelect} />);
    fireEvent.click(screen.getAllByTestId('wf-map-peek-win-row')[0]);
    expect(onSelect).toHaveBeenCalledWith(ROWS[0]);
  });

  it('the active row carries `aria-selected` and the `.on` class', () => {
    render(
      <MapPeekWindowsSection
        heading="H" rows={ROWS} verdicts={VERDICTS} activeId="b" onSelect={() => {}}
      />,
    );
    const rows = screen.getAllByTestId('wf-map-peek-win-row');
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');
    expect(rows[1].className).toContain(' on');
    expect(rows[0]).toHaveAttribute('aria-selected', 'false');
    // The negative too — a review found this test asserted only the positive, which would still
    // pass if the `.on` class leaked onto every row rather than just the active one.
    expect(rows[0].className).not.toContain(' on');
  });

  it('the drilldown row (§1 #13, §4 #4) is the LAST row, and calls onOpenDrilldown — withheld entirely when null', () => {
    const { rerender } = render(
      <MapPeekWindowsSection heading="H" rows={ROWS} verdicts={VERDICTS} onSelect={() => {}} onOpenDrilldown={null} />,
    );
    expect(screen.queryByTestId('wf-map-peek-drilldown')).not.toBeInTheDocument();

    const onOpenDrilldown = vi.fn();
    rerender(
      <MapPeekWindowsSection heading="H" rows={ROWS} verdicts={VERDICTS} onSelect={() => {}} onOpenDrilldown={onOpenDrilldown} />,
    );
    const drilldown = screen.getByTestId('wf-map-peek-drilldown');
    expect(drilldown).toHaveTextContent('This window, region by region');
    fireEvent.click(drilldown);
    expect(onOpenDrilldown).toHaveBeenCalledTimes(1);
  });
});
