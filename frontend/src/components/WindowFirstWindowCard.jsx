import React from 'react';
import PropTypes from 'prop-types';
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
 * <h2>No expander, and no footer</h2>
 *
 * <p>Collapse/expand is P9's. It would also have nothing to collapse: the attribute rows are P7,
 * the spot strip P6, and §2.3 deleted the narrative block outright — so at P5 collapsed and
 * expanded differ by a few pixels of padding. A control whose only effect is that is a demo
 * control, which §6 bans. The header's markup puts the expander's slot last, after the badges, so
 * P9 inserts one element and reflows nothing.
 *
 * <p>The footer is absent for the same reason rather than being drawn empty: everything the design
 * puts in it — the strip's sort statement, the film controls, "See all N →" — belongs to P6 and
 * P11, and a bar claiming a sort over a set that is not on screen is the exact failure §6 names.
 *
 * @param {object}   props
 * @param {object}   props.card       a descriptor from {@code buildWindowCards}
 * @param {string}   props.todayStr   today in Europe/London, for the confidence horizon
 * @param {Function} [props.onOpenPick] opens the pick dialog for this window
 */
export default function WindowFirstWindowCard({ card, todayStr, onOpenPick }) {
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
        style={{ gap: '10px', padding: '12px 14px 10px' }}
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
          style={{ fontSize: '15.5px', letterSpacing: '-0.01em' }}
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
        {card.bestRating != null && (
          <span
            data-testid="window-card-best"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '11px' }}
          >
            {`best ${card.bestRating}★`}
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
  }).isRequired,
  todayStr: PropTypes.string.isRequired,
  onOpenPick: PropTypes.func,
};
