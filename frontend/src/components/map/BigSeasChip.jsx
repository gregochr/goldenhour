import React from 'react';
import PropTypes from 'prop-types';
import BoltIcon from './BoltIcon.jsx';
import { bigSeasChipAriaLabel, bigSeasChipTipLines } from '../../utils/bigSeasChips.js';

/**
 * One big-seas chip (big-seas plan F2; handoff "Big-seas chip"): the bolt, the structure's short
 * name and the wave height behind a left rule, on a teal-ringed plate. Drawn by {@code MapLabels}.
 *
 * <p>It is an ordinary chip to everything around it: {@code data-testid="map-label-chip"} (so
 * {@code MapView}'s {@code closeCallout} finds the selected one), {@code data-selected}, and
 * {@code data-ask="fade"} while an answer's picks are on the map. A press selects the place by its
 * location name, exactly as a chip does. The {@code aria-label} REPLACES the content, so it carries
 * everything the chip shows; the bolt is decorative.
 *
 * @param {object} props
 * @param {object} props.item a chip from {@code bigSeasChipItems}
 * @param {object} props.style the placer's {@code left/top} (or the off-screen measuring box)
 * @param {boolean} props.selected whether this place is the selection
 * @param {boolean} props.fade whether Ask's picks are on the map
 * @param {Function} props.refCallback {@code (node) => void}, for the measuring pass
 * @param {?Function} [props.onSelect] called with the location name on press
 * @param {?Function} [props.onTipEnter] pointer-enter handler
 * @param {?Function} [props.onTipMove] pointer-move handler
 * @param {?Function} [props.onTipLeave] pointer-leave handler
 */
export default function BigSeasChip({
  item, style, selected, fade, refCallback, onSelect = null, onTipEnter = null, onTipMove = null,
  onTipLeave = null,
}) {
  return (
    <button
      type="button"
      ref={refCallback}
      className="wf-maplab-chip wf-bsc"
      data-testid="map-label-chip"
      data-kind="big-seas"
      data-aligned={item.ringed ? 'true' : undefined}
      data-selected={selected ? 'true' : undefined}
      data-ask={fade ? 'fade' : undefined}
      style={style}
      aria-label={bigSeasChipAriaLabel(item)}
      onClick={(pressEvent) => {
        // Focuses itself first (macOS and iOS Safari do not focus a tapped button), as a pick chip
        // does: the selected chip stays mounted, so focus stays on it and Escape still reaches the pane.
        pressEvent.currentTarget.focus({ preventScroll: true });
        onTipLeave?.();
        onSelect?.(item.name);
      }}
      onPointerEnter={onTipEnter}
      onPointerMove={onTipMove}
      onPointerLeave={onTipLeave}
    >
      <BoltIcon className="wf-bsc-bolt" />
      <b className="wf-bsc-n">{item.label}</b>
      <em className="wf-bsc-h">{`${item.heightM.toFixed(1)} m`}</em>
    </button>
  );
}

BigSeasChip.propTypes = {
  item: PropTypes.shape({
    name: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    heightM: PropTypes.number.isRequired,
    ringed: PropTypes.bool,
  }).isRequired,
  style: PropTypes.shape({}).isRequired,
  selected: PropTypes.bool,
  fade: PropTypes.bool,
  refCallback: PropTypes.func.isRequired,
  onSelect: PropTypes.func,
  onTipEnter: PropTypes.func,
  onTipMove: PropTypes.func,
  onTipLeave: PropTypes.func,
};

/**
 * The big-seas hover card's lines, for the layer's existing {@code .wf-maplab-tip}: the name first,
 * then the rest in the secondary ink.
 *
 * @param {object} props
 * @param {object} props.item a chip from {@code bigSeasChipItems}
 */
export function BigSeasTipLines({ item }) {
  const [name, ...rest] = bigSeasChipTipLines(item);
  return (
    <>
      <div className="wf-maplab-tip-n">{name}</div>
      {rest.map((line) => (
        <div key={line} className="wf-maplab-tip-s" data-testid="map-label-tip-seas">{line}</div>
      ))}
    </>
  );
}

BigSeasTipLines.propTypes = {
  item: PropTypes.shape({}).isRequired,
};
