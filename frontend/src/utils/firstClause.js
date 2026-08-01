/**
 * The opening clause of a generated sentence — what a hover-weight panel is allowed to show.
 *
 * <p>Claude's slot summaries are one or two long sentences built to be read in a popup. Dropping
 * the whole paragraph into a tooltip is what made the Close-to-home peek read as modal content
 * summoned by an accident of the pointer; the peek shows a *glance*, and the click shows the read.
 *
 * <p>Truncation is at a clause boundary rather than a character count, so the fragment is always a
 * grammatical unit and never stops mid-word or mid-number.
 */

/**
 * Below this, a "clause" is a fragment rather than a statement — `Clear, but…` truncated at the
 * first comma yields `Clear.`, which tells the reader nothing they could not see on the card. The
 * scan therefore ignores breaks that land before this many characters and takes the next one.
 */
const MIN_CLAUSE_CHARS = 28;

/**
 * Clause boundaries, in one pass:
 * <ul>
 *   <li>`,` `;` `:` — the ordinary ones;</li>
 *   <li>a dash <em>surrounded by whitespace</em>, so `high-cloud` and `dew-point` stay whole;</li>
 *   <li>a sentence terminator followed by whitespace or the end, so the `.` in `4.5` and in
 *       `0.3 µg` is not a boundary.</li>
 * </ul>
 */
const BOUNDARY = /[,;:]|\s[—–-]\s|[.!?](?:\s|$)/g;

/** Trailing punctuation and space left behind once the boundary itself is cut off. */
const TRAILING_JUNK = /[\s,;:—–-]+$/;

/**
 * Reduces a generated summary to its first clause, terminated with a full stop.
 *
 * @param {?string} text the full summary, or null
 * @returns {?string} the first clause ending in `.`, or null when there is no text to reduce
 */
export function firstClause(text) {
  if (text == null) return null;
  const trimmed = String(text).trim();
  if (trimmed === '') return null;

  // A fresh regex per call: BOUNDARY is /g, and a module-level lastIndex shared between calls
  // would make the same input return different answers depending on what was truncated before it.
  const scan = new RegExp(BOUNDARY.source, 'g');
  let match = scan.exec(trimmed);
  while (match !== null) {
    if (match.index >= MIN_CLAUSE_CHARS) {
      const head = trimmed.slice(0, match.index).replace(TRAILING_JUNK, '');
      if (head !== '') return `${head}.`;
      break;
    }
    match = scan.exec(trimmed);
  }

  // No boundary worth cutting at — the summary is already one clause.
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

export default firstClause;
