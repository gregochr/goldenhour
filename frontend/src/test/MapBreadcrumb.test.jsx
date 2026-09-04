/**
 * `MapBreadcrumb` (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 3). Purely presentational: every
 * clause is derived from the props it is handed, so this file drives it directly rather than through
 * `MapView` — the derived-truth rule (§5 rule 3) is exactly "does the clause disappear when the LIVE
 * value stops matching the CARRIED one", which is provable by re-rendering with a different prop.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapBreadcrumb from '../components/map/MapBreadcrumb.jsx';

const ACTIVE_ROW = { dayLabel: 'Tonight', eventType: 'SUNSET' };
const ORIGIN = { id: 'lakes', name: 'Lake District', baseName: 'Keswick' };

function renderCrumb(props = {}) {
  const handlers = {
    onBack: vi.fn(),
    onClearRating: vi.fn(),
    onClearReach: vi.fn(),
    onClearScope: vi.fn(),
    onClearOrigin: vi.fn(),
  };
  const view = render(
    <MapBreadcrumb
      carried={{}}
      minStars={3}
      driveTimeFilter={0}
      {...handlers}
      {...props}
    />,
  );
  return { ...view, ...handlers };
}

describe('MapBreadcrumb — the ← Plan control', () => {
  it('has "Plan" alone as its accessible name — the arrow is aria-hidden', () => {
    renderCrumb();
    expect(screen.getByRole('button', { name: 'Plan' })).toBeInTheDocument();
  });

  it('calls onBack when pressed', () => {
    const { onBack } = renderCrumb();
    fireEvent.click(screen.getByRole('button', { name: 'Plan' }));
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});

describe('MapBreadcrumb — the window clause', () => {
  it('names the active row\'s day and kind word when one is showing', () => {
    renderCrumb({ activeRow: ACTIVE_ROW });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('Tonight sunset');
  });

  it('is omitted — no "/" separator, no window span — when there is no active row', () => {
    renderCrumb({ activeRow: null });
    expect(screen.queryByTestId('wf-map-breadcrumb-window')).toBeNull();
    expect(screen.queryByText('/')).toBeNull();
  });

  it('reads the sunrise kind word too, not only sunset', () => {
    renderCrumb({ activeRow: { dayLabel: 'Tomorrow', eventType: 'SUNRISE' } });
    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('Tomorrow sunrise');
  });

  it('carries a real space at every TOP-LEVEL sibling boundary — never a bare adjacency JSX '
      + 'would collapse to nothing', () => {
    // The exact trap this file's own doc comment names: JSX drops a whitespace-only text node
    // between sibling tags rather than collapsing it to a space, so two siblings placed side by
    // side with only a newline between them in the SOURCE render with ZERO characters between
    // them in the DOM — "Plan" glued straight onto "/", and "/" glued straight onto "Tonight".
    // `toHaveTextContent` alone would not catch this: it was passing before the fix, because
    // each earlier assertion only checked text WITHIN one span, never across a sibling boundary.
    // Reading `.textContent` directly (never normalised) is what actually proves a real space
    // character sits between "Plan" and "/", and between "/" and "Tonight".
    renderCrumb({
      activeRow: ACTIVE_ROW,
      carried: { minRating: 4 },
      minStars: 4,
    });
    const { textContent } = screen.getByTestId('wf-map-breadcrumb');
    expect(textContent).not.toMatch(/Plan\//);
    expect(textContent).not.toMatch(/\/Tonight/);
    expect(textContent).not.toMatch(/sunsetcarrying/);
    expect(textContent).toMatch(/Plan \/ Tonight sunset carrying/);
  });
});

describe('MapBreadcrumb — every carrying clause, present and absent', () => {
  it('names the origin\'s base town when one is in force', () => {
    renderCrumb({ origin: ORIGIN });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying'))
      .toHaveTextContent('drive times from Keswick');
  });

  it('omits the origin clause at home', () => {
    renderCrumb({ origin: null, carried: { minRating: 4 }, minStars: 4 });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).not.toHaveTextContent('drive times');
  });

  it('names the rating floor when the map still holds the carried value', () => {
    renderCrumb({ carried: { minRating: 4 }, minStars: 4 });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('4★+');
  });

  it('omits the rating clause when the carried value is null (Plan\'s Any lens)', () => {
    // §1 #6 / §5 decision 2: Any carries as minStars=1 but the crumb never NAMES it — there is
    // nothing for the reader to act on, since 1★+ already admits every rated spot.
    renderCrumb({ carried: { minRating: null }, minStars: 1 });
    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
  });

  it('omits the rating clause when the reader RAISES the floor past the carried value — an exact '
      + 'match is required in EITHER direction, not just "at least as high"', () => {
    // A `minStars >= carried.minRating` mutation would still pass every "lowered" fixture (every
    // mismatch elsewhere in this file moves the live value DOWN from the carried one, where `===`
    // and `>=` agree) — this is the direction that tells the two apart. If the crumb kept naming
    // "4★+" after the reader raised the floor to 5, it would be asserting a narrower carried claim
    // than the map is actually holding, i.e. asserting a floor the reader never asked to lift.
    renderCrumb({ carried: { minRating: 4 }, minStars: 5 });
    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
  });

  it('names the reach tier using the SAME label FiltersPopover shows for it', () => {
    renderCrumb({ carried: { limitMinutes: 150 }, driveTimeFilter: 150 });
    // DRIVE_TIME_TIERS' own [150, '2h 30'] — the increment's own copy example.
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('within 2h 30');
  });

  it('omits the reach clause when the carried limit is null (Any)', () => {
    renderCrumb({ carried: { limitMinutes: null, minRating: 4 }, minStars: 4, driveTimeFilter: 0 });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).not.toHaveTextContent('within');
  });

  it('omits the reach clause when the reader WIDENS the tier past the carried value — the identical '
      + '"either direction" rule the rating clause holds, so a >= mutation cannot hide behind it', () => {
    renderCrumb({ carried: { limitMinutes: 90, minRating: 4 }, minStars: 4, driveTimeFilter: 150 });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).not.toHaveTextContent('within');
  });

  it('names the carried region when its jump is the scope in force', () => {
    renderCrumb({ carried: { region: 'Lake District' }, regionInForce: 'Lake District' });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('Lake District');
  });

  it('omits the region clause when a DIFFERENT region\'s jump is now in force', () => {
    renderCrumb({ carried: { region: 'Lake District' }, regionInForce: 'Peak District' });
    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
  });

  it('joins several live clauses with " · ", origin first', () => {
    renderCrumb({
      origin: ORIGIN,
      carried: { minRating: 4, limitMinutes: 150, region: 'Lake District' },
      minStars: 4,
      driveTimeFilter: 150,
      regionInForce: 'Lake District',
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent(
      'carrying drive times from Keswick · 4★+ · within 2h 30 · Lake District',
    );
  });

  it('omits the whole carrying group — and the clear button — when no clause holds at all', () => {
    renderCrumb({ carried: {} });
    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
  });
});

describe('MapBreadcrumb — the derived-truth rule (§5 rule 3)', () => {
  it('drops the ★ clause alone when the reader moves the floor, keeping the rest', () => {
    const { rerender } = renderCrumb({
      origin: ORIGIN,
      carried: { minRating: 4, limitMinutes: 150, region: 'Lake District' },
      minStars: 4,
      driveTimeFilter: 150,
      regionInForce: 'Lake District',
      onBack: vi.fn(), onClearRating: vi.fn(), onClearReach: vi.fn(),
      onClearScope: vi.fn(), onClearOrigin: vi.fn(),
    });
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('4★+');

    // The reader moves the floor on the map itself — minStars no longer matches carried.minRating.
    rerender(
      <MapBreadcrumb
        origin={ORIGIN}
        carried={{ minRating: 4, limitMinutes: 150, region: 'Lake District' }}
        minStars={2}
        driveTimeFilter={150}
        regionInForce="Lake District"
        onBack={vi.fn()} onClearRating={vi.fn()} onClearReach={vi.fn()}
        onClearScope={vi.fn()} onClearOrigin={vi.fn()}
      />,
    );
    const carrying = screen.getByTestId('wf-map-breadcrumb-carrying');
    expect(carrying).not.toHaveTextContent('★+');
    expect(carrying).toHaveTextContent('drive times from Keswick');
    expect(carrying).toHaveTextContent('within 2h 30');
    expect(carrying).toHaveTextContent('Lake District');
  });
});

describe('MapBreadcrumb — clear', () => {
  it('has "clear" as its accessible name', () => {
    renderCrumb({ carried: { minRating: 4 }, minStars: 4 });
    expect(screen.getByRole('button', { name: 'clear' })).toBeInTheDocument();
  });

  it('calls all four resets exactly once, in order: rating, reach, scope, origin', () => {
    const { onClearRating, onClearReach, onClearScope, onClearOrigin } = renderCrumb({
      origin: ORIGIN,
      carried: { minRating: 4, limitMinutes: 150, region: 'Lake District' },
      minStars: 4,
      driveTimeFilter: 150,
      regionInForce: 'Lake District',
    });
    fireEvent.click(screen.getByRole('button', { name: 'clear' }));

    expect(onClearRating).toHaveBeenCalledTimes(1);
    expect(onClearReach).toHaveBeenCalledTimes(1);
    expect(onClearScope).toHaveBeenCalledTimes(1);
    expect(onClearOrigin).toHaveBeenCalledTimes(1);
    const order = [onClearRating, onClearReach, onClearScope, onClearOrigin]
      .map((fn) => fn.mock.invocationCallOrder[0]);
    expect(order).toEqual([...order].sort((a, b) => a - b));
  });
});
