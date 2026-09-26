/**
 * `MapPeekTideSection` in isolation — map-mobile-sheet-plan.md §3 M3 task 3,
 * `docs/design/map-mobile-sheet/README.md` "Section: Tide". Modelled on `MapTideStrip.test.jsx`'s
 * own split: a pure, fully-controlled render driven by a `mapTideFit.stripModel`-shaped `model` and
 * a `BriefingWindowTide`-shaped `tide`, never through a real `MapView`.
 *
 * Covers every line from one `stripModel` fixture (the key, the phase line, the height/time
 * clause, the chart), the zero-dimmed sentence, the jump calling `onSelectEv` with the scanned row
 * and focusing the stable target BEFORE that call (the disappearing-link keyboard path), and the
 * "beyond" sentence when `nextFitRow` is `-1`.
 */
import { useState } from 'react';
import {
  describe, it, expect, vi,
} from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapPeekTideSection from '../components/map/MapPeekTideSection.jsx';

function tideFixture(overrides = {}) {
  return {
    locationName: 'Bamburgh Castle',
    state: 'MID',
    direction: 'RISING',
    range: '4.3 m',
    rangeAnomaly: 'about average',
    curve: Array.from({ length: 49 }, (_, i) => (1 - Math.cos((i / 48) * Math.PI * 2)) / 2),
    windowPosition: 0.24,
    windowLevel: 0.5,
    sunrisePosition: 0.24,
    sunsetPosition: 0.86,
    extremes: [
      { kind: 'LW', position: 0.02, time: '00:29' },
      { kind: 'HW', position: 0.36, time: '08:38' },
    ],
    heightAtWindow: '2.6 m',
    ...overrides,
  };
}

function baseModel(overrides = {}) {
  return {
    visible: true,
    representative: 'Bamburgh Castle',
    namedCoastal: new Array(16).fill(0).map((_, i) => ({ name: `Spot${i}` })),
    dimmed: [],
    matched: [],
    dominantWant: null,
    dominantWantCount: 0,
    nextFitRow: -1,
    ...overrides,
  };
}

const activeRow = { date: '2026-09-20', eventType: 'SUNRISE', time: '05:44', dayLabel: 'Today' };

