import React from 'react';
import PropTypes from 'prop-types';
import WindowSpotStrip from './WindowSpotStrip.jsx';
import WindowAttributeRow from './WindowAttributeRow.jsx';
import { badgeChannel, CONFIDENCE_VERDICTS } from '../utils/windowFirstCards.js';
import { confidenceTreatment, daysOut, resolveConfidence, scaleRgbaAlpha } from '../utils/confidenceUtils.js';

/**
 * The verdict badge's own fill, border and text, as `rgba()` literals the confidence channel can
 * consume. Text stays hex on purpose — `scaleRgbaAlpha` returns a non-`rgba()` string untouched,
 * so the hex IS the mechanism that keeps the word at full strength while its fill decays.
 */
const VERDICT_TREATMENT = {
  WORTH_IT: { fill: 'rgba(138,174,114,0.14)', border: 'rgba(138,174,114,0.5)', text: 'var(--color-badge-go)', weight: 600 },
  MAYBE: { fill: 'rgba(224,165,66,0.14)', border: 'rgba(224,165,66,0.5)', text: 'var(--color-badge-maybe)', weight: 600 },
  STAND_DOWN: { fill: 'rgba(200,69,47,0.12)', border: 'rgba(200,69,47,0.4)', text: 'var(--color-badge-poor)', weight: 400 },
  // Text-secondary, NOT --color-verdict-awaiting. That token is the verdict/fill family and is
  // numerically the muted ink: on this badge's own fill it measures 3.47:1 at 10px, below AA, and
  // it never decays so no tier softens it. This file already rejected --color-pick-also as badge
  // type at 4.47:1 for the same reason. Secondary measures 6.46:1 and is what `VerdictPill` and the
  // neutral topic badge below already use for exactly this state.
  AWAITING: { fill: 'rgba(255,255,255,0.04)', border: 'rgba(255,255,255,0.10)', text: 'var(--color-plex-text-secondary)', weight: 400 },
};

/** Badge channels for the hot topics that land on a window. */
const CHANNEL = {
  tide: { fill: 'rgba(111,168,176,0.12)', border: 'rgba(111,168,176,0.45)', text: 'var(--color-badge-tide)' },
  nlc: { fill: 'rgba(155,143,212,0.12)', border: 'rgba(155,143,212,0.45)', text: 'var(--color-badge-nlc)' },
  aurora: { fill: 'rgba(138,174,114,0.12)', border: 'rgba(138,174,114,0.45)', text: 'var(--color-badge-go)' },
  snow: { fill: 'rgba(183,203,216,0.12)', border: 'rgba(183,203,216,0.45)', text: 'var(--color-badge-snow)' },
  plain: { fill: 'rgba(255,255,255,0.04)', border: 'var(--color-plex-border-light)', text: 'var(--color-plex-text-secondary)' },
};

/**
 * What a window whose every spot the lens gated says instead of a strip.
 *
 * <p>The design's line is "Nothing matches the current lens in this window." Two changes. The word
 * <em>lens</em> is ours, not the product's — §6 bans inventing vocabulary — so the sentence names
 * the threshold the reader can actually see on the control above. And it states how many are beyond
 * it, because the useful fact is not that the window is empty but that widening would fill it; an
 * empty card that says only "nothing" reads as a bad forecast rather than a tight filter.
 *
 * @param {number} total      how many spots the lens chose from
 * @param {string} reachLabel the active tier's own label
 * @returns {string} the sentence
 */
function emptyLensLine(total, reachLabel) {
  const beyond = total === 1 ? '1 spot is further out.' : `${total} spots are further out.`;
  return `Nothing within ${reachLabel} in this window. ${beyond}`;
}

