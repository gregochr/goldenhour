import PropTypes from 'prop-types';
import { modelLabel } from '../../utils/modelLabels.js';
import { swatchClass } from '../../utils/skyEval.js';

/** Ink classes for a pass-rate tone (whole literal strings, never built at runtime). */
export const TONE_TEXT = {
  none: 'text-plex-text-muted',
  ink: 'text-plex-text',
  amber: 'text-verdict-marginal',
  coral: 'text-plex-coral',
};

/** The card frame shared by every Sky Eval section. */
export const CARD_CLASS =
  'relative flex min-w-0 flex-col gap-3 rounded-xl border border-plex-border bg-plex-surface p-4';

/** A small coloured square for a model. */
export function Swatch({ model, large = false }) {
  const size = large ? 'h-2.5 w-2.5' : 'h-2 w-2';
  return <i aria-hidden="true" className={`inline-block flex-none rounded-[2px] ${size} ${swatchClass(model)}`} />;
}

Swatch.propTypes = { model: PropTypes.string.isRequired, large: PropTypes.bool };

/** A model's swatch and display label. */
export function ModelName({ model }) {
  return (
    <span className="inline-flex min-w-0 items-center gap-[7px]">
      <Swatch model={model} />
      <span className="min-w-0">{modelLabel(model)}</span>
    </span>
  );
}

ModelName.propTypes = { model: PropTypes.string.isRequired };

/** The small "PROD" tag on a model that serves a production lane. */
export function ProdTag() {
  return (
    <span
      className="whitespace-nowrap rounded border border-[rgba(201,162,75,0.5)] px-1 font-mono text-[9.5px] font-medium uppercase tracking-[0.1em] text-skyeval-version"
      data-testid="sky-eval-prod-tag"
    >
      prod
    </span>
  );
}

/** The gold commit tag: a short sha on a faint gold wash, its full detail in the tooltip. */
export function VersionTag({ shortSha, title = undefined }) {
  return (
    <span
      className="w-fit whitespace-nowrap rounded bg-[rgba(201,162,75,0.1)] px-[5px] font-mono text-[11.5px] text-skyeval-version"
      title={title}
      data-testid="sky-eval-version-tag"
    >
      {shortSha}
    </span>
  );
}

VersionTag.propTypes = { shortSha: PropTypes.string.isRequired, title: PropTypes.string };

/**
 * A horizontal bar drawn in SVG (so its width is an attribute, not an inline style).
 *
 * @param {object} props
 * @param {number} props.widthPct 0 to 100
 * @param {string} props.fillClass the fill class (a whole literal string)
 */
export function Bar({ widthPct, fillClass }) {
  return (
    <svg
      viewBox="0 0 100 6"
      preserveAspectRatio="none"
      className="block h-1.5 w-full overflow-hidden rounded-[3px]"
      aria-hidden="true"
    >
      <rect x="0" y="0" width="100" height="6" className="fill-plex-surface-light" />
      <rect x="0" y="0" width={widthPct} height="6" className={fillClass} />
    </svg>
  );
}

Bar.propTypes = { widthPct: PropTypes.number.isRequired, fillClass: PropTypes.string.isRequired };
