import React, { useLayoutEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  describe, it, expect, vi, afterEach,
} from 'vitest';
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
 * The origin as a STATEMENT wherever the shell hands over no search handler — every tab but Plan.
 *
 * <p>Search finds only Plan objects, and until 2026-09-16 a pick made from the ⌕ or the origin button
 * on Coming up opened a Plan dialog over the feed with the tab unmoved. So the shell hands the tick
 * line `onOpenSearch` on the Plan tab only, and with none the line withholds both search controls:
 * the origin is drawn as the statement the Map tab already draws, and the ⌕ is not drawn at all. The
 * Map tab keeps its own rule and its caption, in the block below.
 */
describe('MastheadTickLine — with no search handler (every tab but Plan)', () => {
  const noSearch = { onOpenSearch: undefined };

  it('renders a non-interactive statement instead of the origin button, and withholds the ⌕', () => {
    renderTick(noSearch);

    expect(screen.queryByTestId('window-first-origin-chip')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Search days, regions and places' })).toBeNull();
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(statement.tagName).toBe('SPAN');
    expect(statement).toHaveTextContent('Home · Durham');
  });

  it('draws no caption — "drive times from here" is the Map tab\'s, and no drive time is on screen here', () => {
    renderTick(noSearch);
    expect(screen.getByTestId('window-first-origin-statement')).toHaveTextContent('Home · Durham');
    expect(screen.queryByTestId('masthead-origin-caption')).toBeNull();
  });

  /**
   * `.wf-tick-group:hover` brightens the bordered pill, which reads as "this is a control", and
   * `data-statement` is the hook `index.css` neutralises that with. jsdom resolves no CSS, so the
   * attribute is the observable. Every arm, because an unconditional value is the defect in either
   * direction.
   *
   * <p>⚠️ The NUDGE arm is the one a mutation sweep found unpinned. The nudge renders ahead of the
   * statement whatever `statement` says, so a derivation that dropped its `!noHome` term still drew
   * the nudge, and every other test passed — while its group claimed to be a statement and stopped
   * reacting to the pointer, on exactly the tabs that now have no search handler.
   */
  it.each([
    ['the statement, which does nothing', { ...noSearch }, 'window-first-origin-statement', 'true'],
    ['the origin button, which opens search', {}, 'window-first-origin-chip', 'false'],
    ['⚠️ the nudge, which is a control on every tab', { ...noSearch, homePlace: null },
      'masthead-set-postcode', 'false'],
  ])('marks the group as a statement only when it holds one — %s', (_label, props, testId, expected) => {
    renderTick(props);
    expect(screen.getByTestId(testId).closest('.wf-tick-group'))
      .toHaveAttribute('data-statement', expected);
  });

  it('still names an away origin, and keeps the way home, which moves the origin rather than searching', () => {
    const onGoHome = vi.fn();
    renderTick({ ...noSearch, origin: LAKES, onGoHome });

    expect(screen.getByTestId('window-first-origin-statement'))
      .toHaveTextContent('The Lake District · from Keswick');
    // The way-home pin is a DIFFERENT control from the ⌕ withheld above: it has a visible effect of
    // its own, so leaving the origin stays reachable from every tab.
    fireEvent.click(screen.getByRole('button', { name: 'Plan from home again' }));
    expect(onGoHome).toHaveBeenCalledTimes(1);
  });

  it('⚠️ keeps the empty-state nudge — CLAUDE.md\'s do-not-re-gate-the-postcode rule', () => {
    // The nudge opens settings, not search, so its job exists on every tab.
    const onSetPostcode = vi.fn();
    renderTick({ ...noSearch, homePlace: null, onSetPostcode });

    expect(screen.queryByTestId('window-first-origin-statement')).toBeNull();
    expect(screen.queryByTestId('window-first-search')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Set a postcode for light and drive times' }));
    expect(onSetPostcode).toHaveBeenCalledTimes(1);
  });
});

/**
 * The Map tab's per-tab STATEMENT variant (map-tab-v2-plan.md §3 P11, README "Masthead change") —
 * "on a map, panning IS the search". `isMapTab` is a per-tab STATE of this one component, never a
 * fork, and this block adds only what changes when the prop flips.
 *
 * <p>⚠️ It is no longer the only statement. Coming up and Operations draw one too since 2026-09-16,
 * through the absence of a search handler (the block above). The Map keeps its OWN rule here: every
 * test in this block hands over a handler (the `renderTick` default) and still gets no search,
 * because panning is the search on the map whatever a caller passes. The caption stays the Map's
 * alone. The blocks further above render with a handler and without `isMapTab`, which is the Plan
 * tab, not "the other tabs".
 */
describe('MastheadTickLine — the Map tab statement (map-tab-v2-plan.md §3 P11)', () => {
  it('renders a non-interactive statement instead of the origin button, and withholds the ⌕ search button', () => {
    // WITH a handler, as `renderTick` hands one over: the Map's own rule, not the shell's handler.
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

  it('the Plan tab is unaffected — handed a handler off the map, both search buttons still render', () => {
    renderTick({ isMapTab: false });

    expect(screen.queryByTestId('window-first-origin-statement')).toBeNull();
    expect(screen.getByTestId('window-first-origin-chip')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-search')).toBeInTheDocument();
  });

  it('keeps the statement out of the Tab order — focus can be PUT there, never Tabbed to', () => {
    // "Panning IS the search": the statement is not a control, so it must not be a tab stop. It is
    // a place focus can be put, for the one route that needs it (the block below).
    renderTick({ isMapTab: true });
    expect(screen.getByTestId('window-first-origin-statement')).toHaveAttribute('tabindex', '-1');
  });
});

/**
 * The origin slot keeps the reader's focus when its element is swapped.
 *
 * <p>From an accessibility review of #842. On the Map tab a known home replaces the "set a postcode"
 * nudge's `<button>` with the statement's `<span>` — a different element, so a focused nudge was
 * destroyed and focus fell to `<body>`. It holds focus at that moment when the settings dialog has
 * already handed it back: the dialog closed before its own settings read answered, and the answer
 * named a home saved elsewhere. (A save made IN the dialog swaps it while the dialog holds focus —
 * the resolver case at the end of this block.) On every other tab the nudge becomes the origin
 * BUTTON, React reuses the node, and focus rides it.
 */
describe('MastheadTickLine — the origin slot keeps focus when its element is swapped', () => {
  const tick = (props = {}) => (
    <MastheadTickLine
      light={LIGHT}
      origin={null}
      homePlace="Durham"
      onOpenSearch={vi.fn()}
      onGoHome={vi.fn()}
      onSetPostcode={vi.fn()}
      {...props}
    />
  );
  // A control outside the line — somewhere real a reader can have gone.
  let elsewhere = null;
  afterEach(() => { elsewhere?.remove(); elsewhere = null; });
  const outsideButton = () => {
    elsewhere = document.createElement('button');
    document.body.appendChild(elsewhere);
    return elsewhere;
  };

  it('⚠️ on the Map tab, a saved home hands focus from the nudge to the statement replacing it', () => {
    const { rerender } = render(tick({ isMapTab: true, homePlace: null }));
    const nudge = screen.getByRole('button', { name: 'Set a postcode for light and drive times' });
    nudge.focus();

    rerender(tick({ isMapTab: true, homePlace: 'Durham' }));

    expect(nudge.isConnected, 'precondition: a different element, not the nudge reused').toBe(false);
    expect(document.activeElement).toBe(screen.getByTestId('window-first-origin-statement'));
  });

  it('and back the other way — a statement holding focus hands it to a nudge that replaces it', () => {
    // A home cleared elsewhere, read back by the settings dialog as no postcode, puts the nudge back.
    // The rule is the slot's, not the route's: whichever element leaves holding focus hands it on.
    const { rerender } = render(tick({ isMapTab: true, homePlace: 'Durham' }));
    const statement = screen.getByTestId('window-first-origin-statement');
    statement.focus();
    expect(document.activeElement, 'precondition').toBe(statement);

    rerender(tick({ isMapTab: true, homePlace: null }));

    expect(document.activeElement).toBe(screen.getByTestId('masthead-set-postcode'));
  });

  it('on the other tabs the node is reused, so focus simply stays on it', () => {
    const { rerender } = render(tick({ homePlace: null }));
    const nudge = screen.getByTestId('masthead-set-postcode');
    nudge.focus();

    rerender(tick({ homePlace: 'Durham' }));

    const chip = screen.getByTestId('window-first-origin-chip');
    expect(chip, 'the same node, now the origin button').toBe(nudge);
    expect(document.activeElement).toBe(chip);
  });

  it('leaves focus where it is when the swap happens while the reader is elsewhere', () => {
    // Nothing is recorded — the nudge did not hold focus — so no handoff is even considered. The
    // "somewhere else" check itself (a focus PLACED in the same commit) has its own test below.
    const { rerender } = render(tick({ isMapTab: true, homePlace: null }));
    outsideButton().focus();

    rerender(tick({ isMapTab: true, homePlace: 'Durham' }));

    expect(document.activeElement).toBe(elsewhere);
  });

  it('⚠️ does not pull focus onto the statement when the nudge never held it — even from <body>', () => {
    // The rule is "the element that LEFT held focus", not "focus is nowhere after a swap". The
    // second reads a reader who was never here as one who was.
    const { rerender } = render(tick({ isMapTab: true, homePlace: null }));
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);

    rerender(tick({ isMapTab: true, homePlace: 'Durham' }));

    expect(document.activeElement).toBe(document.body);
  });

  it('⚠️ nor when a switch to the Map tab swaps the origin button for the statement', () => {
    // The route that rules the looser test out: the shell's `tabRequest` handoff switches tabs with
    // focus wherever a closing dialog left it — `<body>` — and moves it to the tab itself a frame
    // later. A statement grabbing it first would be announced for nothing.
    const { rerender } = render(tick({ isMapTab: false }));
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);

    rerender(tick({ isMapTab: true }));

    expect(document.activeElement).toBe(document.body);
  });

  it('never takes focus that something else placed in the same commit', () => {
    // A sibling rendered BEFORE the line runs its layout effect first. Whatever it focuses in the
    // commit that swaps the slot, the handoff must not overwrite — a placed focus is a decision.
    function PlacesFocus({ on }) {
      useLayoutEffect(() => { if (on) elsewhere.focus(); }, [on]);
      return null;
    }
    PlacesFocus.propTypes = { on: PropTypes.bool.isRequired };
    outsideButton();
    const { rerender } = render(<><PlacesFocus on={false} />{tick({ isMapTab: true, homePlace: null })}</>);
    screen.getByTestId('masthead-set-postcode').focus();

    rerender(<><PlacesFocus on />{tick({ isMapTab: true, homePlace: 'Durham' })}</>);

    expect(document.activeElement).toBe(elsewhere);
  });

  it('⚠️ under StrictMode, hands off once and takes nothing back on a later unrelated render', () => {
    // The app mounts under StrictMode in development, which re-runs a newly mounted node's ref —
    // cleanup, then setup — after the commit. That cleanup sees the statement this handoff has just
    // focused, so a flag that only recorded "something focused left" outlived the commit, and the
    // line's next render took a reader who had clicked away straight back onto the statement.
    const strict = (props) => <React.StrictMode>{tick(props)}</React.StrictMode>;
    const { rerender } = render(strict({ isMapTab: true, homePlace: null, light: null }));
    screen.getByTestId('masthead-set-postcode').focus();

    rerender(strict({ isMapTab: true, homePlace: 'Durham', light: null }));
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(document.activeElement, 'control: the handoff itself still happens').toBe(statement);

    statement.blur();           // the reader clicks somewhere that takes no focus
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);
    rerender(strict({ isMapTab: true, homePlace: 'Durham', light: LIGHT })); // the light arrives

    expect(document.activeElement).toBe(document.body);
  });

  it('⚠️ under StrictMode, takes nothing back when the next render REMOVES the statement either', () => {
    // A review's reproduction of the first fix's gap: that fix only refused a record whose node was
    // still attached, so a tab switch that really removed the statement spent StrictMode's stale
    // record and put the reader on the origin button — in macOS Safari a pointer on the Plan tab
    // leaves focus on <body>, exactly the "nowhere" the handoff acts on.
    const strict = (props) => <React.StrictMode>{tick(props)}</React.StrictMode>;
    const { rerender } = render(strict({ isMapTab: true, homePlace: null, light: null }));
    screen.getByTestId('masthead-set-postcode').focus();
    rerender(strict({ isMapTab: true, homePlace: 'Durham', light: null }));
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(document.activeElement, 'control: the handoff itself still happens').toBe(statement);
    statement.blur();

    rerender(strict({ isMapTab: false, homePlace: 'Durham', light: null })); // to the Plan tab

    expect(statement.isConnected, 'precondition: the statement really left').toBe(false);
    expect(document.activeElement).toBe(document.body);
  });

  it('⚠️ spends the handoff once, without StrictMode too — a later render takes nothing back', () => {
    // StrictMode's own re-run hides this one (it rewrites the record either way), so it needs a
    // plain render: a record left standing after the handoff would pull the reader back on the
    // line's next render, in production builds.
    const { rerender } = render(tick({ isMapTab: true, homePlace: null, light: null }));
    screen.getByTestId('masthead-set-postcode').focus();
    rerender(tick({ isMapTab: true, homePlace: 'Durham', light: null }));
    const statement = screen.getByTestId('window-first-origin-statement');
    expect(document.activeElement, 'control: the handoff happened').toBe(statement);
    statement.blur();
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);

    rerender(tick({ isMapTab: true, homePlace: 'Durham', light: LIGHT })); // the light arrives

    expect(document.activeElement).toBe(document.body);
  });

  it('the nudge hands its caller a way to find the slot\'s CURRENT element', () => {
    // For the ordinary order: a save moves the home while the dialog is still OPEN, the nudge is
    // replaced under it, and its recorded opener is detached by the close. `App` gives the dialog
    // this, to ask at close time what stands in the nudge's place.
    const onSetPostcode = vi.fn();
    const { rerender } = render(tick({ isMapTab: true, homePlace: null, onSetPostcode }));
    const nudge = screen.getByTestId('masthead-set-postcode');
    fireEvent.click(nudge);

    expect(onSetPostcode).toHaveBeenCalledTimes(1);
    const findSlot = onSetPostcode.mock.calls[0][0];
    expect(typeof findSlot).toBe('function');
    expect(findSlot()).toBe(nudge);

    rerender(tick({ isMapTab: true, homePlace: 'Durham', onSetPostcode }));

    expect(findSlot()).toBe(screen.getByTestId('window-first-origin-statement'));
  });
});

/**
 * ⌂ goes on its own press, and hands the reader to the origin slot rather than dropping them.
 *
 * <p>Found by reading the code once the slot handoff above existed, and reproduced here before it
 * was fixed: ⌂ renders only while away, and its press returns the origin home — so the press
 * destroys the node it was made on, and on every tab focus fell to `<body>`, where the next Tab
 * starts at the top of the document and a screen reader loses its place.
 *
 * <p>⚠️ `fireEvent.click` does not move focus, so a test that means a KEYBOARD press focuses ⌂
 * first, as a keyboard reader must. The one that leaves it unfocused models the press that
 * genuinely does not focus a button: a pointer in macOS Safari.
 */
describe('MastheadTickLine — ⌂ hands focus to the origin slot when its own press removes it', () => {
  /**
   * Holds the origin as state — the provider's own shape, whose `setOrigin` is a plain setter — so
   * ⌂'s press and its removal land in ONE commit, as they do in the app; `rerender` would split
   * them. A harness, and said so: the shell's route, with the popup its `onGoHome` also closes, is
   * pinned through the real shell in `planOriginShell.test.jsx`.
   */
  function Away({ before = null, ...props }) {
    const [origin, setOrigin] = useState(LAKES);
    return (
      <>
        {before?.(origin)}
        <MastheadTickLine
          light={LIGHT}
          origin={origin}
          homePlace="Durham"
          onOpenSearch={vi.fn()}
          onGoHome={() => setOrigin(null)}
          onSetPostcode={vi.fn()}
          {...props}
        />
      </>
    );
  }
  /** Focuses `target()` in the commit `on` turns true — a layout effect, so it runs in that commit. */
  function FocusWhen({ on, target }) {
    useLayoutEffect(() => { if (on) target().focus(); }, [on, target]);
    return null;
  }
  const home = () => screen.getByRole('button', { name: 'Plan from home again' });
  // A control outside the line — somewhere real a reader can have gone.
  let elsewhere = null;
  const toElsewhere = () => elsewhere;
  afterEach(() => { elsewhere?.remove(); elsewhere = null; });

  it('⚠️ lands on the origin button, which now says where the plan is from', () => {
    render(<Away />);
    const button = home();
    button.focus();

    fireEvent.click(button);

    expect(button.isConnected, 'precondition: the press removed ⌂').toBe(false);
    expect(document.activeElement)
      .toBe(screen.getByRole('button', { name: 'Planning from home · Durham. Search to change it.' }));
  });

  it.each([
    ['on the Map tab', { isMapTab: true }],
    // Since #860 the shell hands `onOpenSearch` to the Plan tab alone, and a slot with no search to
    // open draws the statement on every tab. The landing is whatever the slot holds, so this arm pins
    // that the handoff does not quietly depend on `isMapTab`.
    ['on a tab with no search — Coming up, Operations', { onOpenSearch: undefined }],
  ])('⚠️ lands on the statement %s — a place focus can be put, never a tab stop', (_label, props) => {
    render(<Away {...props} />);
    const button = home();
    button.focus();

    fireEvent.click(button);

    const statement = screen.getByTestId('window-first-origin-statement');
    expect(document.activeElement).toBe(statement);
    expect(statement).toHaveTextContent('Home · Durham');
  });

  it.each([
    ['on the Plan tab, where the origin button\'s node BECOMES the nudge', { isMapTab: false }, false],
    ['on the Map tab, where the nudge REPLACES the statement in the same commit', { isMapTab: true }, true],
    ['on a tab with no search, where it replaces the statement too', { onOpenSearch: undefined }, true],
  ])('with no home saved, lands on the nudge — %s', (_label, props, replaced) => {
    // Going home with no postcode turns the slot's origin into its nudge, by one of two mechanisms,
    // and each arm checks it took its own. On the Plan tab React reuses the origin button's node.
    // Wherever the slot held the statement instead, the statement is swapped for the nudge in the very
    // commit ⌂ leaves, so the target is an element that attached a moment ago.
    render(<Away homePlace={null} {...props} />);
    const slotBefore = screen.getByTestId(replaced ? 'window-first-origin-statement' : 'window-first-origin-chip');
    const button = home();
    button.focus();

    fireEvent.click(button);

    const nudge = screen.getByRole('button', { name: 'Set a postcode for light and drive times' });
    expect(nudge === slotBefore, 'precondition: reused on the Plan tab, replaced where a statement stood')
      .toBe(!replaced);
    expect(document.activeElement).toBe(nudge);
  });

  it('⚠️ does not pull focus onto the slot when ⌂ never held it — even from <body>', () => {
    // The slot's rule: "the element that LEFT held focus", never "focus is nowhere after ⌂ goes". A
    // pointer press in macOS Safari does not focus a button, so a reader who clicked ⌂ there was
    // never on it, and landing them on the origin button would be a move nobody asked for.
    render(<Away />);
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);

    fireEvent.click(home());

    expect(screen.queryByRole('button', { name: 'Plan from home again' }), 'precondition: ⌂ went')
      .toBeNull();
    expect(document.activeElement).toBe(document.body);
  });

  it('never takes focus that something else placed in the commit ⌂ leaves in', () => {
    // A sibling rendered BEFORE the line runs its layout effect first. Whatever it focuses as ⌂
    // goes, the handoff must not overwrite — a placed focus is a decision.
    elsewhere = document.createElement('button');
    document.body.appendChild(elsewhere);
    render(<Away before={(origin) => <FocusWhen on={origin == null} target={toElsewhere} />} />);
    const button = home();
    button.focus();

    fireEvent.click(button);

    expect(button.isConnected, 'precondition: the press removed ⌂').toBe(false);
    expect(document.activeElement).toBe(elsewhere);
  });

  it('⚠️ under StrictMode, a ⌂ focused as it mounts is not recorded as having left', () => {
    // Nothing in the app focuses ⌂ in the commit that mounts it today, and the record must not rest
    // on that. StrictMode re-runs a newly mounted node's ref after the commit, cleanup first, so a ⌂
    // holding focus then reads exactly like one that left; unguarded, that record outlived the
    // commit, and the line's NEXT render — the light arriving — pulled a reader who had since gone
    // nowhere onto the origin button. The slot's StrictMode tests above are the same defect on the
    // elements inside it, and one guard (`watchDeparture`) answers both.
    const toHome = () => home();
    const strict = (props) => (
      <React.StrictMode>
        <MastheadTickLine
          light={null}
          origin={null}
          homePlace="Durham"
          onOpenSearch={vi.fn()}
          onGoHome={vi.fn()}
          onSetPostcode={vi.fn()}
          {...props}
        />
        <FocusWhen on={props.origin != null} target={toHome} />
      </React.StrictMode>
    );
    const { rerender } = render(strict({ origin: null }));

    rerender(strict({ origin: LAKES }));
    const button = home();
    expect(document.activeElement, 'precondition: ⌂ held focus through StrictMode\'s re-run').toBe(button);
    button.blur(); // the reader clicks somewhere that takes no focus
    expect(document.activeElement, 'precondition: focus is nowhere').toBe(document.body);

    rerender(strict({ origin: LAKES, light: LIGHT })); // the light arrives

    expect(document.activeElement).toBe(document.body);
  });
});