/**
 * One shooting window, as a card.
 *
 * <h2>The verdict badge is the confidence channel's only render site</h2>
 *
 * <p>Plan §2.7. The tier scales the badge's fill and border and leaves the word itself unscaled, so
 * a far-horizon "Worth it" reads more provisional than tonight's without ever being harder to read
 * — the same treatment `HeatmapGrid` already applies to its cells. There is <b>no marker glyph</b>:
 * the badge already carries {@code ◎}, and a second hollow circle is noise. There is no second
 * render site either — the rail derives its own confidence and deliberately renders nothing from it.
 *
 * <p>Only a recommendation is qualified. A Poor or an Awaiting badge is not one, so it does not
 * decay — see {@code windowFirstCards.js}, which nulls the field for those verdicts before it ever
 * reaches here.
 *
 * <p>{@code resolveConfidence} is fail-soft and never returns null, so a window with no backend
 * confidence lands on the horizon inference capped at medium. That cap is why the call is
 * {@code confidenceTreatment(resolveConfidence(...))} and never
 * {@code confidenceTreatment(card.confidence)} — the latter returns the <em>medium</em> treatment
 * for an absent tier, which silently decays a high-confidence badge to 72%.
 *
 * <h2>The expander is the last thing in the header, and everything else on the card is its region</h2>
 *
 * <p>P5 reserved this slot after the badges precisely so P9 would insert one element and reflow
 * nothing, and that held. What it controls is <b>every sibling the header has</b> — the attribute
 * rows, the spot strip, its footer, and the fully-gated window's line. Plan §5a settled the split on
 * measured numbers rather than taste: the rows alone cost 207px above the header, and a collapsed
 * card that kept them would give back only 150 of that. So the region is drawn wide, not narrow.
 *
 * <p><b>The region element is always rendered; only its children are conditional.</b>
 * {@code aria-controls} must name an element that exists, and unmounting the whole container on
 * collapse would leave every collapsed card pointing at nothing — a broken relationship on five of
 * six cards in the default state, which is the state almost every reader will be in. Empty and
 * unpadded it contributes no height, so this costs a DOM node and buys a valid relationship.
 *
 * <p>The collapsed header is the mock's: {@code padding} 12/14/10 → 10/14 and the title 15.5px →
 * 13.5px. Nothing leaves it — the verdict badge, the pick, the topic badges, the star and the reach
 * count are the whole point of a collapsed card, which is a row you can scan rather than a stub.
 *
 * <h2>The footer arrives with the strip, and still carries no "See all"</h2>
 *
 * <p>P5 drew no footer at all rather than an empty one. It exists now because the strip gives it
 * two true things to say — the order the spots are in, and how many were drawn. The design's third
 * element, "See all N →", opens P11's drill-down and is still absent: it is the same demo control
 * the expander would have been. A window with no spots at all renders neither strip nor footer,
 * rather than a bar counting nothing.
 *
 * <h2>The attribute rows sit between the header and the strip, and the header may be short a badge</h2>
 *
 * <p>Where the design puts them. A topic promoted to a row is filtered out of {@code card.badges}
 * upstream, so this component renders whatever it is given and nothing here decides badge-or-row —
 * {@code windowFirstRows.js} owns that rule and the reason for it. A card with no tide rollup and no
 * factful topic renders no {@code .wf-rows} container at all, rather than an empty one.
 *
 * <p><b>Everything the rows carry is inside the card's own bounds and above the strip</b>, which is
 * what lets P9 wrap the header's siblings in one collapsible region without moving anything.
 *
 * @param {object}   props
 * @param {object}   props.card       a descriptor from {@code buildWindowCards}
 * @param {string}   props.todayStr   today in Europe/London, for the confidence horizon
 * @param {string}   [props.reachLabel] the active reach tier's own label, used only in the sentence
 *        a fully gated window shows in place of its strip. The label rather than the threshold, so
 *        the card names exactly what is written on the chip the reader would press.
 * @param {boolean}  [props.open] whether the collapsible region is showing. The shell owns the
 *        state, not the card: the default is a judgement about the <em>list</em> ("the first card is
 *        open"), and a card cannot see its own position.
 * @param {Function} [props.onToggle] flips {@code open} for this card.
 * @param {Function} [props.onOpenPick] opens the pick dialog for this window
 * @param {Function} [props.onOpenSpot] opens the map centred on a spot in this window.
 *
 *        <p>Wired at P6 although §5 lists click-to-map under P10′, because the alternative is
 *        worse: the spot card is drawn as a button, lifts on hover and ends in "◍ Open on map →",
 *        and shipping that inert for a phase is precisely the demo control §6 bans. The handler is
 *        the one this arm already passes to the pick dialog's "show location", so nothing new is
 *        invented. P10′ keeps the part that is actually new — {@code WindowSpotPeek}.
 */
