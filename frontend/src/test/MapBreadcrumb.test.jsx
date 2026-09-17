/**
 * `MapBreadcrumb` (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 3). Every clause is derived from
 * the props it is handed, so this file drives it directly rather than through `MapView` — the
 * derived-truth rule (§5 rule 3) is exactly "does the clause disappear when the LIVE value stops
 * matching the CARRIED one", which is provable by re-rendering with a different prop. It also owns
 * one focus handoff, for `clear` (the last two describes).
 */
import { StrictMode, useLayoutEffect, useRef } from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapBreadcrumb from '../components/map/MapBreadcrumb.jsx';
import Modal from '../components/shared/Modal.jsx';

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
    // The exact trap this file's own doc comment names: JSX drops whitespace-only text that
    // contains a line break rather than collapsing it to a space, so two siblings placed side by
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

describe('MapBreadcrumb — clear hands the reader\'s focus to the strip when it takes itself away', () => {
  // One live clause, the carried 4★ floor. `clear` resets the map's floor to its 3★ default, which
  // ends the clause — so the render `MapView` comes back with after the press is `minStars={3}`.
  const FLOOR = { region: null, minRating: 4, limitMinutes: null };

  // This file drives the crumb by props, so the parent's answer to a press arrives as a re-render:
  // the commit in which `clear` leaves. `MapViewPlanHandoff.test.jsx` presses the real button through
  // the real `MapView` (Leaflet and the map's other children mocked), where the floor, reach and
  // scope resets land in that same commit.
  function crumb(props = {}) {
    return (
      <MapBreadcrumb
        carried={FLOOR}
        minStars={4}
        driveTimeFilter={0}
        onBack={vi.fn()}
        onClearRating={vi.fn()}
        onClearReach={vi.fn()}
        onClearScope={vi.fn()}
        onClearOrigin={vi.fn()}
        {...props}
      />
    );
  }
  const strip = () => screen.getByRole('navigation', { name: 'Where you came from' });
  const clearButton = () => screen.getByRole('button', { name: 'clear' });

  // Something ELSE placing focus in the commit that removes `clear`: a layout effect that runs before
  // the crumb's own, because the claimant renders first. It records where focus was when it claimed,
  // so a test can assert that premise rather than lean on the sibling order.
  function Claimant({ claim, seen }) {
    const ref = useRef(null);
    useLayoutEffect(() => {
      if (!claim) return;
      seen.push(document.activeElement);
      ref.current.focus();
    }, [claim, seen]);
    return <button ref={ref} type="button">claimant</button>;
  }

  it('lands the focus of a clear that removes itself on the strip, never on <body>', () => {
    const { rerender } = render(crumb());
    clearButton().focus();
    expect(clearButton()).toHaveFocus();

    rerender(crumb({ minStars: 3 }));

    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    // The strip, and not `← Plan`, the one control that survives: a key press reaching `← Plan`
    // leaves the Map tab, and a held Enter reaches it by key repeat straight after the press.
    expect(document.activeElement).toBe(strip());
    // …and it carries the class `index.css` keys the strip's ring on (the ring describe, below).
    expect(document.activeElement).toHaveClass('wf-map-breadcrumb');
  });

  it('makes the strip a place focus can be put, never a Tab stop', () => {
    render(crumb());
    expect(strip()).toHaveAttribute('tabindex', '-1');
  });

  it.each([
    [
      'the reach tier going back to Any',
      { carried: { region: null, minRating: null, limitMinutes: 90 }, driveTimeFilter: 90 },
      { driveTimeFilter: 0 },
    ],
    [
      'the region jump giving way to My area',
      { carried: { region: 'The Borders', minRating: null, limitMinutes: null }, regionInForce: 'The Borders' },
      { regionInForce: null },
    ],
    [
      'the origin going home',
      { carried: { region: null, minRating: null, limitMinutes: null }, origin: ORIGIN },
      { origin: null },
    ],
  ])('lands on the strip when the clause that ends is %s — whatever the floor does', (_label, before, after) => {
    // The floor stays at 3★ across the commit, so the handoff cannot be keyed to it: every other
    // test here removes `clear` through the rating clause.
    const { rerender } = render(crumb({ minStars: 3, ...before }));
    clearButton().focus();

    rerender(crumb({ minStars: 3, ...before, ...after }));

    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    expect(document.activeElement).toBe(strip());
  });

  it('leaves a clear that is still true after the reset where it is, with the reader on it — a carried floor '
      + 'equal to the map\'s own default', () => {
    // The map resets its floor to 3★, so a door that carried 3★+ still matches after `clear`: the
    // clause stands, React keeps the same button, and nothing may move a reader who is still on it.
    const carried = { region: null, minRating: 3, limitMinutes: null };
    const { rerender } = render(crumb({ carried, minStars: 3, origin: ORIGIN }));
    const clear = clearButton();
    clear.focus();
    fireEvent.click(clear);

    rerender(crumb({ carried, minStars: 3, origin: null }));

    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('carrying 3★+ clear');
    expect(clearButton()).toBe(clear);
    expect(document.activeElement).toBe(clear);
  });

  it('hands nothing on from a clear that never held focus — a press that moved no focus leaves <body> '
      + 'where it was', () => {
    // `fireEvent.click` moves no focus, so the reader was never on `clear`: only a focus that `clear`
    // actually held is handed on, and one arriving on the strip here would be one nobody moved.
    const { rerender } = render(crumb());
    expect(document.activeElement).toBe(document.body);
    fireEvent.click(clearButton());

    rerender(crumb({ minStars: 3 }));

    expect(document.activeElement).toBe(document.body);
  });

  it('takes no focus when clear leaves while the reader is on another control — the floor changed from '
      + 'the filters, say', () => {
    const { rerender } = render(<>{crumb()}<button type="button">elsewhere</button></>);
    const elsewhere = screen.getByRole('button', { name: 'elsewhere' });
    elsewhere.focus();

    rerender(<>{crumb({ minStars: 3 })}<button type="button">elsewhere</button></>);

    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    expect(document.activeElement).toBe(elsewhere);
  });

  it('never overrides a focus another component placed in the commit that removed clear', () => {
    const seen = [];
    const { rerender } = render(<><Claimant claim={false} seen={seen} />{crumb()}</>);
    clearButton().focus();

    rerender(<><Claimant claim seen={seen} />{crumb({ minStars: 3 })}</>);

    // The premise: the claimant found focus nowhere, in that commit, before the handoff asked.
    expect(seen).toHaveLength(1);
    expect(seen[0]).toBe(document.body);
    expect(screen.queryByRole('button', { name: 'clear' })).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'claimant' }));
  });

  it('spends its record when it stands down, too — a later render cannot pull the reader onto the strip', () => {
    // #871's mutant for the tick line: spend the record only when focus is handed. Here that would
    // keep the record through the stand-down, for the next render to act on.
    const seen = [];
    const { rerender } = render(<><Claimant claim={false} seen={seen} />{crumb()}</>);
    clearButton().focus();
    rerender(<><Claimant claim seen={seen} />{crumb({ minStars: 3 })}</>);
    const claimant = screen.getByRole('button', { name: 'claimant' });
    expect(claimant).toHaveFocus();

    // The reader clicks away onto something that takes no focus, and the crumb renders again later.
    claimant.blur();
    expect(document.activeElement).toBe(document.body);
    rerender(<><Claimant claim seen={seen} />{crumb({ minStars: 3, activeRow: ACTIVE_ROW })}</>);

    expect(document.activeElement).toBe(document.body);
  });

  it('spends its record when it acts — a later render cannot pull a reader back onto the strip', () => {
    const { rerender } = render(crumb());
    clearButton().focus();
    rerender(crumb({ minStars: 3 }));
    expect(document.activeElement).toBe(strip());

    // The reader clicks away onto something that takes no focus, and the crumb renders again later.
    strip().blur();
    expect(document.activeElement).toBe(document.body);
    rerender(crumb({ minStars: 3, activeRow: ACTIVE_ROW }));

    expect(document.activeElement).toBe(document.body);
  });

  it('lands in the commit that removed clear, before a dialog closing in that commit restores focus — so the '
      + 'dialog leaves the reader on the strip, not on its opener', () => {
    // No route closes a dialog and removes `clear` in one commit today. This pins the ORDER the
    // component's comment states, through the kind of reader that would see it: `useDialogFocus`'s
    // passive restore, which sends a reader it finds nowhere back to the dialog's opener.
    function Page({ open, minStars }) {
      return (
        <>
          <button type="button">opener</button>
          {crumb({ minStars })}
          {open && <Modal label="Sheet" onClose={() => {}}><p>inside</p></Modal>}
        </>
      );
    }
    const { rerender } = render(<Page open={false} minStars={4} />);
    screen.getByRole('button', { name: 'opener' }).focus();
    rerender(<Page open minStars={4} />);
    // The reader Tabs out of the dialog, which holds no trap, onto `clear`.
    clearButton().focus();
    expect(clearButton()).toHaveFocus();

    rerender(<Page open={false} minStars={3} />);

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(strip());
  });

  it('under StrictMode, a clear focused in the commit that places it is not recorded as having left — nothing '
      + 'does that today, and no record may rest on it', () => {
    // StrictMode re-runs a newly placed node's ref, cleanup first, while that node still holds the
    // focus it was just given. Left standing, that record would be spent by a later render.
    function FocusClearAsPlaced({ on }) {
      useLayoutEffect(() => {
        if (on) screen.queryByRole('button', { name: 'clear' })?.focus();
      });
      return null;
    }
    const { rerender } = render(
      <StrictMode>{crumb({ minStars: 3 })}<FocusClearAsPlaced on={false} /></StrictMode>,
    );
    rerender(<StrictMode>{crumb()}<FocusClearAsPlaced on /></StrictMode>);
    expect(clearButton()).toHaveFocus();
    // The reader clicks away onto something that takes no focus, and the crumb renders again later.
    clearButton().blur();
    expect(document.activeElement).toBe(document.body);

    rerender(<StrictMode>{crumb({ activeRow: ACTIVE_ROW })}<FocusClearAsPlaced on={false} /></StrictMode>);

    expect(document.activeElement).toBe(document.body);
  });
});