describe('MastheadTickLine — the focus rule keeps an outline for forced colours', () => {
  /**
   * `.wf-tick-origin`/`.wf-tick-search`/`.wf-tick-home`'s focus ring is an inset box-shadow, which
   * forced-colours mode (Windows High Contrast) removes. A TRANSPARENT outline beside it is what that
   * mode repaints in a system colour — measured in Chromium's forced-colours emulation, where the
   * statement focus is handed to showed nothing before it. jsdom renders no CSS, so the rule's text
   * is what can be pinned: an "every sibling rule says `outline: none`" tidy-up must fail here.
   */
  // The path goes through a parameter, as in the token test below: Vite rewrites a LITERAL
  // `new URL('…', import.meta.url)` into an asset URL, which is not a file path.
  const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
  const FOCUS = ['.wf-tick-origin:focus-visible', '.wf-tick-search:focus-visible', '.wf-tick-home:focus-visible'];
  // Every top-level rule as [its selector list, its body], comments stripped. Matched by the WHOLE
  // list: ⌂'s own rule has the same selector that ends the shared list, so a substring search finds
  // the shared rule and reads its body as ⌂'s.
  const rules = () => read('../index.css')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('}')
    .map((chunk) => {
      const open = chunk.lastIndexOf('{');
      return [chunk.slice(0, open).split(',').map((selector) => selector.trim()).join(', '), chunk.slice(open + 1)];
    });
  const rule = (selectorList) => {
    const found = rules().filter(([list]) => list === selectorList);
    expect(found, `exactly one rule for ${selectorList}`).toHaveLength(1);
    return found[0][1];
  };

  it('draws a transparent inset outline on the shared focus rule, and no rule for these takes it away', () => {
    const shared = rule(FOCUS.join(', '));
    expect(shared).toMatch(/outline:\s*2px solid transparent;/);
    expect(shared).toMatch(/outline-offset:\s*-2px;/);

    const touching = rules().filter(([list]) => list.split(', ').some((selector) => FOCUS.includes(selector)));
    expect(touching.length, 'the shared rule and ⌂\'s own').toBe(2);
    for (const [list, body] of touching) expect(body, list).not.toMatch(/outline(-style)?:\s*none/);
  });

  it('insets ⌂\'s outline past its own 1px border, so forced colours draws the full 2px', () => {
    expect(rule('.wf-tick-home:focus-visible')).toMatch(/outline-offset:\s*-3px;/);
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
