import PropTypes from 'prop-types';

/**
 * A label and value list for a panel section: the label in ink-2, the value in ink (mono for a number or a date).
 * A {@code null} or empty value reads as an em dash, never as a blank.
 *
 * @param {object} props
 * @param {Array<{label: string, value: import('react').ReactNode, mono?: boolean, testId?: string}>} props.items
 *   the rows, in order
 * @param {string} [props.testId] the list's test id
 */
export default function KvList({ items, testId = undefined }) {
  return (
    <dl className="grid grid-cols-[minmax(88px,120px)_minmax(0,1fr)] gap-x-3 gap-y-2 text-[13px]" data-testid={testId}>
      {items.map((item) => {
        const empty = item.value == null || item.value === '';
        return (
          <div key={item.label} className="contents">
            <dt className="text-plex-text-secondary">{item.label}</dt>
            <dd
              className={`min-w-0 break-words text-plex-text ${item.mono ? 'font-mono text-[12.5px]' : ''}`}
              data-testid={item.testId}
            >
              {empty ? '—' : item.value}
            </dd>
          </div>
        );
      })}
    </dl>
  );
}

KvList.propTypes = {
  items: PropTypes.arrayOf(PropTypes.shape({
    label: PropTypes.string.isRequired,
    value: PropTypes.node,
    mono: PropTypes.bool,
    testId: PropTypes.string,
  })).isRequired,
  testId: PropTypes.string,
};
