import React from 'react';
import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';
import { badgeChannel } from '../utils/windowFirstCards.js';
import { topicFacts } from '../utils/windowFirstRows.js';

/**
 * The popup's topic rows — every topic landing on this window, with the science behind it.
 *
 * <h2>It renders a join it does not perform</h2>
 *
 * <p>⚠️ <b>The badge → {@code hotTopics} join and the scope filter live in
 * {@code utils/windowFirstTopics.js} and nowhere else</b> (plan-matrix A8, M1). This component takes
 * the rows that util produced and draws them. That is not tidiness: the matrix card above and this
 * row eight pixels into a dialog name the same topics, and a second implementation of either rule
 * is exactly how one surface comes to show a topic the other has dropped. The two rules are also
 * both easy to get wrong in ways every naive test passes — a {@code topic.date === card.date}
 * equality loses every NIGHT topic on its morning card, and an unexempted {@code regions}
 * intersection deletes aurora from every away plan — which is the second reason they are written
 * once, tested once, and consumed here.
 *
 * <h2>What the row adds that the card cannot</h2>
 *
 * <p>The card names the topic. This row states its {@code detail} and its measured {@code facts},
 * puts {@code description} — the strategy's own science note, served on the full {@code HotTopic}
 * and deliberately absent from the slim {@code Badge} — behind the circled {@code i}, and says how
 * much of the reader's scope the topic is actually about.
 *
 * <p>⚠️ <b>The facts are not decoration and their absence was a real loss.</b> A snow strategy
 * serves its headline figure as a FACT ({@code "snow line", "~600 m"}) and never as {@code detail},
 * and the surface that used to render it — the window card's snow attribute row — is deleted in the
 * same commit as this component arrives. Rendered here through {@code topicFacts}, the same mapping
 * the attribute row used, so the emphasis of a fact cannot be answered two ways.
 *
 * <h2>The scope note is for region-scoped topics only</h2>
 *
 * <p>A8 rule 2 again, at the render layer: for a tide or an inversion, {@code topic.regions} names
 * where the conditions are, so "4 in scope" is a true and useful qualification. For aurora it is
 * Bortle-enrichment coverage and for NLC it is where the sky happens to be clear — populated lists
 * that mean something else — so a count drawn from them would read as an eligibility statement the
 * payload never made. Whole-sky topics therefore get no scope note at all, and the util has already
 * decided which is which ({@code row.wholeSky}); this component never re-asks.
 *
 * <p>The note is also withheld when the util returned {@code regionsInScope: null}, which happens on
 * two different paths and reads the same way in both: the scope is not known yet (the locations
 * payload has not landed), or the topic served no {@code regions} at all. Silence, never a zero —
 * a zero would say the topic is about nowhere the reader is planning from, which is the one thing
 * the filter would already have acted on.
 *
 * <h2>The safety note is never gated and never truncated</h2>
 *
 * <p>{@code Badge.safetyNote} carries the "do not look at the sun without a filter" class of
 * warning. It rides the badge rather than the topic, so it survives a missing join; it renders for
 * whichever badge carries one, once per row, and no confidence or scope treatment touches it.
 *
 * @param {object}   props
 * @param {Array}    props.rows the rows from {@code windowTopics} — badge, topic, wholeSky,
 *                              regionsInScope
 */