export default function WindowFirstWindowCard({
  card, todayStr, reachLabel, open = true, onToggle, onOpenPick, onOpenSpot,
}) {
  // The colon in `card.key` is a legal HTML5 id character and `aria-controls` is an IDREF, not a
  // selector, so it would work — but it silently breaks `querySelector('#…')` and any CSS id
  // selector for whoever reaches for one next. Cheaper to not lay the trap.
  const bodyId = `window-card-body-${card.key.replace(/:/g, '-')}`;
  const treatment = VERDICT_TREATMENT[card.verdict] || VERDICT_TREATMENT.AWAITING;
  const tier = resolveConfidence({ confidence: card.confidence }, daysOut(card.date, todayStr));
  const { fillScale } = confidenceTreatment(tier);
  // Gated on the VERDICT, not on whether confidence happens to be null. The two are not the same
  // set, and using the null as a proxy was a real defect: a WORTH_IT window whose backend confidence
  // is absent — which the backend produces whenever a region's stats are empty but its triage still
  // says GO — has a null field, and rendered at FULL strength. That is the exact failure the channel
  // exists to prevent, it made `todayStr` provably unable to affect a pixel, and it put the two flag
  // arms in disagreement about one payload: `HeatmapGrid` applies the scale unconditionally once
  // past its Poor early-return. The verdict gate leaves Poor and Awaiting undecayed, which is the
  // thing the null was standing in for.
  const scale = CONFIDENCE_VERDICTS.has(card.verdict) ? fillScale : 1;

  return (
    <div
      data-testid="window-card"
      data-verdict={card.verdict}
      data-lead={card.lead ? 'true' : undefined}
      data-open={open ? 'true' : 'false'}
      className="window-card"
      style={{
        border: `1px solid ${card.lead ? 'rgba(201,162,75,0.42)' : 'var(--color-plex-border)'}`,
        borderRadius: '11px',
        // The lead wash is quoted exactly: the run-bar ramp's contrast floor was derived against
        // this composite, so changing the tint silently invalidates a derivation already in the tree.
        background: card.lead
          ? 'linear-gradient(180deg, rgba(201,162,75,0.06), transparent 55%), var(--color-plex-panel)'
          : 'var(--color-plex-panel)',
        overflow: 'hidden',
      }}
    >
      <div
        data-testid="window-card-head"
        className="flex items-center flex-wrap"
        style={{ gap: '10px', padding: open ? '12px 14px 10px' : '10px 14px' }}
      >
        {card.kicker && (
          <span
            data-testid="window-card-kicker"
            className="font-mono uppercase"
            style={{
              fontSize: '10px',
              fontWeight: 600,
              letterSpacing: '0.11em',
              color: 'var(--color-close-to-home)',
            }}
          >
            {card.kicker}
          </span>
        )}
        <span
          data-testid="window-card-when"
          className="font-bold text-plex-text"
          style={{ fontSize: open ? '15.5px' : '13.5px', letterSpacing: '-0.01em' }}
        >
          {card.when}
        </span>
        {card.time && (
          <span
            data-testid="window-card-time"
            className="font-mono font-semibold text-plex-text"
            style={{ fontSize: '13.5px', fontVariantNumeric: 'tabular-nums' }}
          >
            {card.time}
          </span>
        )}
        {/* Omitted entirely rather than shown as a placeholder: a null best rating means nothing in
            the window is rated, which is a different statement from a low one. */}
        {/* Secondary, not muted — and the star moved with the clause beside it. Measured on the
            running app at this 11px: muted is 3.54:1 on a plain card and 3.48:1 on the lead card's
            gold wash, both under AA; secondary is 6.87:1 and 6.50:1. The star has read muted since
            P5 and the failure is inherited, but the P8 clause sits on the same line, so upgrading
            one and not the other would leave two greys in one row — the reason this change already
            gives for taking the whole rail footer at once. Sixth time on this redesign; the lead
            card is the worse backdrop, which is why both were measured rather than one. */}
        {card.bestRating != null && (
          <span
            data-testid="window-card-best"
            className="font-mono text-plex-text-secondary"
            style={{ fontSize: '11px' }}
          >
            {`best ${card.bestRating}★`}
          </span>
        )}
        {/* The design's second meta clause, earned at P8 and not before. Null whenever the word
            "reach" would over-claim — under "Any" nothing was gated, and one unknown drive time
            makes the drawn set part-measured — in which case the header says nothing rather than
            restating the strip footer's own count one element lower. See `windowFirstCards.js`. */}
        {card.withinReachCount != null && (
          <span
            data-testid="window-card-within-reach"
            className="font-mono text-plex-text-secondary"
            style={{ fontSize: '11px' }}
          >
            {`${card.withinReachCount} within reach`}
          </span>
        )}
        <span className="flex-1 min-w-[12px] h-px bg-plex-border" aria-hidden="true" />

        <span data-testid="window-card-badges" className="flex flex-wrap" style={{ gap: '6px' }}>
          <span
            data-testid="window-card-verdict"
            data-confidence={card.confidence || undefined}
            className="font-mono whitespace-nowrap"
            style={{
              fontSize: '10px',
              padding: '3px 8px',
              borderRadius: '999px',
              border: `1px solid ${scaleRgbaAlpha(treatment.border, scale)}`,
              background: scaleRgbaAlpha(treatment.fill, scale),
              color: treatment.text,
              fontWeight: treatment.weight,
            }}
          >
            {/* Neither Poor nor Awaiting takes the mark. The ◎ reads as a recommendation, and
                neither of these recommends anything — Awaiting has not looked yet. */}
            {card.verdict !== 'STAND_DOWN' && card.verdict !== 'AWAITING' && (
              <span aria-hidden="true">◎ </span>
            )}
            {card.verdictLabel}
          </span>

          {card.pick && (
            <button
              type="button"
              data-testid="window-card-pick"
              data-pick={card.pick.kind}
              onClick={() => onOpenPick?.(card)}
              className="window-card-pick font-mono whitespace-nowrap"
              style={{
                fontSize: '10px',
                fontWeight: 600,
                padding: '3px 8px',
                borderRadius: '999px',
                border: `1px solid ${card.pick.kind === 'best' ? 'rgba(138,174,114,0.5)' : 'rgba(124,141,214,0.5)'}`,
                background: card.pick.kind === 'best' ? 'rgba(138,174,114,0.14)' : 'rgba(124,141,214,0.14)',
                color: card.pick.kind === 'best' ? 'var(--color-badge-go)' : 'var(--color-badge-also)',
              }}
            >
              <span aria-hidden="true">◎ </span>
              {card.pick.kind === 'best' ? 'Best bet' : 'Also good'}
            </button>
          )}

          {card.badges.map((badge) => {
            const channel = CHANNEL[badgeChannel(badge.type)];
            return (
              <span
                key={`${badge.type}:${badge.label}`}
                data-testid="window-card-badge"
                data-channel={badgeChannel(badge.type)}
                className="font-mono whitespace-nowrap"
                style={{
                  fontSize: '10px',
                  padding: '3px 8px',
                  borderRadius: '999px',
                  border: `1px solid ${channel.border}`,
                  background: channel.fill,
                  color: channel.text,
                }}
              >
                {badge.label}
              </span>
            );
          })}
        </span>

        {/* The accessible name carries the window, because `aria-expanded` announces the STATE and
            nothing else distinguishes six identical "Open" buttons in a list. The visible word is
            the first word of the label, so WCAG 2.5.3's label-in-name holds; the caret is decorative
            and is excluded rather than spoken as punctuation. */}
        <button
          type="button"
          data-testid="window-card-expander"
          className="wf-exp"
          aria-expanded={open}
          aria-controls={bodyId}
          aria-label={`${open ? 'Collapse' : 'Open'} ${card.when}`}
          onClick={onToggle}
        >
          {open ? 'Collapse' : 'Open'}
          <span aria-hidden="true">{open ? ' ▴' : ' ▾'}</span>
        </button>
      </div>

      {/* Always rendered, so `aria-controls` above always resolves — see the class comment. */}
      <div id={bodyId} data-testid="window-card-body">
        {open && (
          <>
            {card.rows.length > 0 && (
              <div data-testid="window-card-rows" className="wf-rows">
                {card.rows.map((row) => <WindowAttributeRow key={row.key} row={row} />)}
              </div>
            )}

            {card.spots.length > 0 && (
              <WindowSpotStrip
                spots={card.spots}
                windowLabel={card.when}
                total={card.reachTotal}
                lead={card.lead}
                onOpenSpot={(spot) => onOpenSpot?.(card, spot)}
              />
            )}

            {/* Only when the LENS emptied it. A window with no spots at all still renders neither
                strip nor message — there is nothing the control could bring back, so the line would
                be a statement about the lens on a card the lens never touched. */}
            {card.spots.length === 0 && card.reachTotal > 0 && (
              <p data-testid="window-card-lens-empty" className="wf-strip-empty">
                {emptyLensLine(card.reachTotal, reachLabel)}
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}

WindowFirstWindowCard.propTypes = {
  card: PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    targetType: PropTypes.string,
    lead: PropTypes.bool,
    kicker: PropTypes.string,
    when: PropTypes.string.isRequired,
    time: PropTypes.string,
    verdict: PropTypes.oneOf(['WORTH_IT', 'MAYBE', 'STAND_DOWN', 'AWAITING']).isRequired,
    verdictLabel: PropTypes.string.isRequired,
    bestRating: PropTypes.number,
    confidence: PropTypes.oneOf(['high', 'medium', 'low']),
    badges: PropTypes.arrayOf(PropTypes.shape({
      type: PropTypes.string,
      label: PropTypes.string,
      detail: PropTypes.string,
    })).isRequired,
    pick: PropTypes.shape({
      kind: PropTypes.oneOf(['best', 'also']).isRequired,
      regionName: PropTypes.string.isRequired,
      headline: PropTypes.string.isRequired,
    }),
    spots: PropTypes.arrayOf(PropTypes.object).isRequired,
    reachTotal: PropTypes.number,
    withinReachCount: PropTypes.number,
    rows: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      channel: PropTypes.oneOf(['tide', 'snow']).isRequired,
    })).isRequired,
  }).isRequired,
  todayStr: PropTypes.string.isRequired,
  reachLabel: PropTypes.string,
  open: PropTypes.bool,
  onToggle: PropTypes.func,
  onOpenPick: PropTypes.func,
  onOpenSpot: PropTypes.func,
};
