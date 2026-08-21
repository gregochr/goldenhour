/**
 * The Plan pane's day × event grid — the shape of the week, derived from the rendered windows.
 *
 * <h2>The layout IS the derivation</h2>
 *
 * <p>The v3 design (plan-matrix-plan.md §6 M1) replaces the strip's single row of six with a
 * matrix: one column per distinct day, sunrise on the upper row and sunset on the lower one.
 * Reading down a column is one day; reading across a row compares the same light day to day. The
 * cells are <b>explicitly placed</b> (`--c`/`--r` custom properties) rather than flowed, which is
 * what lets the phone drop the identical markup into two columns under a spanning day header
 * without a second render path.
 *
 * <p>So the column count is data, never a constant: a forecast spanning seven days yields seven
 * columns with no layout change. {@code columns} is published for the stylesheet's `--dc`.
 *
 * <h2>Two holes are information; every other hole is silence</h2>
 *
 * <p>A six-event forecast across four days leaves two cells empty <em>by definition</em> — this
 * morning has already gone, and the last evening is past the end of what the backend renders — and
 * the design says so in the cell rather than closing the gap, because the gap is the reader's own
 * question ("why does Tuesday start at sunset?").
 *
 * <p><b>Both claims are narrow on purpose.</b> "This morning has gone" is made only for the FIRST
 * day, and only when that day is today: the backend drops elapsed events, so a missing sunrise on
 * the first day can only be one that passed — but if every one of today's windows has passed, the
 * first rendered day is tomorrow, and a missing sunrise there would mean something else entirely.
 * "Past the end of the forecast" is made only for the LAST day, where the six-event cap truncates.
 * Any other hole — which the payload contract does not produce, and which a future one might —
 * renders as a dashed cell with <b>no words</b>. Degrade is silence, never synthesis (plan §3
 * rule 6): a cell that cannot explain itself must not guess.
 *
 * <h2>Away days keep their cells</h2>
 *
 * <p>{@code buildHeatStripCards} already folds travel days back in as descriptors carrying
 * {@code away: true} — the strip has always drawn them, because a missing thumbnail silently
 * renumbers the shape of the week. They are cells of the matrix like any other; the caller draws
 * them as divs rather than buttons (plan §3 rule 14: a control with no visible effect is banned).
 * <p>{@code day.away} marks a day whose every rendered window is a travel one. Nothing renders it
 * yet — each cell already knows it is away from its own descriptor, which is what the caller draws
 * off — and it is published because the column is the unit a whole-day treatment would act on and
 * re-deriving it per cell is how two cells of one column come to disagree. If M5's sweep finds no
 * use for it, it should go rather than stay as a tested field with no reader.
 */

/** The two rows of the matrix, in render order — the grid rows the caller places cells into. */
export const MATRIX_ROW = { header: 1, sunrise: 2, sunset: 3 };

/**
 * Which slot a target type occupies — and, read as a predicate, which target types this grid can
 * place at all. See the guard in {@link buildWindowMatrix} for why the second job matters.
 */
const SLOT_BY_TARGET_TYPE = { SUNRISE: 'am', SUNSET: 'pm' };

/** The empty-cell copy, keyed by the only two holes this module will explain. */
export const EMPTY_CELL_COPY = {
  morningGone: 'this morning has gone',
  beyondForecast: 'past the end of the forecast',
};

/**
 * Day-of-month for an ISO date, read at noon UTC so no timezone can move the day.
 *
 * <p>Exported since M2 for the window popup's date tile — see {@code calDow}'s note for why the
 * dialog reads the grid's own functions rather than spelling the tile a second time.
 */
export function dayNumber(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`).getUTCDate();
}

/**
 * Groups the rendered windows into day columns.
 *
 * <p>The input is {@code buildHeatStripCards}' output — chronological, travel days included — so
 * first-appearance order over {@code card.date} IS date order and no sort is needed here. Sorting
 * would be a second opinion about an ordering the payload already owns.
 *
 * @param {Array<{key: string, date: string, targetType: string, dow: string, away: boolean}>} cards
 *        the thumbnail descriptors, chronological
 * @param {?string} todayStr today's ISO date in Europe/London, for the today column and for the
 *        one empty-cell claim that depends on it
 * @returns {{columns: number, days: Array<{date: string, dow: string, dn: number, today: boolean,
 *          away: boolean, solo: boolean, am: ?object, pm: ?object, amEmpty: ?string,
 *          pmEmpty: ?string}>}} the grid
 */
export function buildWindowMatrix(cards, todayStr) {
  const days = [];
  const byDate = new Map();
  for (const card of Array.isArray(cards) ? cards : []) {
    // ⚠️ A card the grid has no ROW for opens no column either, and the guard belongs here rather
    // than at the placement below. `TargetType` has exactly two constants, so this is unreachable
    // today — but the failure it prevents is total and silent: a third kind would create its day (a
    // column, a header, two dashed cells) while landing in neither slot, so a real window would
    // vanish underneath two empty-cell sentences that each claim to explain it. Dropping the window
    // instead at least leaves a shape a reader can see is short.
    if (!card || !card.date || !SLOT_BY_TARGET_TYPE[card.targetType]) continue;
    let day = byDate.get(card.date);
    if (!day) {
      day = {
        date: card.date,
        dow: card.dow || '',
        dn: dayNumber(card.date),
        today: card.date === todayStr,
        away: true,
        solo: false,
        am: null,
        pm: null,
        amEmpty: null,
        pmEmpty: null,
      };
      byDate.set(card.date, day);
      days.push(day);
    }
    day[SLOT_BY_TARGET_TYPE[card.targetType]] = card;
    // A day is an away day only when EVERY window it renders is one. A payload cannot produce a
    // half-travel day today (the travel filter is per date), but the flag describes a whole column,
    // so it is derived from the cells rather than assumed from the first of them.
    if (!card.away) day.away = false;
  }

  days.forEach((day, index) => {
    day.solo = (day.am == null) !== (day.pm == null);
    // See the class comment: both claims are narrow, and any other hole gets no words at all.
    if (day.am == null && index === 0 && day.today) day.amEmpty = EMPTY_CELL_COPY.morningGone;
    if (day.pm == null && index === days.length - 1) day.pmEmpty = EMPTY_CELL_COPY.beyondForecast;
  });

  // A day with neither window cannot arise from the fold above — a day exists only because a card
  // the grid can PLACE named it (see the guard) — so `away` never survives as `true` on an empty
  // day, and `solo` is never true of a day holding nothing.
  return { columns: days.length, days };
}
