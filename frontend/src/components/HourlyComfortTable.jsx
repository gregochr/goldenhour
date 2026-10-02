import PropTypes from 'prop-types';
import { formatEventTimeUk } from '../utils/conversions.js';
import { formatWind } from '../utils/hourlyComfort.js';
import { ThermometerIcon, WindIcon, RainIcon } from './WeatherIcons.jsx';

/** The column headers, in column order. */
const HEADERS = ['Time', 'Temperature', 'Wind', 'Rain chance'];

/** What the header says where the column is narrow (phone): the visible word is shorter, the
 * accessible name stays the full {@link HEADERS} entry. */
const SHORT_HEADERS = { Temperature: 'Temp', 'Rain chance': 'Rain' };

/**
 * The hourly comfort table — one row per daylight hour: time, temperature with feels-like, wind
 * with its compass point, and rain chance.
 *
 * <p>One table, two hosts: the Plan-tab overlay's marker popup (`MarkerPopupContent`'s
 * pure-wildlife branch, the frozen surface it first lived in) and the Map tab's four-day location
 * sheet (`LocationFourDaySheet`). It is a real `<table>` with column headers and a row header per
 * hour, so a screen reader can ask "wind at 15:00" rather than walking a run of loose cells. The
 * popup, whose own heading already says what the table is, keeps the headers visually hidden
 * (`headersVisible` false) so its look is what it always was; the sheet shows them.
 *
 * <p>A missing figure is an em dash with a hidden "not forecast" beside it — never a blank, and
 * never the word "undefined" that a missing wind direction used to print.
 *
 * @param {object} props
 * @param {Array<object>} props.rows hourly forecast rows (`solarEventTime`, `temperatureCelsius`,
 *        `apparentTemperatureCelsius`, `windSpeed`, `windDirection`,
 *        `precipitationProbabilityPercent`), in time order
 * @param {string} props.label the table's accessible name
 * @param {boolean} [props.headersVisible=false] draw the column headers
 * @param {string} [props.testId] the table's `data-testid`
 * @returns {React.ReactElement}
 */
export default function HourlyComfortTable({
  rows, label, headersVisible = false, testId = 'hourly-comfort-table',
}) {
  return (
    <table className="wf-hourly" aria-label={label} data-testid={testId}>
      {/* ⚠️ The headers are hidden by their TEXT (`sr-only` on a span inside each `th`), never by
          styling the `thead` itself: `position: absolute` on a table section blockifies it, and
          some engines then drop the table's row/column semantics, which is the one thing the
          hidden headers exist to keep. The emptied cells collapse to nothing in
          `.wf-hourly-head-off`. */}
      <thead className={headersVisible ? 'wf-hourly-head' : 'wf-hourly-head wf-hourly-head-off'}>
        <tr>
          {HEADERS.map((header) => (
            <th key={header} scope="col">
              {headersVisible && SHORT_HEADERS[header] ? (
                <>
                  <span className="wf-hourly-h-long" aria-hidden="true">{header}</span>
                  <span className="wf-hourly-h-short" aria-hidden="true">{SHORT_HEADERS[header]}</span>
                  <span className="sr-only">{header}</span>
                </>
              ) : (
                <span className={headersVisible ? undefined : 'sr-only'}>{header}</span>
              )}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((h) => {
          const wind = formatWind(h.windSpeed, h.windDirection);
          return (
            <tr key={h.solarEventTime} data-testid="hourly-comfort-row">
              <th scope="row" className="wf-hourly-time">{formatEventTimeUk(h.solarEventTime)}</th>
              <td>
                <span className="wf-hourly-cell">
                  <ThermometerIcon />
                  {temperatureText(h) ?? <Missing />}
                </span>
              </td>
              <td>
                <span className="wf-hourly-cell">
                  <WindIcon />
                  {wind ?? <Missing />}
                </span>
              </td>
              <td>
                <span className="wf-hourly-cell">
                  <RainIcon />
                  {h.precipitationProbabilityPercent != null
                    ? `${h.precipitationProbabilityPercent}%`
                    : <Missing />}
                </span>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

/**
 * One hour's temperature cell: "6°C · feels 4°C". "feels" is printed ONLY when a feels-like was
 * served — an earlier form fell back to the air temperature and printed it as "feels", inventing a
 * figure the callout's summary (which omits it) then disagreed with. Both round with
 * `Math.round`, as the summary does, so the two cannot diverge on a `.5`.
 *
 * @param {object} h an hourly row
 * @returns {?string} the text, or null when neither figure was served
 */
function temperatureText(h) {
  const air = h.temperatureCelsius != null ? `${Math.round(h.temperatureCelsius)}°C` : null;
  const feels = h.apparentTemperatureCelsius != null
    ? `feels ${Math.round(h.apparentTemperatureCelsius)}°C` : null;
  if (air && feels) return `${air} · ${feels}`;
  return air ?? feels;
}

/** An absent figure: a dash for the eye, a phrase for the ear. */
function Missing() {
  return (
    <>
      <span aria-hidden="true">—</span>
      <span className="sr-only">not forecast</span>
    </>
  );
}

HourlyComfortTable.propTypes = {
  rows: PropTypes.arrayOf(PropTypes.shape({
    solarEventTime: PropTypes.string,
    temperatureCelsius: PropTypes.number,
    apparentTemperatureCelsius: PropTypes.number,
    windSpeed: PropTypes.number,
    windDirection: PropTypes.number,
    precipitationProbabilityPercent: PropTypes.number,
  })).isRequired,
  label: PropTypes.string.isRequired,
  headersVisible: PropTypes.bool,
  testId: PropTypes.string,
};
