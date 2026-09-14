import PropTypes from 'prop-types';
import { VERDICT_LABEL, eventWord } from '../../utils/windowFirstCards.js';
import { verdictRegionLabel } from '../../utils/mapVerdict.js';
import { PICK_TEXT } from './WindowControl.jsx';

/**
 * The Map tab's landing card — map-landing-plan.md §3 L4,
 * `docs/design/map-landing/README.md` §4.
 *
 * <p>On a cold open the map answers one question: <em>should I go tonight or in the morning</em>.
 * Two rows, each with its verdict and the region that verdict is true of; the forecast's picks ride
 * the rows they belong to; and when neither row clears Poor the card stops comparing and names the
 * next window that is actually Worth it.
 *
 * <p><b>Purely presentational.</b> Which windows, what the header says, which picks survive and
 * whether the all-Poor branch fires are all decided by {@code utils/mapLanding.landingCardModel},
 * which is pure and directly tested. This component renders that model and owns no rule of its own
 * — the correction L1 and the doors series were both given at review.
 *
 * <h2>⚠️ It is not a dialog, and must not become one</h2>
 *
 * <p>It is an opening statement over the map, not a layer in front of it. It takes no focus, traps
 * none, carries no {@code aria-modal}, and is not a {@code Modal} — the Plan screen's two-deep
 * dialog invariant (CLAUDE.md) is not this card's to spend. A {@code section} with an
 * {@code aria-label} is the whole of its semantics: a named region a screen-reader user can reach
 * and skip, which is what it is.
 *
 * <h2>⚠️ Dismissal is deliberately narrow</h2>
 *
 * <p>The close button, {@code Escape} ({@code MapView} owns that listener) or selecting a row —
 * and <b>nothing else</b>. Not a map click, drag, zoom, wheel or outside tap: panning to the region
 * the card has just named is <em>reading</em> it, and dismiss-on-drag punished exactly the move the
 * card invites. ⚠️ <b>Do not reach for {@code useOutsideDismiss} here</b>; that hook carries the map
 * panels' rule, which is a different one, and its own doc names this card as the exception.
 *
 * <h2>The phone keeps this card, inset — it does not become a `BottomSheet`</h2>
 *
 * <p>Plan step 9 left the choice open. A {@code BottomSheet} brings a {@code fixed inset-0}
 * backdrop whose {@code onClick} is {@code onClose} — an outside tap, which is the one dismissal
 * route this card forbids. Keeping the card and insetting it to the frame (index.css's ≤639px rule)
 * makes the dismissal rules identical on every viewport by construction rather than by matching two
 * implementations up.
 */
