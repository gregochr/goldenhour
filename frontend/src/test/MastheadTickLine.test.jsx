import React from 'react';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MastheadTickLine from '../components/MastheadTickLine.jsx';

/**
 * The masthead's tick line — the design's "ONLY statement of where the plan is computed from".
 *
 * <p>Most of what is asserted here was salvaged from {@code MastheadLight.test.jsx}, which owned
 * the clock times and the postcode nudge until M3 split them out. Salvage by BEHAVIOUR, not by
 * file: the pair tests below still pin the responsive class pairs, the event names still have to
 * reach assistive technology, and the nudge still has to open the postcode field. What is new is
 * the origin control — three states, one of which changes what the button DOES — and the rule
 * about whose light the times are.
 */

/** Alnwick in midsummer, near enough — the handoff's own worked example. */
const LIGHT = {
  label: 'Home · NE66 1NG',
  shortLabel: 'NE66 1NG',
  civilDawn: '05:32',
  sunrise: '06:04',
  sunset: '19:58',
  civilDusk: '20:31',
};

const LAKES = { name: 'The Lake District', baseName: 'Keswick' };

const renderTick = (props = {}) => render(
  <MastheadTickLine
    light={LIGHT}
    origin={null}
    homePlace="Durham"
    onOpenSearch={vi.fn()}
    onGoHome={vi.fn()}
    onSetPostcode={vi.fn()}
    {...props}
  />,
);

