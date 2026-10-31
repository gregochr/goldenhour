import PropTypes from 'prop-types';
import { inputClass } from '../users/userFormClasses.js';

/**
 * The tools row under a Data tab's header: the search box, then the chip row. On a phone the search sits above the
 * chips and spans the width; from 640px it is 280px and the chips follow it on the same line.
 *
 * @param {object} props
 * @param {import('react').ReactNode} props.children the {@link DataSearch} and {@link ChipRow}
 * @param {string} [props.testId]
 */
export function DataTools({ children, testId = undefined }) {
  return (
    <div
      className="flex flex-wrap items-center gap-2 max-[639px]:flex-col max-[639px]:items-stretch"
      data-testid={testId}
    >
      {children}
    </div>
  );
}

DataTools.propTypes = { children: PropTypes.node.isRequired, testId: PropTypes.string };

/**
 * The search box: 280px (full width on a phone) with a clear button once it holds text.
 *
 * @param {object} props
 * @param {string} props.value the search text
 * @param {(value: string) => void} props.onChange called with the new text
 * @param {string} props.placeholder also the accessible name ("Search email")
 * @param {string} props.testId the input's test id; the clear button's is {@code {testId}-clear}
 */
export function DataSearch({ value, onChange, placeholder, testId }) {
  return (
    <div className="relative w-[280px] max-w-full max-[639px]:w-full">
      <input
        type="search"
        aria-label={placeholder}
        placeholder={placeholder}
        className={`${inputClass} pr-8 max-[639px]:min-h-11 [&::-webkit-search-cancel-button]:appearance-none`}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        data-testid={testId}
      />
      {value !== '' && (
        <button
          type="button"
          className="absolute inset-y-0 right-0 flex w-8 items-center justify-center rounded text-base leading-none text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold"
          aria-label="Clear search"
          onClick={() => onChange('')}
          data-testid={`${testId}-clear`}
        >
          <span aria-hidden="true">&times;</span>
        </button>
      )}
    </div>
  );
}

DataSearch.propTypes = {
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  placeholder: PropTypes.string.isRequired,
  testId: PropTypes.string.isRequired,
};