export default function MapLandingCard({
  model, scopeLabel = '', scopeIsArea = true, activeId = null, onSelect, onDismiss,
}) {
  const {
    rows, header, allPoor, lead, nextUp, picks,
  } = model;
  // Nothing ahead of the reader to compare — see `landingRows`' `served` gate. The card does not
  // draw an empty state: an opening statement with nothing to say is worse than no card.
  if (rows.length === 0) return null;

  return (
    // `aria-labelledby`, not `aria-label`: a named `section` is a landmark, so an `aria-label`
    // repeating the visible title makes a screen reader announce it twice on entry. Pointing at the
    // title element gives the landmark the same name with no double-speak. The id is a constant and
    // safe as one — the card is tab-only and `MapView` mounts it behind `!overlayMode`, so the two
    // MapView instances can never both render it.
    <section className="wf-land" data-testid="wf-land" aria-labelledby={TITLE_ID}>
      <div className="wf-land-head">
        <b className="wf-land-title" id={TITLE_ID} data-testid="wf-land-head">{header}</b>
        {/* ⚠️ The count phrase is dropped for a single row, because `landingHeader` already returns
            "Your next window" there — the first cut printed the identical sentence twice, stacked,
            and a third time as the landmark name. */}
        <span className="wf-land-sub" data-testid="wf-land-sub">
          {rows.length > 1 ? `Your next two windows${scopeLabel ? ' · ' : ''}` : ''}
          {scopeLabel}
        </span>
        <button
          type="button"
          data-testid="wf-land-close"
          className="wf-land-x"
          // ⚠️ Not the bare "Dismiss" this shipped with: the colour-scale notice and the LITE
          // viewline chip both mount in the same `!overlayMode` branch with their own "Dismiss",
          // and the cold-open case this card exists for is exactly when an unread notice is also on
          // screen. Three identically-named buttons in NVDA's Elements List name nothing.
          aria-label="Dismiss this card"
          onClick={onDismiss}
        >
          &#10005;
        </button>
      </div>

      <div className="wf-land-rows">
        {rows.map(({ row, verdict }) => (
          <button
            key={row.id}
            type="button"
            data-testid="wf-land-row"
            data-ev-id={row.id}
            // ⚠️ The state is PROGRAMMATIC as well as painted. It shipped as a background tint
            // alone — measured 1.16:1 against the unselected row, under 1.4.11's 3:1 for a state
            // indicator, discarded entirely under forced-colors, and invisible to every screen
            // reader. Both siblings already do this: `WindowControl`'s rows carry `aria-selected`
            // (they are `option`s), `RegionsJump`'s carry `aria-current`. A plain button takes
            // `aria-current`, so that is the one. The left rule below is the second visual channel.
            aria-current={row.id === activeId ? 'true' : undefined}
            className={`wf-land-row${row.id === activeId ? ' on' : ''}`}
            onClick={() => onSelect(row)}
          >
            {/* `.wf-hc-sun` is the day rail's kind-chip vocabulary (matrix-axis D14), the same one
                the window pill's chip uses — never a second one. The CSS uppercases it, so the
                app's own lower-case `eventWord` is the right source and no third spelling of
                SUNRISE/SUNSET is minted here. */}
            <span className={`wf-hc-sun ${row.eventType === 'SUNRISE' ? 'am' : 'pm'}`}>
              {eventWord(row.eventType)}
            </span>
            {/* The bare text nodes keep the contributions apart in the accessible name.
                ⚠️ **They are belt-and-braces here, NOT load-bearing, and an earlier revision of this
                comment claimed the opposite.** jsdom's polyfill trims each contribution (browsers do
                not) — and this project measured across Chromium, WebKit and Firefox that every
                engine inserts a space when the siblings are blockified, which a flex item is;
                `.wf-land-row` is `display: flex`. The run-together string the old comment quoted is
                a **jsdom** artefact, and taking it for a browser defect already cost this project a
                whole build once. The nodes cost nothing and keep jsdom's name equal to the
                browser's, which is worth having; the ⚠️ was not. */}
            {' '}
            <span className="wf-land-when">
              <b>{row.dayLabel ?? row.label}</b>
              {' '}
              {row.time && <span className="wf-land-time">{row.time}</span>}
            </span>
            {' '}
            <PickChip kind={row.pickKind} />
            {' '}
            <VerdictCell verdict={verdict} scopeIsArea={scopeIsArea} />
          </button>
        ))}
      </div>

      {allPoor ? (
        <p className="wf-land-none" data-testid="wf-land-none">
          {lead}
          {nextUp && (
            <>
              {' Next up: '}
              <button
                type="button"
                data-testid="wf-land-next"
                data-ev-id={nextUp.row.id}
                className="wf-land-go"
                onClick={() => onSelect(nextUp.row)}
              >
                {[
                  nextUp.row.label,
                  verdictRegionLabel(nextUp.verdict, { scopeIsArea }),
                  VERDICT_LABEL[nextUp.verdict.tier],
                ].filter(Boolean).join(' · ')}
                {/* `aria-hidden`, matching the quiet line's own chevron below: a bare `›` in the
                    name reads as "right angle quote" on VoiceOver. */}
                <span aria-hidden="true">{' \u203A'}</span>
              </button>
            </>
          )}
        </p>
      ) : picks.map(({ row, kind }) => (
        // The quiet line for a pick that is NOT one of the two rows. No colour — this is a pointer
        // to a different question ("when is the best window this week"), and colouring it would put
        // it in competition with the verdicts above, which answer the one the card is asking.
        <button
          key={row.id}
          type="button"
          // `wf-land-pick-line`, not `wf-land-pick` — that name is the CSS class of the row
          // MEDALLION, and one name for two different elements is a trap for the next reader.
          data-testid="wf-land-pick-line"
          data-ev-id={row.id}
          data-pick={kind}
          className="wf-land-pick-row"
          onClick={() => onSelect(row)}
        >
          <span className="wf-land-pick-lbl">{PICK_TEXT[kind].words}</span>
          {' '}
          <span className="wf-land-pick-win">{row.label}</span>
          {' '}
          <span aria-hidden="true" className="wf-land-pick-go">&#8250;</span>
        </button>
      ))}

      <div className="wf-land-foot">
        Stays until you close it &middot; after that the pill carries the verdict, the region and
        the medallion
      </div>
    </section>
  );
}