describe('MastheadTickLine — the origin control', () => {
  /**
   * WCAG 2.5.3 in full, for all three arms — asserted through {@code getByRole} with an EXACT name.
   *
   * <p>⚠️ A `toContain` on the `aria-label` attribute is not this test. It cannot see the computed
   * name at all, and it passes under the two mutations that actually happen: transposing the two
   * interpolations (so the button announces the region as the town), and dropping the trailing
   * clause. Both were live in the first cut of this file, and the second arm below had no
   * assertion at all — `aria-label={undefined}` would have left an unnameable control, because the
   * visible span and the pin are both `aria-hidden`.
   *
   * <p>The away name also has to carry the two places IN THE ORDER THEY ARE DRAWN. The visible text
   * is `The Lake District · from Keswick`; the name this shipped with read "Planning from Keswick
   * in The Lake District", which transposes them, so a speech-input reader dictating what they
   * could see matched nothing.
   */
  it.each([
    ['home, place known', {}, 'Home · Durham', 'Planning from home · Durham. Search to change it.'],
    ['home, place unknown', { homePlace: undefined }, 'Home', 'Planning from home. Search to change it.'],
    ['away', { origin: LAKES }, 'The Lake District · from Keswick',
      'Planning from The Lake District, from Keswick. Search to change it.'],
  ])('names the origin exactly, %s', (_label, props, visible, name) => {
    renderTick(props);
    const button = screen.getByRole('button', { name });

    expect(button).toHaveAttribute('data-testid', 'window-first-origin-chip');
    expect(button.textContent).toBe(visible);
    // WCAG 2.5.3 proper: the visible words, IN THE ORDER THEY ARE DRAWN, inside the spoken name.
    // Case and the `·` separator are normalised away — the criterion is about the words and their
    // order, and it is the order that the transposition defect broke.
    const flatten = (text) => text.toLowerCase().replace(/[·,.]/g, ' ').replace(/\s+/g, ' ').trim();
    expect(flatten(name)).toContain(flatten(visible));
  });

  it('claims nothing about the setting while the place is not known yet', () => {
    // `undefined` is "we have not heard back". Home is still the origin — the absence of a
    // postcode is not the absence of an origin — so the button states the frame and stops there.
    renderTick({ homePlace: undefined, light: LIGHT });
    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
  });

  it.each([
    ['marks the group away once the origin has moved', { origin: LAKES }, 'true'],
    ['⚠️ and leaves it home otherwise, so the blue treatment cannot leak', {}, 'false'],
  ])('%s', (_label, props, expected) => {
    // The away treatment is a class hook on the GROUP, not on the button: the separator, the search
    // glyph and the pin beside it all take the same tint, and jsdom resolves no CSS, so the
    // attribute is the observable. Both arms, because an unconditional `'true'` would paint every
    // home reader's masthead blue and a one-sided assertion cannot see it.
    renderTick(props);
    expect(screen.getByTestId('window-first-origin-chip').closest('.wf-tick-group'))
      .toHaveAttribute('data-away', expected);
  });

  it('opens search from either the origin button or the ⌕, and they are separate controls', () => {
    // Two buttons rather than one, because in the empty state below they do different things —
    // and because a screen reader needs two names to tell "where you are planning from" apart
    // from "search".
    const onOpenSearch = vi.fn();
    renderTick({ onOpenSearch });

    fireEvent.click(screen.getByTestId('window-first-origin-chip'));
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(onOpenSearch).toHaveBeenCalledTimes(2);
  });

  it('offers the way home only when the origin has moved', () => {
    const onGoHome = vi.fn();
    const { rerender } = render(
      <MastheadTickLine
        light={LIGHT} origin={null} homePlace="Durham"
        onOpenSearch={vi.fn()} onGoHome={onGoHome} onSetPostcode={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('window-first-origin-home')).toBeNull();

    rerender(
      <MastheadTickLine
        light={LIGHT} origin={LAKES} homePlace="Durham"
        onOpenSearch={vi.fn()} onGoHome={onGoHome} onSetPostcode={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Plan from home again' }));
    expect(onGoHome).toHaveBeenCalledTimes(1);
  });
});

describe('MastheadTickLine — the postcode nudge, which is the origin button\'s empty state', () => {
  it.each([
    ['the settings response says there is no home', { homePlace: null, light: LIGHT }],
    ['settings have not answered and the light endpoint said 204', { homePlace: undefined, light: null }],
  ])('nudges when %s', (_label, props) => {
    renderTick(props);

    expect(screen.getByTestId('masthead-set-postcode')).toBeInTheDocument();
    expect(screen.queryByTestId('window-first-origin-chip')).toBeNull();
  });

  it('⚠️ but a saved home OUTRANKS a 204 from the light, rather than being OR-ed with it', () => {
    // The narrowing M3's review forced, and the case it removes: `useTodaysLight` never resets
    // `light` to `undefined` while it refetches, so on the round trip right after a reader SAVES a
    // postcode, settings resolve first and the light is still holding its old `null`. Under an OR
    // the line replaced their new home with "set a postcode" at the moment they acted on it — and
    // permanently, for any postcode that failed to geocode and answers 204 for good.
    renderTick({ homePlace: 'NE66 1NG', light: null });

    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · NE66 1NG');
  });

  it('⚠️ makes no claim about the account when the light fetch merely failed', () => {
    // A failed light fetch resolves to `undefined`, NOT `null` — `useTodaysLight` maps it that way
    // on purpose, because "you have not set a postcode" is a claim about the reader's account that
    // a 502 is no evidence for. Collapsing the two here would undo that at the render.
    renderTick({ homePlace: undefined, light: undefined });
    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
  });

  it('⚠️ withholds it while the origin is away, whatever the light says', () => {
    // An away reader is not planning from a postcode, so the prompt would be about nothing they
    // can see — the same rule the rail-footer line this replaces already followed. And the origin
    // button has a job in that state that the nudge would take away.
    renderTick({ origin: LAKES, homePlace: null, light: null });

    expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Keswick');
  });

  it('opens the postcode field rather than search, and says so in its name', () => {
    // ⚠️ The one state where the origin button does NOT open search. A button labelled "set a
    // postcode" that opened a search box would be a control whose label lies; search stays
    // reachable through the ⌕ beside it and through `/`.
    const onSetPostcode = vi.fn();
    const onOpenSearch = vi.fn();
    renderTick({ homePlace: null, onSetPostcode, onOpenSearch });

    fireEvent.click(screen.getByTestId('masthead-set-postcode'));
    expect(onSetPostcode).toHaveBeenCalledTimes(1);
    expect(onOpenSearch).not.toHaveBeenCalled();

    // …and search is still one click away, which is what makes the swap above safe.
    fireEvent.click(screen.getByTestId('window-first-search'));
    expect(onOpenSearch).toHaveBeenCalledTimes(1);
  });

  it('⚠️ keeps a name that CONTAINS both visible forms, at both widths', () => {
    // The phone renders the short form, which is not a self-explanatory control on its own. WCAG
    // 2.5.3 wants the spoken name to contain what is drawn — so the name is the LONG form, of which
    // the short one is a prefix. This shipped as "Set postcode", inherited from the row it replaced
    // whose visible words genuinely were "Set postcode"/"Set"; the copy changed and the label did
    // not, so NEITHER rendered string appeared in the name. Asserted as containment rather than as
    // a literal, because that is the criterion.
    renderTick({ homePlace: null });
    const wide = 'Set a postcode for light and drive times';
    const narrow = 'Set a postcode';
    const button = screen.getByRole('button', { name: wide });

    expect(within(button).getByText(wide)).toBeInTheDocument();
    expect(within(button).getByText(narrow)).toBeInTheDocument();
    expect(button.getAttribute('aria-label')).toContain(narrow);
  });
});

describe('MastheadTickLine — the width pairs', () => {
  /**
   * Every long/short pair in this component, and why presence alone does not cover them.
   *
   * These pairs render BOTH forms and let CSS pick one. `getByText` reads text nodes and no class
   * attribute, so emptying, deleting or SWAPPING the two visibility classes changes nothing any
   * presence assertion can see — an adversarial review found three of them unpinned on the
   * component this was salvaged from. jsdom resolves no media query, so the class pair IS the
   * observable. Asserted as an exact, complementary pair — one hidden below `sm`, one hidden from
   * `sm` up — because asserting only that both carry "some visibility class" is what let it
   * through last time.
   */
  it.each([
    ['nudge label',
      () => screen.getByText('Set a postcode'),
      () => screen.getByText('Set a postcode for light and drive times'),
      { homePlace: null }],
    ['kind word',
      null,
      () => within(screen.getAllByTestId('masthead-light-golden')[0]).getByText('golden'),
      {}],
  ])('shows exactly one of the %s pair at any width', (_label, phoneForm, wideForm, props) => {
    renderTick(props);

    if (phoneForm) {
      expect(phoneForm().className).toContain('sm:hidden');
      expect(phoneForm().className.split(/\s+/)).not.toContain('hidden');
    }
    expect(wideForm().className.split(/\s+/)).toContain('hidden');
    expect(wideForm().className).toContain('sm:inline');
  });

  it('drops only the blue pair at narrow widths, keeping the goldens at every size', () => {
    // The bundle shows four times on desktop AND iPad and two on a phone. `md` (768px) is the
    // breakpoint that gives exactly that — it was `lg` before the origin button joined the row,
    // and iPad at 834px would have lost two times it is meant to keep.
    renderTick();

    screen.getAllByTestId('masthead-light-blue')
      .forEach((n) => expect(n.className).toContain('hidden md:inline'));
    screen.getAllByTestId('masthead-light-golden')
      .forEach((n) => expect(n.className).not.toContain('hidden'));
  });
});

describe('MastheadTickLine — the times', () => {
  it('shows the two golden times and the two blue ones', () => {
    renderTick();

    expect(screen.getAllByTestId('masthead-light-golden').map((n) => n.textContent))
      .toEqual(['06:04 sunrise golden', '19:58 sunset golden']);
    expect(screen.getAllByTestId('masthead-light-blue').map((n) => n.textContent))
      .toEqual(['05:32 dawn blue', '20:31 dusk blue']);
  });

  it('announces the EVENT, not just the kind — the row is the whole accessible answer', () => {
    // The rule above is aria-hidden, so this row is all a screen reader gets. The kind alone does
    // not answer it: "golden" is the same word for sunrise and for sunset, so the announcement was
    // "05:32 blue, 06:04 golden, 19:58 golden, 20:31 blue" and the only thing separating morning
    // from evening was DOM order — exactly the positional cue the hidden gradient was carrying.
    renderTick();

    ['dawn', 'sunrise', 'sunset', 'dusk'].forEach((event) => {
      const span = screen.getByText(event);
      expect(span.className, `${event} must reach AT at every width`).toContain('sr-only');
      expect(span.className, `${event} must not be width-gated`).not.toContain('sm:not-sr-only');
    });
  });

  it('keeps the visible kind word out of the accessible name, so it cannot double up', () => {
    renderTick();
    const [morning] = screen.getAllByTestId('masthead-light-golden');

    expect(within(morning).getByText('golden')).toHaveAttribute('aria-hidden', 'true');
  });

  it('paints the golden times amber and weights them, so they read before the blues', () => {
    // The row's only visual hierarchy. Asserted on the resolved colour rather than on a class,
    // because the amber is an inline style — deliberately, so the rule's gradient and this row
    // cannot drift onto two different literals for the same accent.
    renderTick();

    screen.getAllByTestId('masthead-light-golden').forEach((n) => {
      expect(n).toHaveStyle({ color: 'rgb(224, 165, 66)' });
      expect(n.className).toContain('font-medium');
    });
    screen.getAllByTestId('masthead-light-blue').forEach((n) => {
      expect(n.style.color).toBe('');
      expect(n.className).not.toContain('font-medium');
    });
  });

  it('never labels solar noon', () => {
    // The pale band in the middle of the gradient already says midday, and the row's one line is
    // not spent on the least useful light of the day.
    renderTick();
    expect(screen.getByTestId('masthead-light-times').textContent).not.toMatch(/noon/i);
  });

  it('draws no times at all when there is no day, and still holds the line', () => {
    // The blank height-holding placeholder the old row needed is gone: this line always renders an
    // origin control, so it holds its own height and the page does not shift when the light lands.
    renderTick({ light: undefined });

    expect(screen.queryByTestId('masthead-light-times')).toBeNull();
    expect(screen.getByTestId('window-first-tickline')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-origin-chip')).toBeInTheDocument();
  });

  /**
   * Whose light the times are, which is the one claim this row cannot get wrong.
   *
   * <p>They are always the reader's HOME light — the endpoint is keyed on the saved postcode and an
   * away origin does not move it. At home the origin button says the place two elements to the
   * left, so drawing the label as well would state one postcode twice. Away it must be drawn:
   * otherwise a row reading "The Lake District · from Keswick   05:40 golden" attributes Durham's
   * sunrise to Cumbria, and the 20–30 minute spread across this country is exactly the size that
   * makes that wrong rather than merely imprecise.
   */
  it('speaks the label but does not draw it at home, where the origin button says the place', () => {
    renderTick();
    const label = screen.getByTestId('masthead-light-label');

    expect(label.className).toContain('sr-only');
    expect(label).toHaveTextContent('Home · NE66 1NG');
  });

  it('⚠️ DRAWS the label once the origin has moved, so the times are not read as the region\'s', () => {
    renderTick({ origin: LAKES });
    const label = screen.getByTestId('masthead-light-label');

    expect(label.className).not.toContain('sr-only');
    // ⚠️ The LONG form, in both channels. The backend documents `shortLabel` as the label "reduced
    // to what fits a phone" — a bare postcode — and the word it drops is "Home", which is the whole
    // of the attribution. Drawn short, this row would put `NE66 1NG` beside a Cumbrian origin and
    // say nothing about whose light it is, which is the defect the drawn label exists to prevent.
    expect(label.textContent).toBe('Home · NE66 1NG');
  });

  it('speaks the same long form at home, where it is the only channel it has', () => {
    renderTick();
    expect(screen.getByTestId('masthead-light-label').textContent).toBe('Home · NE66 1NG');
  });
});

describe('MastheadTickLine — while the search panel covers it', () => {
  /**
   * WCAG 2.4.11 (Focus Not Obscured, AA). The anchored panel is opaque and sits exactly over this
   * row, and the shared `Modal` deliberately has no focus trap — so a keyboard reader who tabs past
   * the search input reaches these controls, cannot see them, and presses Enter on something
   * invisible. The centred box this replaced merely dimmed them behind a backdrop.
   *
   * <p>`tabIndex={-1}` rather than `aria-hidden` (which would hide focusable content from assistive
   * tech without stopping focus reaching it) or `inert` (absent from this project's jsdom, so it
   * would fail as a silent no-op — `useDialogFocus` records the same finding).
   */
  it.each([
    ['the origin button', 'window-first-origin-chip', {}],
    ['the search affordance', 'window-first-search', {}],
    ['the way home', 'window-first-origin-home', { origin: LAKES }],
    ['the postcode nudge', 'masthead-set-postcode', { homePlace: null }],
  ])('takes %s out of the tab order', (_label, testId, props) => {
    const { rerender } = render(
      <MastheadTickLine
        light={LIGHT} origin={props.origin ?? null} homePlace={'homePlace' in props ? props.homePlace : 'Durham'}
        onOpenSearch={vi.fn()} onGoHome={vi.fn()} onSetPostcode={vi.fn()}
      />,
    );
    expect(screen.getByTestId(testId)).not.toHaveAttribute('tabindex');

    rerender(
      <MastheadTickLine
        light={LIGHT} origin={props.origin ?? null} homePlace={'homePlace' in props ? props.homePlace : 'Durham'}
        onOpenSearch={vi.fn()} onGoHome={vi.fn()} onSetPostcode={vi.fn()} searchOpen
      />,
    );
    expect(screen.getByTestId(testId)).toHaveAttribute('tabindex', '-1');
  });
});

/**
 * The Map tab's per-tab STATEMENT variant (map-tab-v2-plan.md §3 P11, README "Masthead change") —
 * "on a map, panning IS the search". `isMapTab` is a per-tab STATE of this one component, never a
 * fork: every describe block above (unchanged, `isMapTab` defaulting to {@code false} throughout)
 * is the "byte-identical on the other tabs" proof by construction — this block adds only what
 * changes when the prop flips.
 */
describe('MastheadTickLine — the Map tab statement (map-tab-v2-plan.md §3 P11)', () => {
  it('renders a non-interactive statement instead of the origin button, and withholds the ⌕ search button', () => {
    renderTick({ isMapTab: true });

    expect(screen.queryByTestId('window-first-origin-chip')).toBeNull();
    expect(screen.queryByTestId('window-first-search')).toBeNull();
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(statement.tagName).toBe('SPAN');
    expect(statement).toHaveTextContent('Home · Durham');
  });

  it('draws the caption, and only in the statement — never on the interactive arm', () => {
    const { rerender } = renderTick({ isMapTab: true });
    expect(screen.getByTestId('masthead-origin-caption')).toHaveTextContent('drive times from here');

    rerender(
      <MastheadTickLine
        light={LIGHT} origin={null} homePlace="Durham"
        onOpenSearch={vi.fn()} onGoHome={vi.fn()} onSetPostcode={vi.fn()}
        isMapTab={false}
      />,
    );
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();
  });

  it('drops the hairline separator along with the search button — nothing left for it to separate', () => {
    const { container } = renderTick({ isMapTab: true });
    expect(container.querySelector('.wf-tick-sep')).toBeNull();
  });

  it('still names the away origin correctly — the map tab does not stop being honest about where drive times come from', () => {
    renderTick({ isMapTab: true, origin: LAKES });

    expect(screen.queryByTestId('window-first-origin-chip')).toBeNull();
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(statement).toHaveTextContent('The Lake District · from Keswick');
    expect(statement).toHaveTextContent('drive times from here');
    // The way-home pin is a DIFFERENT control from the ⌕ search button withheld above — it is not a
    // text field, and leaving the origin remains reachable from every tab.
    expect(screen.getByTestId('window-first-origin-home')).toBeInTheDocument();
  });

  it('⚠️ the empty-state nudge survives untouched — CLAUDE.md\'s do-not-re-gate-the-postcode rule', () => {
    const onSetPostcode = vi.fn();
    renderTick({
      isMapTab: true, homePlace: null, light: LIGHT, onSetPostcode,
    });

    expect(screen.getByTestId('masthead-set-postcode')).toBeInTheDocument();
    // Still no caption and no search button on the map tab, but the nudge itself is the SAME
    // button (`onSetPostcode`, not a dead statement) every other tab already renders.
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();
    expect(screen.queryByTestId('window-first-search')).toBeNull();

    fireEvent.click(screen.getByTestId('masthead-set-postcode'));
    expect(onSetPostcode).toHaveBeenCalledTimes(1);
  });

  it('other tabs are unaffected — the origin button and the ⌕ search button both still render', () => {
    renderTick({ isMapTab: false });

    expect(screen.queryByTestId('window-first-origin-statement')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-search')).toBeInTheDocument();
  });
});

describe('MastheadTickLine — theme tokens it cites', () => {
  /**
   * Every `var(--…)` this component's stylesheet block emits must be a token `index.css` declares.
   *
   * <p>The guard `WindowSheetDialog.test.jsx` introduced, and the reason it exists rather than a
   * cascade test: <b>jsdom does not resolve `var()`</b>, so an undefined token renders as inherited
   * and every colour assertion in the suite passes anyway. That is exactly how M2 shipped a "Maybe"
   * badge in bone, on `--color-badge-marginal`, which `@theme static` has never declared.
   *
   * <p>⚠️ <b>It detects UNDECLARED, not PRUNED</b>, and the difference is worth stating because the
   * incident above is usually told as a pruning story. A token declared in the plain `@theme` block
   * is emitted only while some utility class references its name literally; this check passes on
   * such a token. It cannot be tightened to `@theme static` alone without failing on
   * `--color-plex-coral`, which is genuinely in the plain block and genuinely alive. What it does
   * buy is the case M2 actually hit — a name that is declared nowhere at all.
   *
   * <p>Read as text rather than through the cascade for the same reason.
   */
  const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

  // BOTH stylesheet blocks M3 adds, not just the tick line's — the search panel's rules cite
  // `--color-badge-tide` and two ink tokens, and leaving the newer block unguarded would put the
  // guard on the surface that has already been checked and not on the one that has not.
  it.each([
    ['the tick line', '/* ── The tick line (M3) ──', '.wf-tabs {'],
    ['the search panel', '.wf-search-anchored {', '/* ── P8 · the four-day location sheet'],
  ])('cites only tokens the stylesheet declares — %s', (_label, from, to) => {
    const css = read('../index.css');
    const start = css.indexOf(from);
    expect(start, `${from} must still be findable`).toBeGreaterThan(-1);
    const end = css.indexOf(to, start);
    expect(end, `${to} must still follow it`).toBeGreaterThan(start);
    const block = css.slice(start, end);

    const cited = [...new Set([...block.matchAll(/var\((--[a-z0-9-]+)/g)].map((m) => m[1]))];
    expect(cited.length, 'the block must cite some tokens, or this test proves nothing')
      .toBeGreaterThan(2);

    cited.forEach((token) => {
      // ⚠️ `--wf-*` are the arm's own runtime properties — written by `useLensReserve` or declared
      // on `.wf-shell` — so they are checked against the same stylesheet by the same rule rather
      // than waved through: a typo'd `--wf-mast-height` must fail here like anything else. Only
      // `--font-*` is exempt, and only because Tailwind emits those from its own theme.
      const declared = token.startsWith('--font-')
        || new RegExp(`^\\s*${token}:`, 'm').test(css);
      expect(declared, `${token} is cited by this block but never declared`).toBe(true);
    });
  });
});
