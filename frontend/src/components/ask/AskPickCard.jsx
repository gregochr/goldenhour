import PropTypes from 'prop-types';
import { PickScore, PickTide, pickWhen } from './AskPickFacts.jsx';
import { pickCardShape } from './askShapes.js';

/**
 * One pick card (design README, Components → Pick card), built from a card of
 * `utils/askModel.js`'s {@code buildPickCards}.
 *
 * <p>Every figure on it is a served fact joined from the briefing — the name, the verdict word and
 * the star, the event time, the tide — plus the reader's HOME drive time (⌂). The server's own text
 * is only the {@code why}. Nothing is computed here.
 *
 * <p><b>Structure.</b> The rank circle is a sibling of the card's button, not inside it: a button's
 * children are presentational to a screen reader, so a circle inside it could not carry the name
 * "Pick 1, Whitby, Saturday sunrise, 5 stars" that the map's marker for the same pick carries
 * (plan §2.7). The button holds the three text lines and is stretched over the whole card (CSS), so
 * the circle is not a dead zone to a pointer. Because the circle is not focusable, the button also
 * leads with an sr-only "Pick N" of its own, so a reader tabbing through the cards hears the number
 * the map and the prose use; in browse mode the rank is therefore said twice, which is the lesser
 * cost. Below the button is the {@code actions} row, a sibling of it and above its stretched hit
 * area (a button inside a button is invalid): F5's "Plan this ›" and F1b's "Show on map ›" go
 * there.
 *
 * <p><b>Selection is {@code aria-current}, not {@code aria-pressed}.</b> A pressed button promises
 * a second press undoes it; choosing a pick does not (it moves the map and the window to it), and a
 * toggle that cannot be released is a false affordance. Clearing the selection is the answer's, not
 * the card's.
 *
 * <p>The tide is the PREFERENCE axis: the wave is the same glyph the map's chip draws (a letter for
 * a match, an arrow for a miss) and the words are the same served state, in the tide colour. The
 * visible words are hidden from assistive technology and the app's own accessible clause
 * ({@code tideAccessibleClause}) stands in their place, so the one fact is said once.
 *
 * @param {object} props
 * @param {object} props.card one element of {@code buildPickCards}' result
 * @param {boolean} [props.selected] this card is the selected pick
 * @param {function(number): void} [props.onSelect] called with the card's rank
 * @param {React.ReactNode} [props.actions] controls for the card's own row, below the text
 */
export default function AskPickCard({
  card, selected = false, onSelect, actions = null,
}) {
  return (
    <li
      className="wf-ask-pick"
      data-selected={selected ? 'true' : undefined}
      data-ask-pick={card.rank}
      data-testid={`ask-pick-${card.rank}`}
    >
      <span
        className="wf-ask-rk"
        role="img"
        aria-label={card.label}
        data-testid={`ask-pick-rank-${card.rank}`}
      >
        <span aria-hidden="true">{card.rank}</span>
      </span>
      <button
        type="button"
        className="wf-ask-pick-main"
        aria-current={selected ? 'true' : undefined}
        data-ask-pick-select={card.rank}
        data-testid={`ask-pick-select-${card.rank}`}
        onClick={() => onSelect?.(card.rank)}
      >
        <span className="sr-only">{`Pick ${card.rank}, `}</span>
        <span className="wf-ask-l1">
          <span className="wf-ask-nm" data-testid={`ask-pick-name-${card.rank}`}>{card.name}</span>
          <PickScore card={card} testId={`ask-pick-score-${card.rank}`} />
        </span>
        <span className="wf-ask-l2">
          <span className="wf-ask-evb" data-target={card.targetType}>{card.targetType}</span>
          <span data-testid={`ask-pick-when-${card.rank}`}>{pickWhen(card)}</span>
          {card.driveLabel && (
            <>
              <span className="wf-ask-sep" aria-hidden="true" />
              <span data-testid={`ask-pick-drive-${card.rank}`}>
                <span aria-hidden="true">⌂ </span>
                <span className="sr-only">Drive from home </span>
                {card.driveLabel}
              </span>
            </>
          )}
          {card.tide && (
            <>
              <span className="wf-ask-sep" aria-hidden="true" />
              <span className="wf-ask-tide" data-tier={card.tide.tier} data-testid={`ask-pick-tide-${card.rank}`}>
                <PickTide tide={card.tide} />
              </span>
            </>
          )}
        </span>
        {card.why && <span className="wf-ask-why">{card.why}</span>}
      </button>
      {actions && <div className="wf-ask-pick-act" data-testid={`ask-pick-actions-${card.rank}`}>{actions}</div>}
    </li>
  );
}

AskPickCard.propTypes = {
  card: pickCardShape.isRequired,
  selected: PropTypes.bool,
  onSelect: PropTypes.func,
  actions: PropTypes.node,
};
