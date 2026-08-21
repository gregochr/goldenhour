/**
 * The pane's ordered contents — window cards, with the away days that interrupt them accounted for.
 *
 * <h2>Why the absence is counted at all</h2>
 *
 * <p>Through P8 {@code buildWindowCards} simply dropped away days, and the reason was sound: a
 * travel day still carries slots (the pipeline skips <em>evaluation</em>, not collection), so the
 * projector turns one into STAND_DOWN or AWAITING and a naive card would read "Poor" directly under
 * a rail tile reading "Not forecast" — a contradiction on one screen.
 *
 * <p>What that left behind is a quieter problem, and it is the one this module fixes. The six-event
 * cap in {@code selectUpcomingEvents} is applied <b>before</b> the travel filter, so an away day
 * spends one of the six slots and then vanishes: a reader with two away days sees four cards and no
 * account of the missing two, and the pane's own date order silently skips a day. Counting the
 * block is therefore not decoration — it is what makes the cap's arithmetic legible.
 *
 * <h2>What is left of it after M5, and why the module survives</h2>
 *
 * <p>⚠️ <b>Nothing renders an away ROW any more.</b> M2 deleted the card list, and a travel day has
 * been a cell of the matrix ever since — {@code WindowFirstHeatStrip} draws it from the away flag on
 * its own descriptor, as a div and never a button. M5 deleted the promoted strip, which was the last
 * reader of this list's <em>contents</em>. So the away block's rendered payload went with it: its
 * date label, its travel note and its window count each had exactly one consumer, that consumer is
 * gone, and a field kept "in case" is the dead weight this phase exists to sweep.
 *
 * <p>What survives is the <em>shape</em>: one item per rendered thing, in date order, with a run of
 * consecutive away days folded into one item. That is the empty-state line's denominator — "No
 * windows to show." must stay off a pane whose only content is a travel day, and the card list
 * alone cannot answer that, because a travel day has no card. It is the one question this module is
 * still asked. Should an away surface ever want a label or a note again, {@code /api/travel-days}
 * still carries both; they are deleted here rather than parked because the reader went, not the
 * data.
 *
 * <p><b>No "Mark yourself back →".</b> The mock's action needs the travel-day admin surface, which
 * is ADMIN-only ({@code /api/admin/travel-days}) and which this arm's tab bar has no route to. A
 * control that is inert for most readers and unreachable for the rest is the demo control plan §6
 * bans.
 */

/** The next calendar day, in the same `YYYY-MM-DD` form. */
function nextDay(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/**
 * Folds the away days back into the card list, in date order.
 *
 * <p>The walk is over {@code upcomingEvents} rather than over the cards, because the cards are the
 * <em>surviving</em> events and so carry no record of what was removed. {@code cards} is consumed in
 * order and the alignment is exact by construction: {@code buildWindowCards} builds from the same
 * array with the same travel-day filter, in the same order.
 *
 * @param {Array} upcomingEvents [{date, targetType}], ordered, already capped
 * @param {Array} cards          the descriptors from {@code buildWindowCards}
 * @param {?Set}  travelDayDates dates the operator is away
 * @returns {Array} `[{kind: 'card', key, card} | {kind: 'away', key, dates}]`
 */
export function buildPaneItems(upcomingEvents, cards, travelDayDates) {
  const items = [];
  let cardIndex = 0;
  let block = null;

  for (const event of upcomingEvents || []) {
    if (travelDayDates?.has(event.date)) {
      // A run of consecutive away days is ONE row. `block` is nulled by the first live event below,
      // so two runs either side of a forecast day stay two rows — the gap they describe is not the
      // same gap.
      //
      // ⚠️ It breaks on a CALENDAR gap as well, not only on a live event. The walk is over events,
      // and a day contributing none of them is invisible to it — so an away Wednesday and an away
      // Friday either side of a Thursday with no events would have folded into one row labelled
      // "Wed 5 – Fri 7", claiming Thursday was a travel day when nothing said so. The event walk
      // cannot see that day; the date arithmetic can.
      const last = block?.dates[block.dates.length - 1];
      if (block && last !== event.date && nextDay(last) !== event.date) block = null;
      if (!block) {
        block = { kind: 'away', key: `away:${event.date}`, dates: [] };
        items.push(block);
      }
      if (block.dates[block.dates.length - 1] !== event.date) block.dates.push(event.date);
      continue;
    }
    block = null;
    const card = cards[cardIndex];
    cardIndex += 1;
    // Defensive: a caller that filtered the cards differently would otherwise emit `undefined` and
    // crash the pane. Skipping keeps the away rows correct, which is the half this module owns.
    if (card) items.push({ kind: 'card', key: card.key, card });
  }

  return items;
}
