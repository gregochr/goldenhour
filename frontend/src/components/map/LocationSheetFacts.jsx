// The badge list's explicit role="list" is deliberate: Safari/VoiceOver drops list semantics from a
// <ul> styled `list-style: none`, which is exactly how this one is drawn.
/* eslint-disable jsx-a11y/no-redundant-roles */
import React from 'react';
import PropTypes from 'prop-types';
import useForecastDetail from '../../hooks/useForecastDetail.js';
import {
  comfortRows, detailBadges, generatedFooter,
} from '../../utils/forecastDetailFacts.js';
import {
  ThermometerIcon, WindIcon, RainIcon, DropletIcon,
} from '../WeatherIcons.jsx';

const COMFORT_ICONS = {
  temperature: ThermometerIcon,
  wind: WindIcon,
  rain: RainIcon,
  precip: DropletIcon,
};

/**
 * The forecast detail one location-sheet window row carries below its prose: the conditions
 * badges (dust, cloud inversion, valley mist, bluebell, rising tide, sea state, storm surge), the wind and
 * comfort rows, and the "Forecast generated" footer — the facts the Plan-tab map overlay's popup
 * showed and the sheet did not (overlay convergence C2, `docs/engineering/overlay-convergence-plan.md`
 * §3 C2, owner decision D3).
 *
 * <p>Mounted by the sheet ONLY for an expanded row, so the lazy detail fetch
 * ({@code useForecastDetail}, memoised in `forecastApi`) happens on the reader opening a window,
 * never six times for a sheet that opens with one row showing. The badges wait on that fetch and
 * say so with a skeleton; the comfort rows and the footer read the slim row and appear at once. A
 * failed fetch costs the badges quietly — the rest stands. Nothing here is computed: every figure is
 * a served field (see `utils/forecastDetailFacts.js`), and the block renders NOTHING for a window
 * with none of them, so a location with no detail to show draws the sheet it always drew.
 *
 * <p>Never imports the since-deleted `MarkerPopupContent` or its inline styles — the sheet's own vocabulary
 * (`.wf-loc-*` in `index.css`), per map-tab-v2-plan.md §4 #11.
 *
 * @param {object} props
 * @param {?object} props.forecast the window's slim forecast row, or null (no row → no block)
 * @param {string} props.eventType 'SUNRISE' or 'SUNSET'
 * @param {boolean} props.isPro true for PRO and ADMIN readers
 * @param {boolean} [props.triageSuperseded] true when a live rating outranks this row's triage
 *   verdict, which suppresses the footer's "not evaluated by Claude" clause
 */
export default function LocationSheetFacts({
  forecast, eventType, isPro, triageSuperseded = false,
}) {
  const { detail, detailLoading } = useForecastDetail(forecast);
  if (!forecast) return null;

  const badges = detailBadges(detail, { eventType, isPro });
  const comfort = comfortRows(forecast);
  const footer = generatedFooter(forecast, detail, triageSuperseded, isPro);
  if (!detailLoading && badges.length === 0 && comfort.length === 0 && !footer) return null;

  return (
    <div data-testid="location-sheet-facts" className="wf-loc-facts">
      {detailLoading && (
        <div
          data-testid="location-sheet-facts-skeleton"
          className="wf-loc-facts-skeleton animate-pulse"
          aria-hidden="true"
        />
      )}
      {badges.length > 0 && (
        <ul className="wf-loc-fact-list" role="list" aria-label="Conditions">
          {badges.map((badge) => (
            <li
              key={badge.key}
              data-testid={`location-sheet-fact-${badge.key}`}
              className="wf-loc-fact font-mono"
              data-tone={badge.tone}
              data-locked={badge.locked ? 'true' : undefined}
            >
              <span aria-hidden="true">{`${badge.glyph} `}</span>
              {badge.text}
            </li>
          ))}
        </ul>
      )}
      {comfort.length > 0 && (
        <div data-testid="location-sheet-comfort" className="wf-loc-comfort font-mono">
          {comfort.map((line) => {
            const Icon = COMFORT_ICONS[line.icon];
            return (
              <div key={line.key} className="wf-loc-comfort-row">
                <Icon />
                <span>
                  <strong>{line.strong}</strong>
                  {line.rest}
                </span>
                {/* A spoken full stop between rows: they are blocks to the eye, but a screen reader
                    reading the block's text would otherwise run "…10°C" into "6.7 mph". */}
                <span className="sr-only">. </span>
              </div>
            );
          })}
        </div>
      )}
      {footer && (
        <p data-testid="location-sheet-generated" className="wf-loc-generated font-mono">
          {footer}
        </p>
      )}
    </div>
  );
}

LocationSheetFacts.propTypes = {
  forecast: PropTypes.object,
  eventType: PropTypes.string.isRequired,
  isPro: PropTypes.bool.isRequired,
  triageSuperseded: PropTypes.bool,
};
