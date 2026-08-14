import React from 'react';
import PropTypes from 'prop-types';
import WindowSpotStrip from './WindowSpotStrip.jsx';
import WindowAttributeRow from './WindowAttributeRow.jsx';
import { badgeChannel, CONFIDENCE_VERDICTS, windowCardDomId } from '../utils/windowFirstCards.js';
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
  // #C4787F at the same 0.12/0.45 weights as its four siblings. Literals rather than the token,
  // matching how every other row here spells its hue.
  eclipse: { fill: 'rgba(196,120,127,0.12)', border: 'rgba(196,120,127,0.45)', text: 'var(--color-badge-eclipse)' },
  tide: { fill: 'rgba(111,168,176,0.12)', border: 'rgba(111,168,176,0.45)', text: 'var(--color-badge-tide)' },
  nlc: { fill: 'rgba(155,143,212,0.12)', border: 'rgba(155,143,212,0.45)', text: 'var(--color-badge-nlc)' },
  aurora: { fill: 'rgba(138,174,114,0.12)', border: 'rgba(138,174,114,0.45)', text: 'var(--color-badge-go)' },
  snow: { fill: 'rgba(183,203,216,0.12)', border: 'rgba(183,203,216,0.45)', text: 'var(--color-badge-snow)' },
  plain: { fill: 'rgba(255,255,255,0.04)', border: 'var(--color-plex-border-light)', text: 'var(--color-plex-text-secondary)' },
};

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
 * <h2>The footer arrives with the strip, and at P11 it gains its third element</h2>
 *
 * <p>P5 drew no footer at all rather than an empty one. It exists because the strip gives it two
 * true things to say — the order the spots are in, and how many were drawn — and P11 adds the
 * design's third, "See all", now that there is a sheet for it to open. A window with no spots at
 * all renders neither strip nor footer, rather than a bar counting nothing — and a window the LENS
 * emptied keeps its own line and gains the trigger on the end of it, because "12 spots are further
 * out" with no route to those twelve is the very defect CLAUDE.md records against Close-to-home's
 * old four-card cap. The trigger is absent, on every variant, whenever the sheet could show nothing
 * the strip does not — see {@code sheetOffersMore}, which is the arrows' own rule applied to a
 * different control.
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
 * @param {Function} [props.onLoosenLens] moves the page-wide lens to the option an emptied window
 *        names. The card receives the whole action descriptor from {@code card.lensEmpty} and hands
 *        it straight back, so it learns nothing about which lenses exist — the shell owns both.
 *        The former {@code reachLabel} prop is gone: the sentence it fed is now built where both
 *        thresholds are known, because with two gates a card cannot say which one emptied it.
 * @param {boolean}  [props.open] whether the collapsible region is showing. The shell owns the
 *        state, not the card: the default is a judgement about the <em>list</em> ("the first card is
 *        open"), and a card cannot see its own position.
 * @param {Function} [props.onToggle] flips {@code open} for this card.
 * @param {Function} [props.onOpenPick] opens the pick dialog for this window
 * @param {Function} [props.onSeeAllSpots] opens the drill-down over this window's whole spot list.
 *        The shell owns the sheet, exactly as it owns the pick dialog: one sheet on the page is
 *        then structural rather than something a page-level token has to buy back, which is the
 *        shape {@code useSpotPeek} needed only because peek state is deliberately per-strip.
 * @param {boolean}  [props.peeksSuppressed] passed straight through to the strip — the drill-down
 *        is a modal the pane stays mounted behind, and a hover panel portalled above it would draw
 *        over the dialog. A boolean, so nothing here learns anything it could act on.
 * @param {Function} [props.onOpenSpot] opens the map centred on a spot in this window.
 *
 *        <p>Wired at P6 although §5 lists click-to-map under P10′, because the alternative is
 *        worse: the spot card is drawn as a button, lifts on hover and ends in "◍ Open on map →",
 *        and shipping that inert for a phase is precisely the demo control §6 bans. The handler is
 *        the one this arm already passes to the pick dialog's "show location", so nothing new is
 *        invented. P10′ keeps the part that is actually new — {@code WindowSpotPeek}.
 * @param {?Map}     [props.scoreIndex] briefing-score index, drilled through to the strip's peek.
 *
 *        <p>A lookup structure rather than window content, which is why it is passed rather than
 *        folded into each spot descriptor by {@code buildWindowSpots}: that record's join is
 *        documented as briefing + reach, the scores arrive from a third request that resolves after
 *        the first paint, and folding them in would rebuild every card's spot array when it lands.
 *        It carries no role and gates nothing, so P7's pin on this component's props still holds.
 */
export default function WindowFirstWindowCard({
  card, todayStr, open = true, onToggle, onOpenPick, onOpenSpot, onSeeAllSpots, onLoosenLens,
  peeksSuppressed, scoreIndex,
}) {
  // The colon in `card.key` is a legal HTML5 id character and `aria-controls` is an IDREF, not a
  // selector, so it would work — but it silently breaks `querySelector('#…')` and any CSS id
  // selector for whoever reaches for one next. Cheaper to not lay the trap.
  const bodyId = `window-card-body-${card.key.replace(/:/g, '-')}`;
  const windowLabel = [card.kicker, card.when].filter(Boolean).join(' ');
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
      // The anchor the promoted strip's "Go to …" scrolls to and focuses within. It is an id rather
      // than a ref because the strip is not this component's parent — it is a sibling several items
      // up the pane — and threading a ref per card through the shell to reach one of them would be
      // a lot of plumbing for a lookup the DOM already indexes.
      id={windowCardDomId(card.key)}
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
      {/* `data-open` rather than a second class or a new prop: the phone rule changes this row's
          padding AND its gap, and the padding is the one value here that varies at render, so
          without a hook the element's geometry would end up split across two files — the trap the
          migration rule exists to avoid. `open` is already this component's prop; the attribute
          only publishes it to the stylesheet. */}
      <div
        data-testid="window-card-head"
        data-open={open ? 'true' : 'false'}
        className="wf-wh flex items-center flex-wrap"
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
        {/* One group, because in the spec these two ARE one clause: the mock prints
            `best 4★ · 6 within reach` from a single `.best` span (`Plan Window First v2.html:471`)
            and gives it `flex-basis: 100%` on phone (`:267`) so the meta takes one full row. This
            arm split it in two only so "within reach" could be null independently of the rating —
            a nullability difference, not a second clause — so they must still share that row.
            Wrapping is what makes one `flex-basis` govern both; two orders would spend two rows
            saying what the design says in one. The group renders only when a child does, or an
            empty flex item would spend a gap on nothing. */}
        {(card.bestRating != null || card.withinReachCount != null) && (
        <span data-testid="window-card-meta" className="wf-wh-meta">
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
        </span>
        )}
        {/* The design's spacer rule, and on phone it is the one element the header LOSES: with meta
            and badges each taking their own full row there is no gap left for a rule to fill.
            Classed rather than hidden by a Tailwind variant so the media query owns it. */}
        <span className="wf-wh-rule flex-1 min-w-[12px] h-px bg-plex-border" aria-hidden="true" />

        <span
          data-testid="window-card-badges"
          className="wf-wh-badges flex flex-wrap"
          style={{ gap: '6px' }}
        >
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

        {/* The window card is the ONE surface guaranteed to be on screen whenever a topic is, and
            that is why it carries this rather than leaving it to the two that already do.
            `WindowFirstPromotedStrip` shows it only for the window it promotes, and the Hot Topics
            pill sits behind a door that is shut on a fresh session — so on the v2 pane there were
            arrangements where a rose "Deep partial eclipse" chip appeared with the solar-filter
            instruction nowhere on screen or in the accessibility tree. An adversarial review found
            it; `BriefingWindow.Badge`'s own Javadoc had already named this card as the reason the
            field rides the badge at all.

            Rendered from whichever badge carries one rather than per badge: a warning is about the
            hazard, not about the chip, and two identical lines would be worse than one. */}
        {card.badges.map((badge) => badge.safetyNote).find(Boolean) && (
          <div
            data-testid="window-card-safety"
            className="font-mono"
            style={{
              fontSize: '10.5px',
              lineHeight: 1.45,
              marginTop: '6px',
              display: 'flex',
              alignItems: 'baseline',
              gap: '6px',
              color: 'var(--color-plex-text)',
            }}
          >
            <span aria-hidden="true">⚠</span>
            <span>{card.badges.map((badge) => badge.safetyNote).find(Boolean)}</span>
          </div>
        )}

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
                // The kicker as well, for the reason the sheet's own header carries it: on a lead
                // card `when` is the bare event word, so "See all spots in Sunset" and "Scroll
                // Sunset spots left" name no day on the one card most likely to be read.
                windowLabel={windowLabel}
                total={card.reachTotal}
                lead={card.lead}
                onOpenSpot={(spot) => onOpenSpot?.(card, spot)}
                onSeeAll={onSeeAllSpots ? () => onSeeAllSpots(card) : undefined}
                peeksSuppressed={peeksSuppressed}
                date={card.date}
                targetType={card.targetType}
                scoreIndex={scoreIndex}
              />
            )}

            {/* Only when the LENS emptied it. A window with no spots at all renders neither strip
                nor message — there is nothing the control could bring back, so the line would be a
                statement about the lens on a card the lens never touched. `buildLensEmptyState`
                returns null in exactly that case, so the condition is the descriptor's existence
                rather than a second reading of the same facts here. */}
            {card.spots.length === 0 && card.lensEmpty && (
              <div data-testid="window-card-lens-empty" className="wf-lens-empty">
                <b data-testid="window-card-lens-empty-head">{card.lensEmpty.headline}</b>
                <span data-testid="window-card-lens-empty-body">{card.lensEmpty.body}</span>
                {/* The way out, and it is a real one: each action was tested by re-running both
                    gates with that control loosened, so a button that appears always fills the card.
                    One tap rather than "reason about which of two controls to change". */}
                {card.lensEmpty.actions.map((action) => (
                  <button
                    key={`${action.kind}:${action.id}`}
                    type="button"
                    data-testid="window-card-lens-loosen"
                    data-loosen={action.kind}
                    className="wf-lens-empty-act"
                    // The card's KEY rides along, because pressing this button destroys it: the
                    // action is guaranteed to refill the window, so the whole empty card — this
                    // button included — is gone on the next commit and focus falls to `<body>`.
                    // Only the shell can put it somewhere, and only it knows what replaced this.
                    onClick={() => onLoosenLens?.(action, card.key)}
                  >
                    {action.label}
                    <span aria-hidden="true"> →</span>
                  </button>
                ))}
                {/* The trigger belongs here MORE than on a populated card, not less. A count of what
                    is beyond the lens is otherwise a number with no route to the thing it counts,
                    which is the exact defect CLAUDE.md records against Close-to-home's old
                    per-window cap ("20 within reach above four cards, no route to the other
                    sixteen"). The bar's own controls can also reveal them, but they change the whole
                    page to answer a question about one window — where the sheet widens for browsing
                    and forgets it on close, which is what charge c6 asks a drill-down to be. */}
                {onSeeAllSpots && (
                  <button
                    type="button"
                    data-testid="window-card-lens-all"
                    className="wf-lens-empty-act"
                    aria-label={`See all spots in ${windowLabel}`}
                    onClick={() => onSeeAllSpots(card)}
                  >
                    See all
                    <span aria-hidden="true"> →</span>
                  </button>
                )}
              </div>
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
      safetyNote: PropTypes.string,
    })).isRequired,
    pick: PropTypes.shape({
      kind: PropTypes.oneOf(['best', 'also']).isRequired,
      regionName: PropTypes.string.isRequired,
      headline: PropTypes.string.isRequired,
    }),
    spots: PropTypes.arrayOf(PropTypes.object).isRequired,
    reachTotal: PropTypes.number,
    reachedTotal: PropTypes.number,
    withinReachCount: PropTypes.number,
    lensEmpty: PropTypes.shape({
      headline: PropTypes.string.isRequired,
      body: PropTypes.string.isRequired,
      actions: PropTypes.arrayOf(PropTypes.shape({
        kind: PropTypes.oneOf(['reach', 'rating']).isRequired,
        id: PropTypes.string.isRequired,
        label: PropTypes.string.isRequired,
      })).isRequired,
    }),
    rows: PropTypes.arrayOf(PropTypes.shape({
      key: PropTypes.string.isRequired,
      channel: PropTypes.oneOf(['tide', 'snow']).isRequired,
    })).isRequired,
  }).isRequired,
  todayStr: PropTypes.string.isRequired,
  open: PropTypes.bool,
  onToggle: PropTypes.func,
  onOpenPick: PropTypes.func,
  onOpenSpot: PropTypes.func,
  onSeeAllSpots: PropTypes.func,
  onLoosenLens: PropTypes.func,
  peeksSuppressed: PropTypes.bool,
  scoreIndex: PropTypes.instanceOf(Map),
};
