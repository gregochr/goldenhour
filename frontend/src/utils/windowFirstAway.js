/**
 * The pane's ordered contents — window cards, with the away days that interrupt them made visible.
 *
 * <h2>Why the absence needs a row at all</h2>
 *
 * <p>Through P8 {@code buildWindowCards} simply dropped away days, and the reason was sound: a
 * travel day still carries slots (the pipeline skips <em>evaluation</em>, not collection), so the
 * projector turns one into STAND_DOWN or AWAITING and a naive card would read "Poor" directly under
 * a rail tile reading "Not forecast" — a contradiction on one screen.
 *
 * <p>What that left behind is a quieter problem, and it is the one this module fixes. The six-event
 * cap in {@code selectUpcomingEvents} is applied <b>before</b> the travel filter, so an away day
 * spends one of the six slots and then vanishes: a reader with two away days sees four cards and no
 * account of the missing two, and the pane's own date order silently skips a day. The row is
 * therefore not decoration — it is what makes the cap's arithmetic legible.
 *
 * <h2>Chronological, never appended</h2>
 *
 * <p>The mock renders the skipped block once, after the whole window list. That reads as a footnote
 * and it breaks the pane's only ordering spine, which is time — the same argument this project
 * already settled for Hot Topics, where a multi-day tide run stays one card per day in date order
 * rather than collapsing to a single block at one point in the list. A gap between Tuesday and
 * Thursday belongs between Tuesday and Thursday. So the block is emitted in place, and a run of
 * consecutive away days folds into one row rather than repeating the same sentence twice.
 *
 * <h2>What the row may claim</h2>
 *
 * <p>"Not forecast" is the rail tile's own word for this state and is the one used here, rather than
 * the mock's "not generated" — generation is exactly what <em>did</em> happen. The note is the
 * travel range's own {@code note} field, so nothing is invented; a block spanned by two ranges with
 * different notes carries none, because there is no honest way to attribute one sentence to both.
 *
 * <p><b>No "Mark yourself back →".</b> The mock's action needs the travel-day admin surface, which
 * is ADMIN-only ({@code /api/admin/travel-days}) and which this arm's tab bar has no route to. A
 * control that is inert for most readers and unreachable for the rest is the demo control plan §6
 * bans.
 */

/** `Mon 3` — the mock's own short form, used for both ends of the range. */
function shortDay(dateStr) {
  const d = new Date(`${dateStr}T12:00:00Z`);
  const dow = d.toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
  return `${dow} ${d.getUTCDate()}`;
}

/**
 * The block's date label — one day, or a range.
 *
 * @param {string[]} dates ISO dates, ascending
 * @returns {string} `Mon 3` or `Mon 3 – Tue 4`
 */
export function awayDateLabel(dates) {
  const first = shortDay(dates[0]);
  if (dates.length === 1) return first;
  return `${first} – ${shortDay(dates[dates.length - 1])}`;
}

/**
 * The single note covering <em>every</em> date in the block, or null.
 *
 * <p>Null in four cases, and they are deliberately not distinguished on screen: no range carries a
 * note, two ranges disagree, one day is covered only by an un-noted range, or nothing matches. In
 * each of them the row says "away" and stops — naming one reason would attribute it to days it does
 * not describe.
 *
 * <p><b>Coverage is tested per day, not per range, and the difference is a false claim.</b> The
 * first version discarded un-noted ranges before testing coverage, so a block spanned by
 * {@code [{05→05, note:'Skye'}, {06→06, note:null}]} collected exactly one note and printed it —
 * a sentence about Wednesday rendered against a row that also covers Thursday, which no range in
 * the payload says anything about. Un-noted ranges are the normal case ({@code note} is nullable on
 * the entity and {@code TravelDaysView} sends null for an empty box), and the backend does not merge
 * adjacent ranges, so the mixed block is an ordinary shape rather than a contrived one.
 *
 * <p>Note what the per-day rule deliberately keeps working: two adjacent ranges carrying the
 * <em>same</em> note still yield that note. Swapping {@code some} for {@code every} would have
 * fixed the false claim and broken this.
 *
 * @param {string[]} dates        the block's ISO dates
 * @param {Array}    travelRanges [{startDate, endDate, note}]
 * @returns {?string} the note, or null
 */
function noteFor(dates, travelRanges) {
  const ranges = travelRanges || [];
  const covers = (range, date) => date >= range.startDate && date <= range.endDate;
  const notes = new Set();
  for (const date of dates) {
    const forDay = new Set(
      ranges.filter((r) => r?.note && covers(r, date)).map((r) => r.note.trim()),
    );
    // A day with no note of its own, or with two, silences the WHOLE block: the row may not borrow
    // a sentence from the day next to it.
    if (forDay.size !== 1) return null;
    notes.add([...forDay][0]);
    if (notes.size > 1) return null;
  }
  return notes.size === 1 ? [...notes][0] : null;
}

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
 * @param {Array} travelRanges   [{startDate, endDate, note}] from {@code /api/travel-days}
 * @returns {Array} `[{kind: 'card', key, card} | {kind: 'away', key, dates, label, note, windowCount}]`
 */
export function buildPaneItems(upcomingEvents, cards, travelDayDates, travelRanges) {
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
        block = { kind: 'away', key: `away:${event.date}`, dates: [], windowCount: 0 };
        items.push(block);
      }
      if (block.dates[block.dates.length - 1] !== event.date) block.dates.push(event.date);
      block.windowCount += 1;
      continue;
    }
    block = null;
    const card = cards[cardIndex];
    cardIndex += 1;
    // Defensive: a caller that filtered the cards differently would otherwise emit `undefined` and
    // crash the pane. Skipping keeps the away rows correct, which is the half this module owns.
    if (card) items.push({ kind: 'card', key: card.key, card });
  }

  for (const item of items) {
    if (item.kind !== 'away') continue;
    item.label = awayDateLabel(item.dates);
    item.note = noteFor(item.dates, travelRanges);
  }
  return items;
}
