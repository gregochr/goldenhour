/**
 * `components/map/MapLandingCard.jsx` — the rendering half of the landing card
 * (`docs/engineering/map-landing-plan.md` §3 L4).
 *
 * <p>The SELECTION rules live in `utils/mapLanding.js` and are pinned in `mapLanding.test.js`;
 * this file asserts what the model turns into on screen — the medallion-versus-quiet-line split,
 * the all-Poor sentence, the accessible name, and the three dismissal routes' handlers. Its
 * fixtures are real `landingCardModel` output rather than hand-written objects, so a change to the
 * model's shape fails here too instead of leaving this file asserting on a shape nothing produces.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MapLandingCard from '../components/map/MapLandingCard.jsx';
import { landingCardModel } from '../utils/mapLanding.js';
import { EVENT_KIND } from '../utils/mapEvents.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';
const THURSDAY = '2026-01-22';

function solar(date, eventType, { dayLabel, time = '16:12', pickKind = null, served = true } = {}) {
  const word = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const day = dayLabel ?? (date === TODAY ? 'Today' : 'Tomorrow');
  return {
    id: `solar:${date}:${eventType}`,
    kind: EVENT_KIND.SOLAR,
    eventType,
    date,
    label: `${day} ${word}`,
    dayLabel: day,
    time,
    served,
    scored: served,
    pickKind,
  };
}

const verdict = (tier, extra = {}) => ({
  tier, regionName: 'The Lakes', sharingCount: 0, allInScope: false, scopedRegionCount: 2, ...extra,
});

/** The all-Poor state — both rows Poor with a later Worth it. Hoisted; four tests drive it. */
const POOR_VERDICTS = new Map([
  [`solar:${TODAY}:SUNSET`, verdict('STAND_DOWN')],
  [`solar:${TOMORROW}:SUNRISE`, verdict('STAND_DOWN')],
  [`solar:${THURSDAY}:SUNSET`, verdict('WORTH_IT', { regionName: 'The Peak' })],
]);

const EVENTS = [
  solar(TODAY, 'SUNSET', { dayLabel: 'Tonight', time: '20:28', pickKind: 'also' }),
  solar(TOMORROW, 'SUNRISE', { time: '05:42' }),
  solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday', time: '20:31', pickKind: 'best' }),
];

const VERDICTS = new Map([
  [`solar:${TODAY}:SUNSET`, verdict('WORTH_IT')],
  [`solar:${TOMORROW}:SUNRISE`, verdict('MAYBE', { regionName: 'North East', sharingCount: 2 })],
  [`solar:${THURSDAY}:SUNSET`, verdict('WORTH_IT', { regionName: 'The Peak' })],
]);

function renderCard({ events = EVENTS, verdicts = VERDICTS, ...props } = {}) {
  const onSelect = vi.fn();
  const onDismiss = vi.fn();
  const result = render(
    <MapLandingCard
      model={landingCardModel({ events, verdicts })}
      scopeLabel="My area"
      onSelect={onSelect}
      onDismiss={onDismiss}
      {...props}
    />,
  );
  return { ...result, onSelect, onDismiss };
}

