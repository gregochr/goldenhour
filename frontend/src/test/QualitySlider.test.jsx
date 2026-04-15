import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import QualitySlider from '../components/QualitySlider.jsx';
import { TIER_LABELS } from '../utils/tierUtils.js';

// All 6 internal→visual inversion pairs. MAX_TIER = 5; visual = 5 - internal.
const INVERSION_TABLE = [
  { internal: 0, visual: 5 },
  { internal: 1, visual: 4 },
  { internal: 2, visual: 3 },
  { internal: 3, visual: 2 },
  { internal: 4, visual: 1 },
  { internal: 5, visual: 0 },
];

// Drag table: same pairs plus `startFrom` — a starting internal tier whose visual
// position differs from the drag target, ensuring fireEvent.change is a real change.
const DRAG_TABLE = [
  { visual: 5, expectedInternal: 0, startFrom: 1 }, // start visual=4, drag to 5
  { visual: 4, expectedInternal: 1, startFrom: 0 }, // start visual=5, drag to 4
  { visual: 3, expectedInternal: 2, startFrom: 0 }, // start visual=5, drag to 3
  { visual: 2, expectedInternal: 3, startFrom: 0 }, // start visual=5, drag to 2
  { visual: 1, expectedInternal: 4, startFrom: 0 }, // start visual=5, drag to 1
  { visual: 0, expectedInternal: 5, startFrom: 1 }, // start visual=4, drag to 0
];