/** The card title's id — see the `aria-labelledby` note on the `section`. */
const TITLE_ID = 'wf-land-title';

MapLandingCard.propTypes = {
  /** From `utils/mapLanding.landingCardModel` — every decision this card renders. */
  model: PropTypes.shape({
    rows: PropTypes.array.isRequired,
    header: PropTypes.string.isRequired,
    allPoor: PropTypes.bool.isRequired,
    lead: PropTypes.string,
    nextUp: PropTypes.object,
    picks: PropTypes.array.isRequired,
  }).isRequired,
  /** The scope segment's own words ("My area" / "Around Keswick" / "Everywhere") for the kicker. */
  scopeLabel: PropTypes.string,
  /** Whether the scope segment reads "My area" — decides only the all-in-scope region wording. */
  scopeIsArea: PropTypes.bool,
  /** The EV row id the map is currently showing, so its row reads as current. */
  activeId: PropTypes.string,
  /** Called with the chosen EV row. The caller dismisses — selecting a row closes the card. */
  onSelect: PropTypes.func.isRequired,
  onDismiss: PropTypes.func.isRequired,
};

/**
 * The pick medallion on a row it belongs to — the SAME outline-chip vocabulary the window pill
 * wears, sharing its `[data-pick]` ink and weight rules in index.css.
 *
 * <p>⚠️ <b>Its own class, not the pill's.</b> The rule to avoid is `.wf-map-tab
 * .wf-win-pick-words` at ≤811px, which visually-hides the WORDS under the pill's width pressure —
 * and its selector needs no `.wf-win-pill` ancestor, so a card reusing that class name would have
 * lost its words below 812px for a reason that is not about a 376px card. (An earlier revision of
 * this note named `.wf-win-pick`, the chip class, which carries no such rule; the conclusion was
 * right and the sentence was not.) Measured in headless Chromium against the built sheet: the
 * card's words stay `position: static` at 768px and below while the pill's go `absolute`. The two
 * share the ink and weight rules — one WCAG 1.4.11 decision, one place — and differ in their box.
 */
function PickChip({ kind }) {
  if (!kind) return null;
  return (
    <span className="wf-land-pick" data-pick={kind} data-testid="wf-land-medallion">
      <i aria-hidden="true" className="wf-land-pick-glyph">{PICK_TEXT[kind].glyph}</i>
      {' '}
      <span className="wf-land-pick-words">{PICK_TEXT[kind].words}</span>
    </span>
  );
}

PickChip.propTypes = { kind: PropTypes.oneOf(['best', 'also']) };

/**
 * A row's verdict word with the region it is true of stacked under it — the pill's own two
 * functions ({@code VERDICT_LABEL} and {@code verdictRegionLabel}), so the card and the control
 * six pixels away can never print different words for one window.
 *
 * <p>Renders nothing at all when there is no verdict: an unscored window is not a Poor one, and a
 * served window nothing is rated in is still a window worth showing (see {@code landingRows}).
 */
function VerdictCell({ verdict, scopeIsArea }) {
  if (!verdict) return null;
  const region = verdictRegionLabel(verdict, { scopeIsArea });
  return (
    <span className="wf-land-verdict" data-tier={verdict.tier} data-testid="wf-land-verdict">
      <b className="wf-land-verdict-word" data-testid="wf-land-verdict-word">
        {VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING}
      </b>
      {' '}
      {region && (
        <span className="wf-land-verdict-region" data-testid="wf-land-verdict-region">{region}</span>
      )}
    </span>
  );
}

VerdictCell.propTypes = {
  verdict: PropTypes.shape({
    tier: PropTypes.string.isRequired,
    regionName: PropTypes.string,
    sharingCount: PropTypes.number,
    allInScope: PropTypes.bool,
  }),
  scopeIsArea: PropTypes.bool,
};
