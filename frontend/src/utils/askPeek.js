/**
 * The phone peek sheet's Ask states — pure, no React and no Leaflet (`docs/engineering/ask-photocast-plan.md`
 * §2.7's phone paragraph and state table, F4).
 *
 * <h2>Three facts decide the sheet, and only one of them is stored here</h2>
 *
 * <p>The sheet's state is DERIVED from three things, so it can never disagree with itself:
 * <ul>
 *   <li>whether the Ask section is <b>expanded</b> — a value of {@code MapView}'s own
 *       {@code openMapMenu} ({@code 'peek:ask'}, map-mobile-sheet-plan.md §5 D-1's one gate);</li>
 *   <li>whether another <b>section</b> is open ({@code 'peek:win'} / {@code 'peek:tide'} /
 *       {@code 'peek:lay'}, the same switch);</li>
 *   <li>whether the conversation has something <b>settled</b> on screen — Ask state
 *       ({@code AskContext.phase}), not the sheet's.</li>
 * </ul>
 * <b>"Minimised" is not a flag.</b> It is "a settled answer exists and neither the Ask section nor
 * any other section is open" — which is why every cell of the table that ends in the 112px line is the
 * same write ({@code openMapMenu → null}) as the cell that ends in the 126px collapsed row; the
 * conversation, not the sheet, decides which of the two the reader sees. A tab switch away and back
 * keeps the answer minimised by the same construction: it has only to close the expanded section.
 *
 * <h2>The heights</h2>
 *
 * <p>The numbers live here AND in `index.css` (the sheet's own rules); {@code askPeekCss.test.js} reads
 * the stylesheet and asserts the two agree, so a change to one fails against the other. They are
 * published to the rest of the map through {@code --psh} (the callout's band) and the camera's inset.
 */

/**
 * The phases the peek sheet treats as "something SETTLED to keep and to clear": not empty, not still
 * being fetched. Includes {@code 'plan'} (F5's phase — a conversation that has an answer and is looking
 * at one pick's plan is still an answer to keep, to minimise and to clear), which nothing enters yet.
 * Deliberately NOT {@code AskClearAnswer}'s own list: that text button must not appear in a plan phase
 * until F5 gives it a seam, and the two are different questions.
 */
export const PEEK_SETTLED_PHASES = Object.freeze(['answer', 'plan', 'cant', 'error']);

/**
 * Every height the sheet takes, in px (all clamped to {@code calc(100% - 64px)} by the stylesheet, so
 * the window pill — {@code top: 10px; height: 44px} — is never covered).
 *
 * <p>{@code plain} and {@code open} are the pre-Ask sheet, unchanged: with Ask off (the flag, an
 * unreachable server, a rewind) the sheet has no Ask row and is exactly what it was.
 */
export const PEEK_HEIGHT = Object.freeze({
  /** No Ask row: the three buttons alone (the sheet before Ask existed). */
  plain: 74,
  /** The Ask row above the three buttons. */
  collapsed: 126,
  /** The handle, the Ask row and one 44px line under it: rank, spot, time, "N picks ▴". */
  minimised: 112,
  /** A section open with no Ask row (the sheet before Ask existed). */
  open: 356,
  /** A section open with the Ask row above the buttons (the mock raises it by the row's 52px). */
  openWithAsk: 408,
  /** The Ask section: the field, the conversation, the answer. */
  ask: 470,
});

/**
 * The sheet's Ask state.
 *
 * <ul>
 *   <li>{@code 'off'} — no Ask row at all (Ask is not offered, or this is not a phone);</li>
 *   <li>{@code 'collapsed'} — the Ask entry row above the three buttons, nothing settled;</li>
 *   <li>{@code 'expanded'} — the Ask section is open (the field, then the conversation);</li>
 *   <li>{@code 'minimised'} — a settled answer, nothing else open: the entry row and the one line;</li>
 *   <li>{@code 'section'} — another section is open (Windows, Tide, Layers), under the entry row.</li>
 * </ul>
 *
 * @param {object} facts
 * @param {boolean} facts.offered the Ask row exists (Ask is on or unreachable, on a phone, on the tab)
 * @param {boolean} facts.expanded {@code openMapMenu === 'peek:ask'}
 * @param {?string} facts.section the other open section's name, or null
 * @param {string} facts.phase {@code AskContext.phase}
 * @returns {'off'|'collapsed'|'expanded'|'minimised'|'section'}
 */
export function askPeekMode({
  offered, expanded, section, phase,
}) {
  if (!offered) return 'off';
  if (expanded) return 'expanded';
  if (section != null) return 'section';
  return PEEK_SETTLED_PHASES.includes(phase) ? 'minimised' : 'collapsed';
}

/**
 * The height the sheet is heading for — the target, not whatever a transition has reached — so the
 * camera's inset and the label placer's obstacle never read a half-grown sheet.
 *
 * @param {'off'|'collapsed'|'expanded'|'minimised'|'section'} mode {@link askPeekMode}'s answer
 * @param {boolean} sectionOpen whether another section is open
 * @returns {number} px
 */
export function peekTargetHeight(mode, sectionOpen) {
  switch (mode) {
    case 'expanded': return PEEK_HEIGHT.ask;
    case 'minimised': return PEEK_HEIGHT.minimised;
    case 'collapsed': return PEEK_HEIGHT.collapsed;
    case 'section': return PEEK_HEIGHT.openWithAsk;
    default: return sectionOpen ? PEEK_HEIGHT.open : PEEK_HEIGHT.plain;
  }
}

/**
 * The height the sheet RESTS at, which is what {@code --psh} publishes: what the map's own chrome and
 * the callout's band must clear. A callout can exist only while the sheet is at rest (every route that
 * opens one collapses an open sheet first, and a sheet press clears the selection), so the open heights
 * never need publishing — and the destination of an open sheet is the right figure to hold while it is
 * open, since that is where it goes the moment the reader touches the map.
 *
 * @param {boolean} offered the Ask row exists
 * @param {boolean} settled the conversation has a settled answer on screen
 * @returns {number} px
 */
export function peekRestingHeight(offered, settled) {
  if (!offered) return PEEK_HEIGHT.plain;
  return settled ? PEEK_HEIGHT.minimised : PEEK_HEIGHT.collapsed;
}