describe('MapLandingCard — the two rows', () => {
  it('draws the two windows with kind, day, time, verdict and region', () => {
    renderCard();

    const rows = screen.getAllByTestId('wf-land-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('sunset');
    expect(rows[0]).toHaveTextContent('Tonight');
    expect(rows[0]).toHaveTextContent('20:28');
    expect(within(rows[0]).getByTestId('wf-land-verdict-word')).toHaveTextContent('Worth it');
    expect(within(rows[0]).getByTestId('wf-land-verdict-region')).toHaveTextContent('The Lakes');
    // The pill's own `verdictRegionLabel`, so the card and the control cannot print different words.
    expect(within(rows[1]).getByTestId('wf-land-verdict-region')).toHaveTextContent('North East +2');
  });

  it('names the header and the scope, and marks the window in force', () => {
    renderCard({ activeId: `solar:${TOMORROW}:SUNRISE` });

    expect(screen.getByTestId('wf-land-head')).toHaveTextContent('Tonight, or tomorrow?');
    expect(screen.getByTestId('wf-land-sub')).toHaveTextContent('Your next two windows · My area');
    // ⚠️ Asserted as ARIA state, not as a class substring. `.className.toContain('on')` passes
    // today only because the literal "wf-land-row" happens to contain no "on" — any sibling
    // modifier (`…-none`, `…-soon`) would invert it — and it pins a paint detail in place of the
    // state contract a screen reader actually reads.
    const rows = screen.getAllByTestId('wf-land-row');
    expect(rows[0]).not.toHaveAttribute('aria-current');
    expect(rows[1]).toHaveAttribute('aria-current', 'true');
    expect(rows[1].className).toContain('on');
  });

  it('names each row by its own window, through the ROLE and the accessible name', () => {
    // ⚠️ Asserted through `getByRole(…, { name })` rather than `textContent` — they are different
    // computations, and a test named "accessible name" that reads `textContent` cannot tell them
    // apart: an `aria-hidden` on the day/time span, or an `aria-label` on the button (which
    // REPLACES the subtree), leaves `textContent` untouched. Whitespace-tolerant, because jsdom's
    // accname glues sibling contributions where every real engine spaces them.
    renderCard();

    expect(screen.getByRole('button', { name: /sunset\s*Tonight\s*20:28\s*Also good\s*Worth it\s*The Lakes/ }))
      .toHaveAttribute('data-ev-id', `solar:${TODAY}:SUNSET`);
    expect(screen.getByRole('button', { name: /sunrise\s*Tomorrow\s*05:42/ }))
      .toHaveAttribute('data-ev-id', `solar:${TOMORROW}:SUNRISE`);
  });

  it('names the close control for the card, not just "Dismiss"', () => {
    // Three buttons on this tab mount a ✕ with an `aria-label`: this card, the colour-scale notice
    // and the LITE viewline chip — and the cold-open case the card exists for is exactly when an
    // unread notice is also on screen. Three rows reading "Dismiss" name nothing.
    renderCard();

    expect(screen.getByRole('button', { name: 'Dismiss this card' }))
      .toBe(screen.getByTestId('wf-land-close'));
  });

  it('keeps the chevrons out of the accessible names', () => {
    renderCard({ verdicts: POOR_VERDICTS });

    expect(screen.getByRole('button', { name: /Thursday sunset · The Peak · Worth it/ }).textContent)
      .toContain('\u203A');
    expect(screen.getByRole('button', { name: 'Thursday sunset · The Peak · Worth it' })).toBeTruthy();
  });

  it('does not print "Your next window" twice on a one-row card', () => {
    // ⚠️ `landingHeader` already returns that phrase for a single row, so repeating it as the
    // kicker stacked the identical sentence under itself — and, before `aria-labelledby`, gave the
    // landmark a third copy of it.
    renderCard({ events: [EVENTS[0]], verdicts: VERDICTS });

    expect(screen.getByTestId('wf-land-head')).toHaveTextContent('Your next window');
    expect(screen.getByTestId('wf-land-sub')).toHaveTextContent('My area');
    expect(screen.getByTestId('wf-land-sub')).not.toHaveTextContent('Your next');
  });

  it('renders nothing at all when no window is ahead', () => {
    const { container } = renderCard({ events: [], verdicts: new Map() });

    expect(container).toBeEmptyDOMElement();
  });

  it('omits the verdict cell entirely for a served window nothing is rated in', () => {
    renderCard({ verdicts: new Map([[`solar:${TODAY}:SUNSET`, verdict('WORTH_IT')]]) });

    const rows = screen.getAllByTestId('wf-land-row');
    expect(within(rows[0]).getByTestId('wf-land-verdict')).toBeTruthy();
    expect(within(rows[1]).queryByTestId('wf-land-verdict')).toBeNull();
  });
});

describe('MapLandingCard — the picks', () => {
  it('a pick ON a row is a medallion, and never ALSO the quiet line', () => {
    renderCard();

    const rows = screen.getAllByTestId('wf-land-row');
    expect(within(rows[0]).getByTestId('wf-land-medallion')).toHaveAttribute('data-pick', 'also');
    expect(within(rows[1]).queryByTestId('wf-land-medallion')).toBeNull();
    // Exactly one quiet line — Thursday's Best bet — and it is not the row's own Also good.
    const lines = screen.getAllByTestId('wf-land-pick-line');
    expect(lines).toHaveLength(1);
    expect(lines[0]).toHaveAttribute('data-pick', 'best');
    expect(lines[0]).toHaveTextContent('Best bet');
    expect(lines[0]).toHaveTextContent('Thursday sunset');
  });

  it('a pick EARLIER than the first row renders nowhere', () => {
    const events = [
      // An elapsed filler carrying the week's Best bet — the state the suppression exists for.
      solar(TODAY, 'SUNRISE', { served: false, time: '', pickKind: 'best' }),
      ...EVENTS,
    ];
    renderCard({ events });

    expect(screen.queryAllByTestId('wf-land-pick-line').map((n) => n.getAttribute('data-pick')))
      .toEqual(['best']);
    // ...and the one that DID render is Thursday's, not the elapsed one.
    expect(screen.getByTestId('wf-land-pick-line')).toHaveAttribute('data-ev-id', `solar:${THURSDAY}:SUNSET`);
  });
});

describe('MapLandingCard — when neither row is worth the drive', () => {
  const POOR = POOR_VERDICTS;

  it('stops comparing and names a later Worth it window, with its region and its word', () => {
    renderCard({ verdicts: POOR });

    const none = screen.getByTestId('wf-land-none');
    expect(none).toHaveTextContent('Neither is worth the drive.');
    expect(none).toHaveTextContent('Next up:');
    const next = screen.getByTestId('wf-land-next');
    expect(next).toHaveTextContent('Thursday sunset · The Peak · Worth it');
    expect(next).toHaveAttribute('data-ev-id', `solar:${THURSDAY}:SUNSET`);
  });

  it('withholds the quiet pick lines while that sentence stands', () => {
    renderCard({ verdicts: POOR });

    expect(screen.queryAllByTestId('wf-land-pick-line')).toHaveLength(0);
  });

  it('ends after "drive" when nothing later is Worth it — never offering a Maybe', () => {
    const noneGood = new Map([
      [`solar:${TODAY}:SUNSET`, verdict('STAND_DOWN')],
      [`solar:${TOMORROW}:SUNRISE`, verdict('STAND_DOWN')],
      [`solar:${THURSDAY}:SUNSET`, verdict('MAYBE')],
    ]);
    renderCard({ verdicts: noneGood });

    expect(screen.getByTestId('wf-land-none')).toHaveTextContent('Neither is worth the drive.');
    expect(screen.getByTestId('wf-land-none')).not.toHaveTextContent('Next up');
    expect(screen.queryByTestId('wf-land-next')).toBeNull();
  });

  it('keeps a row medallion — "best bet" has never meant "worth it"', () => {
    renderCard({ verdicts: POOR });

    expect(within(screen.getAllByTestId('wf-land-row')[0]).getByTestId('wf-land-medallion'))
      .toHaveAttribute('data-pick', 'also');
  });
});

describe('MapLandingCard — its handlers', () => {
  it('the close control dismisses', () => {
    const { onDismiss, onSelect } = renderCard();

    fireEvent.click(screen.getByTestId('wf-land-close'));

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('a row hands its EV row back — the caller closes the card', () => {
    const { onSelect } = renderCard();

    fireEvent.click(screen.getAllByTestId('wf-land-row')[1]);

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: `solar:${TOMORROW}:SUNRISE` }));
  });

  it('the quiet pick line selects its own window', () => {
    const { onSelect } = renderCard();

    fireEvent.click(screen.getByTestId('wf-land-pick-line'));

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: `solar:${THURSDAY}:SUNSET` }));
  });

  it('the "next up" line selects the window it names', () => {
    const { onSelect } = renderCard({
      verdicts: new Map([
        [`solar:${TODAY}:SUNSET`, verdict('STAND_DOWN')],
        [`solar:${TOMORROW}:SUNRISE`, verdict('STAND_DOWN')],
        [`solar:${THURSDAY}:SUNSET`, verdict('WORTH_IT')],
      ]),
    });

    fireEvent.click(screen.getByTestId('wf-land-next'));

    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: `solar:${THURSDAY}:SUNSET` }));
  });

  it('is a named region, NOT a dialog — the two-deep dialog invariant is not this card\'s to spend', () => {
    renderCard();

    const card = screen.getByTestId('wf-land');
    expect(card.tagName).toBe('SECTION');
    expect(card).not.toHaveAttribute('role', 'dialog');
    expect(card).not.toHaveAttribute('aria-modal');
    // ⚠️ `aria-labelledby`, not `aria-label`: a named `section` is a landmark, so an `aria-label`
    // repeating the visible title makes a screen reader announce it twice on entry.
    expect(card).not.toHaveAttribute('aria-label');
    expect(screen.getByRole('region', { name: 'Tonight, or tomorrow?' })).toBe(card);
  });
});
