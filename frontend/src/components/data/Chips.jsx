import PropTypes from 'prop-types';

/** The chip body. 36px tall and never shrinking on a phone, where the row scrolls sideways. */
const CHIP_BASE = 'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 font-mono text-[11px] transition-colors cursor-pointer focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:h-9 max-[639px]:shrink-0';
const CHIP_ON = 'border-plex-gold bg-plex-gold/10 text-plex-text';
const CHIP_OFF = 'border-plex-border text-plex-text-secondary hover:border-plex-border-light hover:text-plex-text';

/**
 * The chip row: chips and their groups on one line, wrapping on a tablet or desktop and scrolling sideways on a
 * phone (639px and under).
 *
 * @param {object} props
 * @param {import('react').ReactNode} props.children {@link ChipGroup}s and {@link ChipDivider}s
 * @param {string} [props.testId]
 */
export function ChipRow({ children, testId = undefined }) {
  return (
    <div
      className="flex flex-wrap items-center gap-2 max-[639px]:flex-nowrap max-[639px]:overflow-x-auto max-[639px]:pb-1"
      data-testid={testId}
    >
      {children}
    </div>
  );
}

ChipRow.propTypes = { children: PropTypes.node.isRequired, testId: PropTypes.string };

/**
 * One group of chips. Single-select within a group (the caller owns the value); groups AND together.
 *
 * @param {object} props
 * @param {string} props.label the group's accessible name
 * @param {import('react').ReactNode} props.children the {@link Chip}s
 */
export function ChipGroup({ label, children }) {
  return (
    <div className="flex items-center gap-1.5 max-[639px]:shrink-0" role="group" aria-label={label}>
      {children}
    </div>
  );
}

ChipGroup.propTypes = { label: PropTypes.string.isRequired, children: PropTypes.node.isRequired };

/** The 1px divider between chip groups. */
export function ChipDivider() {
  return <span className="h-5 w-px shrink-0 bg-plex-border" aria-hidden="true" />;
}

/**
 * A filter chip with an optional count. A toggle ({@code aria-pressed}).
 *
 * @param {object} props
 * @param {string} props.label the chip's text
 * @param {number} [props.count] the number of items it would show
 * @param {boolean} props.on whether it is selected
 * @param {() => void} props.onClick called on press
 * @param {string} props.testId the chip's test id; the count's is {@code {testId}-count}
 */
export function Chip({ label, count = undefined, on, onClick, testId }) {
  return (
    <button
      type="button"
      className={`${CHIP_BASE} ${on ? CHIP_ON : CHIP_OFF}`}
      aria-pressed={on}
      onClick={onClick}
      data-testid={testId}
    >
      {label}
      {count != null && <span className="text-plex-text-muted" data-testid={`${testId}-count`}>{count}</span>}
    </button>
  );
}

Chip.propTypes = {
  label: PropTypes.string.isRequired,
  count: PropTypes.number,
  on: PropTypes.bool.isRequired,
  onClick: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};

/**
 * A native select drawn as a chip ("All regions ▾"). The select does the work, so it keeps its own keyboard and
 * picker; the chevron is decoration.
 *
 * @param {object} props
 * @param {string} props.label the select's accessible name
 * @param {string} props.value the selected option's value
 * @param {Array<{value: string, label: string}>} props.options the options, the "all" one first
 * @param {(value: string) => void} props.onChange called with the chosen value
 * @param {boolean} [props.on] whether a non-default option is chosen (the chip lights up)
 * @param {string} props.testId the select's test id
 */
export function ChipSelect({ label, value, options, onChange, on = false, testId }) {
  return (
    <span className="relative inline-flex max-[639px]:shrink-0">
      <select
        aria-label={label}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`${CHIP_BASE} appearance-none pr-6 ${on ? CHIP_ON : CHIP_OFF} bg-transparent`}
        data-testid={testId}
      >
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <span className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-[9px] text-plex-text-muted" aria-hidden="true">
        &#9662;
      </span>
    </span>
  );
}

ChipSelect.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.string.isRequired,
  options: PropTypes.arrayOf(PropTypes.shape({ value: PropTypes.string, label: PropTypes.string })).isRequired,
  onChange: PropTypes.func.isRequired,
  on: PropTypes.bool,
  testId: PropTypes.string.isRequired,
};

/**
 * The sort chip: where the column headers are gone (the grid in card mode), a select of "{column} ▲/▼" choices.
 * Hidden until the nearest {@code @container} is 540px wide or less, so it must sit inside a
 * {@code @container} element that is the list's width (the view's list card); the grid's own header covers wider.
 *
 * @param {object} props
 * @param {Array<{key: string, label: string}>} props.options the sortable columns
 * @param {string} props.sortKey the current sort column
 * @param {'asc'|'desc'} props.sortDir the current direction
 * @param {(key: string, dir: 'asc'|'desc') => void} props.onChange called with the chosen column and direction
 * @param {string} props.testId the select's test id
 */
export function SortChip({ options, sortKey, sortDir, onChange, testId }) {
  return (
    <span className="relative hidden @max-[540px]:inline-flex max-[639px]:shrink-0">
      <select
        aria-label="Sort by"
        value={`${sortKey}:${sortDir}`}
        onChange={(e) => {
          const [key, dir] = e.target.value.split(':');
          onChange(key, dir);
        }}
        className={`${CHIP_BASE} ${CHIP_OFF} appearance-none bg-transparent pr-6`}
        data-testid={testId}
      >
        {options.flatMap((o) => [
          <option key={`${o.key}:asc`} value={`${o.key}:asc`}>{`${o.label} ▲`}</option>,
          <option key={`${o.key}:desc`} value={`${o.key}:desc`}>{`${o.label} ▼`}</option>,
        ])}
      </select>
      <span className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-[9px] text-plex-text-muted" aria-hidden="true">
        &#9662;
      </span>
    </span>
  );
}

SortChip.propTypes = {
  options: PropTypes.arrayOf(PropTypes.shape({ key: PropTypes.string, label: PropTypes.string })).isRequired,
  sortKey: PropTypes.string.isRequired,
  sortDir: PropTypes.oneOf(['asc', 'desc']).isRequired,
  onChange: PropTypes.func.isRequired,
  testId: PropTypes.string.isRequired,
};