export default function WindowTopicRows({ rows }) {
  if (!rows || rows.length === 0) return null;

  return (
    <div data-testid="wf-topic-rows" className="wf-trows">
      {rows.map(({ badge, topic, wholeSky, regionsInScope, scopedRegions }) => {
        const channel = badgeChannel(badge.type);
        // ⚠️ The BADGE's line first, the topic's as the fallback — and the order is load-bearing.
        //
        // It used to be the other way round, justified by "both fields carry the same sentence, and
        // preferring the topic's keeps the row identical to the Hot Topics door". Both premises are
        // now dead. `PlanWindowProjector.badgeFor` deliberately gives a day-scoped topic's
        // non-aligned window a DIFFERENT sentence — that window's own water — and the Hot Topics
        // door was deleted in the Coming up redesign's P6.
        //
        // Left topic-first, the evening card of a spring run printed `tide aligned with sunrise at
        // 47 of 61 coastal locations`: the topic is one object shared by both of its windows, so its
        // line is the ALIGNED window's, and the join now succeeds on both. The badge is the
        // window-specific payload and the topic is the day-level fallback, so the badge wins.
        const detail = badge.detail || topic?.detail || null;
        const science = topic?.description || null;
        // From the BADGE, which is where the served facts ride — the joined topic carries none.
        const facts = (badge.facts || []).length > 0 ? topicFacts(badge) : [];
        return (
          <div
            key={`${badge.type}:${badge.label}`}
            data-testid="wf-topic-row"
            data-channel={channel}
            data-whole-sky={wholeSky ? 'true' : undefined}
            className="wf-trow"
          >
            <span data-testid="wf-topic-row-name" className="wf-trow-n">{badge.label}</span>
            {/* Only where the join found a topic to read it off. A missing note is silence: an
                `i` that opens an empty card is a control with nothing behind it. */}
            {science && (
              <InfoTip
                className="wf-trow-i"
                heading={badge.label}
                text={science}
                position="below"
              />
            )}
            {detail && (
              <span data-testid="wf-topic-row-detail" className="wf-trow-d">{detail}</span>
            )}
            {facts.length > 0 && (
              <span data-testid="wf-topic-row-facts" className="wf-trow-f">
                {/* Index-keyed, and deliberately: a topic's facts are a fixed, ordered list built
                    fresh from one payload — nothing reorders or splices them — while their text is
                    not unique by construction, so keying on content is the option that could
                    actually collide. The attribute row this is lifted from says the same. */}
                {facts.map((f, i) => (
                  <span
                    key={`${i}:${f.segments[0]?.text ?? ''}`}
                    data-testid="wf-topic-row-fact"
                    className={f.optional ? 'opt' : undefined}
                  >
                    {f.segments.map((segment, j) => (
                      <span key={`${j}:${segment.text}`} data-tone={segment.tone}>{segment.text}</span>
                    ))}
                  </span>
                ))}
              </span>
            )}
            {/* Region-scoped only, and only when a number was actually measured — see the class
                comment for why a whole-sky count would be a claim the payload never made. */}
            {!wholeSky && regionsInScope != null && (
              <span
                data-testid="wf-topic-row-scope"
                className="wf-trow-s"
                // ⚠️ The INTERSECTION, the same set the count states. Publishing the served
                // roster here put a nine-region list under a label reading "1 region in scope",
                // so a reader hovering to learn WHICH regions was handed the ones the count had
                // just excluded.
                title={(scopedRegions || []).join(' · ') || undefined}
              >
                {`${regionsInScope} region${regionsInScope === 1 ? '' : 's'} in scope`}
              </span>
            )}
            {badge.safetyNote && (
              <span data-testid="wf-topic-row-safety" className="wf-trow-warn">
                <span aria-hidden="true">⚠ </span>
                {badge.safetyNote}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

WindowTopicRows.propTypes = {
  rows: PropTypes.arrayOf(PropTypes.shape({
    badge: PropTypes.shape({
      type: PropTypes.string,
      label: PropTypes.string.isRequired,
      detail: PropTypes.string,
      safetyNote: PropTypes.string,
    }).isRequired,
    /** The joined {@code HotTopic}, or null when the badge found none — see the class comment. */
    topic: PropTypes.object,
    wholeSky: PropTypes.bool,
    regionsInScope: PropTypes.number,
    /** The intersected region names — the same set {@code regionsInScope} counts. */
    scopedRegions: PropTypes.arrayOf(PropTypes.string),
  })).isRequired,
};