describe('MapBreadcrumb — the ring the strip draws when it takes focus', () => {
  // Pinned as text, because a jsdom cascade test would not hold (measured, jsdom 30.0.1). jsdom
  // decides `:focus-visible` from the key, mouse and focus events its selector engine has recorded,
  // so after this file's earlier clicks the strip's programmatic focus does not match. And a focus
  // change clears neither of its caches — selector matches (cleared by an attribute change) and
  // computed styles (cleared by any DOM change) — so a rule read before the focus, as `getByRole`
  // reads the strip while looking `clear` up, goes on answering "no" after it. The ring itself was
  // measured in Chromium, WebKit and Firefox. Comments are stripped before the rules are read,
  // because they hold both braces and class names.
  const CSS_PATH = resolve(process.cwd(), 'src/index.css');
  const RING = '.wf-map-breadcrumb:focus-visible';
  /** Every block at every depth, `@media` included: `{ prelude, declarations, depth }`. */
  function cssBlocks() {
    expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH} — run vitest from frontend/`).toBe(true);
    const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const blocks = [];
    const open = [];
    let start = 0;
    for (let i = 0; i < css.length; i += 1) {
      if ('{};'.includes(css[i])) {
        const text = css.slice(start, i).trim();
        if (css[i] === '{') {
          open.push({ prelude: text, declarations: [], depth: open.length });
          blocks.push(open.at(-1));
        } else {
          if (text && open.length) open.at(-1).declarations.push(text);
          if (css[i] === '}') open.pop();
        }
        start = i + 1;
      }
    }
    return blocks;
  }
  // The WHOLE selector, at the top level: a shared list, or a copy inside `@media`, cannot stand in.
  const isRing = (block) => block.depth === 0 && block.prelude === RING;

  it('is a real 2px outline in its buttons\' ring colour, inset by its own width, and declares nothing else — '
      + '`.wf-body--map` clips an outset ring, forced colours remove a box-shadow one, and a second outline '
      + 'declaration would undo either', () => {
    const rings = cssBlocks().filter(isRing);
    expect(rings, 'one top-level `.wf-map-breadcrumb:focus-visible` rule').toHaveLength(1);
    expect(rings[0].declarations).toEqual(['outline: 2px solid var(--color-plex-gold)', 'outline-offset: -2px']);
  });

  it('is the only rule anywhere in index.css — an at-rule or a longer selector included — that sets the '
      + 'strip\'s outline', () => {
    const others = cssBlocks()
      .filter((block) => /\.wf-map-breadcrumb(?![\w-])/.test(block.prelude) && !isRing(block))
      .filter((block) => block.declarations.some((declaration) => declaration.startsWith('outline')))
      .map((block) => block.prelude);
    expect(others).toEqual([]);
  });
});
