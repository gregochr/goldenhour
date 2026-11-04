import PropTypes from 'prop-types';
import GuidePanel from './GuidePanel.jsx';
import { useGuide } from '../../context/GuideContext.jsx';

/**
 * The guide's docked column (presentation 1) for the shell to mount in the Ask dock's slot.
 *
 * <p>Drawn by the SHELL, not by {@code GuideProvider}, because it needs the shell's docked row; the
 * provider draws the other two presentations. It renders only while the guide is open AS the docked
 * column, so mounting it unconditionally is safe.
 *
 * @param {object} props
 * @param {'desktop'|'wide'} props.band the dock's width band, from {@code useAskSurface}
 * @param {boolean} props.sticky Plan and Coming up stick to the viewport; the Map's column is the frame's height
 * @param {boolean} props.inert a shell dialog, search or settings is open (never the page)
 */
export default function GuideDock({ band, sticky, inert }) {
  const guide = useGuide();
  if (!guide.open || guide.presentation !== 'docked') return null;
  return (
    <GuidePanel
      presentation="docked"
      page={guide.page}
      hash={guide.hash}
      nonce={guide.nonce}
      tracked={guide.tracked}
      onTrack={guide.trackGuide}
      onClose={() => guide.closeGuide()}
      band={band}
      sticky={sticky}
      inert={inert}
    />
  );
}

GuideDock.propTypes = {
  band: PropTypes.oneOf(['desktop', 'wide']).isRequired,
  sticky: PropTypes.bool.isRequired,
  inert: PropTypes.bool.isRequired,
};
