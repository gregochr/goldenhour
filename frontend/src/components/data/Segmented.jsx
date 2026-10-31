import PropTypes from 'prop-types';

/**
 * A single-select button group ("Today · 7 days · 30 days"): joined buttons in one bordered pill, the chosen one
 * lit. Each button carries {@code aria-pressed}; the group is a labelled {@code role="group"}. The caller owns the
 * value. Phone buttons are 44px tall.
 *
 * @param {object} props
 * @param {string} props.label the group's accessible name
 * @param {Array<{value: string, label: string}>} props.options the buttons, in order
 * @param {string} props.value the chosen option's value
 * @param {(value: string) => void} props.onChange called with the pressed option's value
 * @param {string} props.testId the group's test id; each button is {@code {testId}-{value lower case}}
 */
export default function Segmented({ label, options, value, onChange, testId }) {
  return (
    <div
      className="inline-flex shrink-0 overflow-hidden rounded-lg border border-plex-border"
      role="group"
      aria-label={label}
      data-testid={testId}
    >
      {options.map((option, index) => {
        const on = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(option.value)}
            className={`cursor-pointer px-3 py-1.5 font-mono text-[11px] transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 ${
              index > 0 ? 'border-l border-plex-border' : ''
            } ${on ? 'bg-plex-gold/10 text-plex-text' : 'text-plex-text-secondary hover:text-plex-text'}`}
            data-testid={`${testId}-${option.value.toLowerCase()}`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

Segmented.propTypes = {
  label: PropTypes.string.isRequired,
  options: PropTypes.arrayOf(PropTypes.shape({
    value: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
  })).isRequired,
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};
