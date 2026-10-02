import PropTypes from 'prop-types';
import { formatEventTimeUk } from '../utils/conversions.js';
import { formatWind } from '../utils/hourlyComfort.js';
import { ThermometerIcon, WindIcon, RainIcon } from './WeatherIcons.jsx';

/** The column headers, in column order. */
const HEADERS = ['Time', 'Temperature', 'Wind', 'Rain chance'];

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
              <span className={headersVisible ? undefined : 'sr-only'}>{header}</span>
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
                  {h.temperatureCelsius != null
                    ? `${Math.round(h.temperatureCelsius)}°C · feels ${Math.round(h.apparentTemperatureCelsius ?? h.temperatureCelsius)}°C`
                    : <Missing />}
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
