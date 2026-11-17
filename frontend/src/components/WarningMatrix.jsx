import React from 'react';
import PropTypes from 'prop-types';
import { MATRIX_COLOURS, MATRIX_GRID, matrixText, matrixValue } from '../utils/warnings.js';

const CELL = 22;
const STEP = 24;
const LEFT = 22;
const TOP = 2;
const GRID = STEP * 4 - (STEP - CELL);

/**
 * The Met Office likelihood and impact matrix (style guide v1.4, page 9; weather-warnings-plan.md section 12.1),
 * drawn from values: a 4 by 4 grid in the level palette plus the guide's matrix green, the axis labels exactly
 * "Likelihood" (vertical, arrow up) and "Impact" (horizontal, arrow right), and a tick on the warning's own cell.
 *
 * <p>No Met Office artwork is copied; the grid is {@code MATRIX_GRID}. It carries no words such as "medium" or
 * "minor". It never recolours a warning: the served level is printed by the chip, the grid only locates the cell.
 * With a missing or out-of-range value nothing is drawn. The accessible name is the text alternative
 * "Likelihood 3 of 4, impact 2 of 4".
 *
 * @param {object} props
 * @param {?number} props.likelihood the served likelihood, 1 to 4
 * @param {?number} props.impact the served impact, 1 to 4
 * @param {string} [props.className] extra classes
 */
export default function WarningMatrix({ likelihood = null, impact = null, className = '' }) {
  const text = matrixText(likelihood, impact);
  if (text == null) return null;
  const l = matrixValue(likelihood);
  const i = matrixValue(impact);
  const yBase = TOP + GRID;
  return (
    <svg
      data-testid="warning-matrix"
      role="img"
      aria-label={text}
      viewBox="0 0 122 118"
      className={`h-[118px] w-[122px] shrink-0 text-plex-text-secondary ${className}`}
    >
      {MATRIX_GRID.map((row, r) => row.map((colour, c) => {
        const ticked = 4 - r === l && c + 1 === i;
        const x = LEFT + c * STEP;
        const y = TOP + r * STEP;
        return (
          <g key={`${r}:${c}`}>
            <rect
              data-testid="warning-matrix-cell"
              data-likelihood={4 - r}
              data-impact={c + 1}
              data-colour={colour}
              data-ticked={ticked ? 'true' : undefined}
              x={x}
              y={y}
              width={CELL}
              height={CELL}
              fill={MATRIX_COLOURS[colour]}
              stroke={ticked ? '#000000' : 'none'}
              strokeWidth={ticked ? 2 : 0}
            />
            {ticked && (
              <path
                data-testid="warning-matrix-tick"
                d={`M${x + 5} ${y + 11.5} L${x + 9.5} ${y + 16} L${x + 17} ${y + 6}`}
                fill="none"
                stroke="#000000"
                strokeWidth="2.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            )}
          </g>
        );
      }))}
      {/* Vertical axis: the label and an arrow up. */}
      <text
        x="9"
        y={TOP + GRID / 2 + 18}
        transform={`rotate(-90 9 ${TOP + GRID / 2 + 18})`}
        textAnchor="middle"
        fontSize="10"
        fill="currentColor"
      >
        Likelihood
      </text>
      <path
        d={`M16 ${TOP + 22} L16 ${TOP + 4} M12.5 ${TOP + 8} L16 ${TOP + 3.5} L19.5 ${TOP + 8}`}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
      />
      {/* Horizontal axis: the label and an arrow right. */}
      <text x={LEFT + 4} y={yBase + 14} fontSize="10" fill="currentColor">Impact</text>
      <path
        d={`M${LEFT + 44} ${yBase + 10} L${LEFT + 66} ${yBase + 10} `
          + `M${LEFT + 62} ${yBase + 6.5} L${LEFT + 67} ${yBase + 10} L${LEFT + 62} ${yBase + 13.5}`}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
      />
    </svg>
  );
}

WarningMatrix.propTypes = {
  likelihood: PropTypes.number,
  impact: PropTypes.number,
  className: PropTypes.string,
};