describe('QualitySlider', () => {
  it('does not render Worst or Best labels', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.queryByText('Worst')).not.toBeInTheDocument();
    expect(screen.queryByText('Best')).not.toBeInTheDocument();
  });

  it('slider fully left (visual 0) means everything visible (internal tier 5)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={5} onChange={onChange} showing={10} total={10} />);
    const slider = screen.getByRole('slider');
    // Internal 5 → visual 0 (fully left)
    expect(slider).toHaveValue('0');
  });

  it('slider fully right (visual 5) means best only (internal tier 0)', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    const slider = screen.getByRole('slider');
    // Internal 0 → visual 5 (fully right)
    expect(slider).toHaveValue('5');
  });

  it('default tier 2 renders at visual position 3', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    const slider = screen.getByRole('slider');
    // Internal 2 → visual 3
    expect(slider).toHaveValue('3');
  });

  it('dragging right raises quality (lowers internal tier)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={2} onChange={onChange} showing={5} total={12} />);
    // Drag to visual 4 → internal = 5 - 4 = 1
    fireEvent.change(screen.getByRole('slider'), { target: { value: '4' } });
    expect(onChange).toHaveBeenCalledWith(1);
  });

  it('dragging left lowers quality (raises internal tier)', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={2} onChange={onChange} showing={5} total={12} />);
    // Drag to visual 1 → internal = 5 - 1 = 4
    fireEvent.change(screen.getByRole('slider'), { target: { value: '1' } });
    expect(onChange).toHaveBeenCalledWith(4);
  });

  it('shows the showing/total cell count', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={8} total={18} />);
    expect(screen.getByText(/Showing 8 of 18 cells/)).toBeInTheDocument();
  });

  it('shows the tier label for the current value', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    expect(screen.getByText((content) => content.includes(TIER_LABELS[0]))).toBeInTheDocument();
  });

  it('has aria-label on the slider', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-label', 'Quality threshold');
  });

  it('has aria-valuetext set to current tier label', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', TIER_LABELS[3]);
  });

  it('displays WORTH IT label at best-quality tier', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={2} total={10} />);
    expect(screen.getByText((t) => t.includes('WORTH IT + king tide or aurora'))).toBeInTheDocument();
  });

  it('displays MAYBE label at marginal tier', () => {
    render(<QualitySlider value={3} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByText((t) => t.includes('MAYBE + tide aligned'))).toBeInTheDocument();
  });

  it('displays Everything including standdown at lowest tier', () => {
    render(<QualitySlider value={5} onChange={() => {}} showing={12} total={12} />);
    expect(screen.getByText((t) => t.includes('Everything including standdown'))).toBeInTheDocument();
  });

  // ── Full inversion table: all 6 internal → visual positions ───────────────
  // Kills mutations on MAX_TIER (e.g. 5→4 or 5→6) and on the ± operator.

  it.each(INVERSION_TABLE)(
    'internal tier $internal renders slider at visual position $visual',
    ({ internal, visual }) => {
      render(<QualitySlider value={internal} onChange={() => {}} showing={1} total={10} />);
      expect(screen.getByRole('slider')).toHaveValue(String(visual));
    },
  );

  // ── Full drag table: all 6 visual drag positions → internal onChange value ──
  // Each row uses a startFrom value whose visual position ≠ drag target, so
  // fireEvent.change is a genuine state change (React suppresses no-op changes).
  // Kills mutations on the inverse formula inside handleChange.

  it.each(DRAG_TABLE)(
    'dragging to visual $visual calls onChange with internal $expectedInternal',
    ({ visual, expectedInternal, startFrom }) => {
      const onChange = vi.fn();
      render(<QualitySlider value={startFrom} onChange={onChange} showing={5} total={12} />);
      fireEvent.change(screen.getByRole('slider'), { target: { value: String(visual) } });
      expect(onChange).toHaveBeenCalledTimes(1);
      expect(onChange).toHaveBeenCalledWith(expectedInternal);
    },
  );

  // ── Tier label shown in body text for every tier ──────────────────────────
  // Kills mutations that swap TIER_LABELS[value] for TIER_LABELS[visualValue].

  it.each(INVERSION_TABLE)(
    'tier label for internal $internal matches TIER_LABELS[$internal]',
    ({ internal }) => {
      render(<QualitySlider value={internal} onChange={() => {}} showing={1} total={10} />);
      expect(
        screen.getByText((t) => t.includes(TIER_LABELS[internal])),
      ).toBeInTheDocument();
    },
  );

  // ── aria-valuetext follows internal tier, not visual position ─────────────

  it.each(INVERSION_TABLE)(
    'aria-valuetext is TIER_LABELS[$internal] (not the visual label) for internal $internal',
    ({ internal }) => {
      render(<QualitySlider value={internal} onChange={() => {}} showing={1} total={10} />);
      expect(screen.getByRole('slider')).toHaveAttribute('aria-valuetext', TIER_LABELS[internal]);
    },
  );

  // ── Slider range attributes ───────────────────────────────────────────────
  // Kills mutations that alter min, max, or step.

  it('slider has min=0, max=5, step=1', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    const slider = screen.getByRole('slider');
    expect(slider).toHaveAttribute('min', '0');
    expect(slider).toHaveAttribute('max', '5');
    expect(slider).toHaveAttribute('step', '1');
  });

  // ── Showing / total count ─────────────────────────────────────────────────
  // Separate assertions pin each number independently — kills a swap mutation.

  it('showing count is displayed independently from total', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={7} total={20} />);
    const text = screen.getByText(/Showing 7 of 20 cells/);
    expect(text).toBeInTheDocument();
  });

  it('showing=0 is displayed as zero, not as total', () => {
    render(<QualitySlider value={0} onChange={() => {}} showing={0} total={15} />);
    expect(screen.getByText(/Showing 0 of 15 cells/)).toBeInTheDocument();
  });

  it('showing equals total when all cells are visible', () => {
    render(<QualitySlider value={5} onChange={() => {}} showing={9} total={9} />);
    expect(screen.getByText(/Showing 9 of 9 cells/)).toBeInTheDocument();
  });

  // ── data-testid ───────────────────────────────────────────────────────────

  it('root element has data-testid="quality-slider"', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.getByTestId('quality-slider')).toBeInTheDocument();
  });

  // ── Show all locations toggle ─────────────────────────────────────────────

  it('renders toggle switch when onShowAllLocationsChange is provided', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={() => {}}
      />,
    );
    expect(screen.getByTestId('show-all-locations-toggle')).toBeInTheDocument();
  });

  it('does not render toggle when onShowAllLocationsChange is not provided', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    expect(screen.queryByTestId('show-all-locations-toggle')).not.toBeInTheDocument();
  });

  it('toggle has role="switch" and aria-checked matching state', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={true}
        onShowAllLocationsChange={() => {}}
      />,
    );
    const toggle = screen.getByRole('switch');
    expect(toggle).toHaveAttribute('aria-checked', 'true');
  });

  it('toggle aria-checked is false when showAllLocations is false', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={() => {}}
      />,
    );
    const toggle = screen.getByRole('switch');
    expect(toggle).toHaveAttribute('aria-checked', 'false');
  });

  it('clicking toggle calls onShowAllLocationsChange with inverted value', () => {
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={onToggle}
      />,
    );
    fireEvent.click(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledWith(true);
  });

  it('clicking toggle when on calls onShowAllLocationsChange with false', () => {
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={true}
        onShowAllLocationsChange={onToggle}
      />,
    );
    fireEvent.click(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledWith(false);
  });

  it('toggle displays "Show all locations" label', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={() => {}}
      />,
    );
    expect(screen.getByText('Show all locations')).toBeInTheDocument();
  });

  // ── Toggle track data-checked attribute ───────────────────────────────────
  // Kills mutations that hardcode 'true'/'false' or flip the ternary on line 78.

  it('toggle track data-checked is "true" when showAllLocations is true', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={true}
        onShowAllLocationsChange={() => {}}
      />,
    );
    const track = screen.getByTestId('show-all-locations-toggle').querySelector('.quality-toggle-track');
    expect(track).toHaveAttribute('data-checked', 'true');
  });

  it('toggle track data-checked is "false" when showAllLocations is false', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={() => {}}
      />,
    );
    const track = screen.getByTestId('show-all-locations-toggle').querySelector('.quality-toggle-track');
    expect(track).toHaveAttribute('data-checked', 'false');
  });

  // ── Conditional rendering guard ───────────────────────────────────────────
  // Kills a mutant that changes the guard from onShowAllLocationsChange to showAllLocations.

  it('does not render toggle when showAllLocations is true but onShowAllLocationsChange is undefined', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={true}
      />,
    );
    expect(screen.queryByTestId('show-all-locations-toggle')).not.toBeInTheDocument();
  });

  // ── Callback isolation ────────────────────────────────────────────────────
  // Kills mutations that swap onChange ↔ onShowAllLocationsChange.

  it('clicking toggle does not call the slider onChange callback', () => {
    const onChange = vi.fn();
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={2}
        onChange={onChange}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={onToggle}
      />,
    );
    fireEvent.click(screen.getByRole('switch'));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('dragging slider does not call onShowAllLocationsChange', () => {
    const onChange = vi.fn();
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={2}
        onChange={onChange}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={onToggle}
      />,
    );
    fireEvent.change(screen.getByRole('slider'), { target: { value: '4' } });
    expect(onToggle).not.toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  // ── Toggle call count ─────────────────────────────────────────────────────
  // Kills mutations that double-fire or skip the callback.

  it('toggle click fires onShowAllLocationsChange exactly once', () => {
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={onToggle}
      />,
    );
    fireEvent.click(screen.getByRole('switch'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  // ── No spurious callback on mount ─────────────────────────────────────────

  it('onChange is not called on initial render', () => {
    const onChange = vi.fn();
    render(<QualitySlider value={3} onChange={onChange} showing={5} total={12} />);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('onShowAllLocationsChange is not called on initial render', () => {
    const onToggle = vi.fn();
    render(
      <QualitySlider
        value={3}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={onToggle}
      />,
    );
    expect(onToggle).not.toHaveBeenCalled();
  });

  // ── Dot separator ─────────────────────────────────────────────────────────
  // Kills mutations that remove the middot separator between count and tier label.

  it('renders a dot separator between cell count and tier label', () => {
    render(<QualitySlider value={2} onChange={() => {}} showing={5} total={12} />);
    const slider = screen.getByTestId('quality-slider');
    expect(slider.textContent).toContain('\u00B7');
  });

  // ── Pin showing vs total vs value to distinct values ──────────────────────
  // Kills mutations that substitute value for showing, total for showing, etc.

  it('renders showing, total, and tier label from three independent props', () => {
    // value=1, showing=3, total=17 — all distinct, none collide
    render(<QualitySlider value={1} onChange={() => {}} showing={3} total={17} />);
    expect(screen.getByText(/Showing 3 of 17 cells/)).toBeInTheDocument();
    expect(screen.getByText((t) => t.includes(TIER_LABELS[1]))).toBeInTheDocument();
    // A mutant using value (1) or total (17) in place of showing (3) fails the regex
    expect(screen.queryByText(/Showing 1 of/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Showing 17 of/)).not.toBeInTheDocument();
  });

  // ── Toggle button type ────────────────────────────────────────────────────
  // Kills mutations that remove type="button", which could cause form submission.

  it('toggle button has type="button"', () => {
    render(
      <QualitySlider
        value={2}
        onChange={() => {}}
        showing={5}
        total={12}
        showAllLocations={false}
        onShowAllLocationsChange={() => {}}
      />,
    );
    expect(screen.getByRole('switch')).toHaveAttribute('type', 'button');
  });
});
