import PropTypes from 'prop-types';
import { spreadLines } from '../../utils/askSpread.js';

/**
 * The server's figures beside the model's sentence (`docs/engineering/ask-window-context-plan.md` §2.4,
 * §6 Q4 — W2): one quiet line per window the answer's prose may have quoted, under the summary, inside
 * the answer's one live region, on every surface and for every role.
 *
 * <p>Draws nothing for an answer that carries no windows (every answer stored before W1, every
 * can't-answer). The words are {@code spreadLines}'; this component only lays them out. The visible
 * text is hidden from assistive technology and a spoken twin says the same figures in words a screen
 * reader voices as counts ("4 stars or more", never "4 black star plus") — the Plan card's own pattern
 * for its spread row.
 *
 * <p>⚠️ Reads no role and sits in no media query: `askCss.test.js` pins the stylesheet, this file's
 * test pins the render for a LITE reader on a typed and on a Ready answer.
 *
 * @param {object} props
 * @param {Array<{windowId: string, spread: object}>} props.windows the answer's carried windows
 */
export default function AskWindowSpread({ windows }) {
  const lines = spreadLines(windows);
  if (lines.length === 0) return null;
  return (
    // `list-style: none` makes Safari drop the list role; the explicit role keeps these a list there
    // (the picks list's own waiver of the redundancy rule).
    // eslint-disable-next-line jsx-a11y/no-redundant-roles
    <ul className="wf-ask-spread" role="list" aria-label="Rated places, by window" data-testid="ask-spread">
      {lines.map((line) => (
        <li
          key={line.windowId ?? 'none'}
          className="wf-ask-spread-l"
          data-window={line.windowId ?? undefined}
          data-testid="ask-spread-line"
        >
          <span aria-hidden="true">{line.text}</span>
          <span className="sr-only">{line.spoken}</span>
        </li>
      ))}
    </ul>
  );
}

AskWindowSpread.propTypes = {
  windows: PropTypes.arrayOf(PropTypes.shape({
    windowId: PropTypes.string,
    spread: PropTypes.object,
  })),
};