describe('MapPeekTideSection — every line from one fixture', () => {
  it('renders null when there is no served tide at all', () => {
    const { container } = render(
      <MapPeekTideSection tide={null} activeRow={activeRow} model={baseModel()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('the key is the strip\'s own kicker text, verbatim', () => {
    render(<MapPeekTideSection tide={tideFixture()} activeRow={activeRow} model={baseModel()} />);
    expect(screen.getByTestId('wf-map-peek-tide-key')).toHaveTextContent('Tide at this light');
  });

  it('the phase line is the served state/direction phrase', () => {
    render(<MapPeekTideSection tide={tideFixture()} activeRow={activeRow} model={baseModel()} />);
    expect(screen.getByTestId('wf-map-peek-tide-phase')).toHaveTextContent('mid tide, rising');
  });

  it('the height/time clause joins the served height and the window\'s own served clock time', () => {
    render(<MapPeekTideSection tide={tideFixture()} activeRow={activeRow} model={baseModel()} />);
    expect(screen.getByTestId('wf-map-peek-tide-meta')).toHaveTextContent('2.6 m · 05:44');
  });

  it('drops the height/time clause when neither half is servable', () => {
    render(
      <MapPeekTideSection
        tide={tideFixture({ heightAtWindow: null })}
        activeRow={{ ...activeRow, time: '' }}
        model={baseModel()}
      />,
    );
    expect(screen.queryByTestId('wf-map-peek-tide-meta')).not.toBeInTheDocument();
  });

  it('renders the tall chart (a real SVG with the served curve)', () => {
    render(<MapPeekTideSection tide={tideFixture()} activeRow={activeRow} model={baseModel()} />);
    expect(screen.getByTestId('wf-tide-strip-svg')).toHaveAttribute('viewBox', '0 0 1000 92');
  });

  it('renders the zero-dimmed sentence when nothing is dimmed', () => {
    render(
      <MapPeekTideSection
        tide={tideFixture()}
        activeRow={activeRow}
        model={baseModel({ namedCoastal: new Array(6).fill(0), matched: new Array(6).fill(0) })}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-footer')).toHaveTextContent(
      '6 of 6 coastal spots have the water they want',
    );
  });

  it('focuses the stable target BEFORE calling onSelectEv with the scanned row (§3 M3 task 3\'s own order)', () => {
    const onSelectEv = vi.fn();
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    const focusTargetRef = { current: document.createElement('button') };
    document.body.appendChild(focusTargetRef.current);
    const order = [];
    onSelectEv.mockImplementation(() => order.push('onSelectEv'));
    const focusSpy = vi.spyOn(focusTargetRef.current, 'focus').mockImplementation(() => order.push('focus'));

    render(
      <MapPeekTideSection
        tide={tideFixture()}
        activeRow={activeRow}
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: nextRow })}
        onSelectEv={onSelectEv}
        focusTargetRef={focusTargetRef}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-strip-next'));

    expect(onSelectEv).toHaveBeenCalledWith(nextRow);
    // §3 M3 task 3: focus lands on the stable target — never on `<body>` — when the link's own
    // press resolves the very fact it existed for and it unmounts on the next render. Focus is
    // parked BEFORE the state-changing call, not after.
    expect(focusSpy).toHaveBeenCalled();
    expect(order).toEqual(['focus', 'onSelectEv']);
  });

  it('renders the beyond denial when nextFitRow is -1', () => {
    render(
      <MapPeekTideSection
        tide={tideFixture()}
        activeRow={{ ...activeRow, eventType: 'SUNRISE' }}
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: -1 })}
      />,
    );
    expect(screen.getByTestId('wf-tide-strip-beyond')).toHaveTextContent(
      'Next high water on a sunrise is beyond these four days',
    );
  });

  it('the disappearing-link path: activating the jump resolves the very fit it existed for, the link unmounts on rerender, and focus stays on the stable target rather than falling to <body> (§3 M3 task 3)', () => {
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    const focusTargetRef = { current: null };

    function Harness() {
      const [model, setModel] = useState(
        baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: nextRow }),
      );
      return (
        <>
          <button
            type="button"
            aria-label="Tide"
            data-testid="stable-target"
            ref={(el) => { focusTargetRef.current = el; }}
          />
          <MapPeekTideSection
            tide={tideFixture()}
            activeRow={activeRow}
            model={model}
            // The jump RESOLVES the fit — the next render's model has nothing dimmed, so
            // `nextFitCopy` returns null and the link (and the whole footer's jump branch)
            // unmounts, exactly the case this test exists for.
            onSelectEv={() => setModel(baseModel({ namedCoastal: [{ name: 'X' }], matched: [{ name: 'X' }] }))}
            focusTargetRef={focusTargetRef}
          />
        </>
      );
    }

    render(<Harness />);
    // Activation via a real click on a focused, real <button> — the same event a browser fires
    // for a keyboard Enter/Space press on it (jsdom does not synthesise that translation itself,
    // so this is the behavioural equivalent this suite's own convention uses elsewhere).
    screen.getByTestId('wf-tide-strip-next').focus();
    fireEvent.click(screen.getByTestId('wf-tide-strip-next'));

    expect(screen.queryByTestId('wf-tide-strip-next')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(focusTargetRef.current);
    expect(document.activeElement).not.toBe(document.body);
  });

  it('tolerates a missing focusTargetRef without throwing on the jump', () => {
    const onSelectEv = vi.fn();
    const nextRow = { dayLabel: 'Thursday', eventType: 'SUNSET' };
    render(
      <MapPeekTideSection
        tide={tideFixture()}
        activeRow={activeRow}
        model={baseModel({ dimmed: [{ name: 'X' }], dominantWant: 'HIGH', nextFitRow: nextRow })}
        onSelectEv={onSelectEv}
      />,
    );
    expect(() => fireEvent.click(screen.getByTestId('wf-tide-strip-next'))).not.toThrow();
    expect(onSelectEv).toHaveBeenCalledWith(nextRow);
  });
});
