import PropTypes from 'prop-types';

/** The header label: mono 10px uppercase, ink-3 (ink-2 when it is the sorted column). */
const HEADER_LABEL = 'font-mono text-[10px] uppercase tracking-[0.12em]';

/** The shared column-position classes of a cell and its header cell. */
function columnClass(column) {
  if (column.cardOnly) {
    const place = column.card === 'status'
      ? '@max-[540px]:order-2 @max-[540px]:justify-self-end'
      : '@max-[540px]:order-3 @max-[540px]:col-span-2';
    return `hidden @max-[540px]:block ${place}`;
  }
  if (column.card === 'title') return `@max-[540px]:order-1 ${column.secondary ? '@max-[820px]:hidden' : ''}`;
  // A normal column may also fill a card line (Users: Role rides line 1, Sub-regions is line 2). It stays shown in
  // card mode, so it must not be secondary: a secondary column drops at 820px, which is before cards begin.
  if (column.card === 'status') return '@max-[540px]:order-2 @max-[540px]:justify-self-end';
  if (column.card === 'summary') return '@max-[540px]:order-3 @max-[540px]:col-span-2';
  return `@max-[540px]:hidden ${column.secondary ? '@max-[820px]:hidden' : ''}`;
}

/** The grid-template-columns of the columns that show: all of them, or only the non-secondary ones. */
function template(columns, lean) {
  return columns
    .filter((c) => !c.cardOnly && !(lean && c.secondary))
    .map((c) => `minmax(0,${c.width ?? '1fr'})`)
    .join(' ');
}

/**
 * The Data tabs' list: a CSS grid (not a {@code <table>}, so a row can collapse into a card) inside a Tailwind
 * {@code @container}, so it reflows whether the panel or the viewport narrowed it:
 * <ul>
 *   <li>wider than 820px: every column;
 *   <li>820px and under: columns marked {@code secondary} drop;
 *   <li>540px and under: each row becomes a two-line card (line 1 the {@code card: 'title'} column and the
 *       {@code card: 'status'} cell on the right, line 2 the {@code card: 'summary'} cell) and the header hides.
 * </ul>
 * Cards take their cells two ways. A column with {@code cardOnly: true} is shown only in card mode, so a value that
 * is a secondary column on a wide list (Waiting) can still ride the card's right edge. A normal column with
 * {@code card: 'status'} or {@code 'summary'} is shown in both modes, in its own place in the wide list and on its
 * card line (it must not be {@code secondary}, because secondary columns are gone by the time cards begin).
 *
 * <p>Rows are {@code role="button"} with {@code tabIndex=0}; Enter and Space open them; each carries an
 * {@code aria-label} reading the row. ⚠️ A row holds <b>no</b> interactive element (no button, link, input or
 * select) because the whole row is the target, and nested controls inside a button are invalid and unreachable by
 * keyboard. A grid of button rows is not an ARIA table, so no table roles are used.
 *
 * @param {object} props
 * @param {Array<object>} props.columns the columns, in order:
 *   {@code {key, header, width ('2fr'), secondary, sortKey, card ('title'|'status'|'summary'), cardOnly, cell(row)}}
 * @param {Array<object>} props.rows the rows to show (already filtered, sorted and paged)
 * @param {(row: object) => (string|number)} props.getRowId the row's id
 * @param {(row: object) => string} props.getRowLabel the row's accessible name, reading the whole row
 * @param {string|number|null} [props.selectedId] the row whose panel is open
 * @param {(row: object) => void} props.onSelect called when a row is activated
 * @param {(row: object) => boolean} [props.isSelectable] false for a row that opens nothing (Tides: statistics with no
 *   matching location). Such a row is plain: no {@code role="button"}, not focusable, no pointer cursor or hover tint,
 *   and it never calls {@code onSelect}. Defaults to every row opening.
 * @param {(row: object) => boolean} [props.isOff] true for a row drawn in the quiet ink (a switched-off item)
 * @param {{key: string, dir: 'asc'|'desc'}} [props.sort] the current sort, for the header's arrow and the sort button's accessible name
 * @param {(sortKey: string) => void} [props.onSort] called when a sortable header is pressed
 * @param {string} props.testIdPrefix the grid's test id; rows are {@code {prefix}-row-{id}}
 */
export default function DataGrid({
  columns, rows, getRowId, getRowLabel, selectedId = null, onSelect, isSelectable = undefined, isOff = undefined, sort = undefined,
  onSort = undefined, testIdPrefix,
}) {
  const gridVars = { '--data-cols': template(columns, false), '--data-cols-lean': template(columns, true) };
  const rowGrid = 'grid grid-cols-(--data-cols) items-center gap-x-3 @max-[820px]:grid-cols-(--data-cols-lean) @max-[540px]:grid-cols-[minmax(0,1fr)_auto]';

  function onRowKeyDown(event, row) {
    // A key from inside the row (there is nothing to focus there today) must not also open it.
    if (event.target !== event.currentTarget) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSelect(row);
    }
  }

  return (
    <div className="@container" data-testid={testIdPrefix}>
      <div
        className={`${rowGrid} border-b border-plex-border px-3 pb-2 @max-[540px]:hidden`}
        style={gridVars}
        data-testid={`${testIdPrefix}-header`}
      >
        {columns.filter((c) => !c.cardOnly).map((column) => {
          const sorted = sort != null && column.sortKey != null && sort.key === column.sortKey;
          const tone = sorted ? 'text-plex-text-secondary' : 'text-plex-text-muted';
          return (
            <div
              key={column.key}
              className={`min-w-0 ${columnClass(column)}`}
            >
              {column.sortKey != null && onSort ? (
                <button
                  type="button"
                  className={`${HEADER_LABEL} ${tone} cursor-pointer whitespace-nowrap hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold`}
                  aria-label={sorted ? `${column.header}, sorted ${sort.dir === 'asc' ? 'ascending' : 'descending'}` : undefined}
                  onClick={() => onSort(column.sortKey)}
                  data-testid={`${testIdPrefix}-sort-${column.sortKey}`}
                >
                  {column.header}
                  {sorted ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : ''}
                </button>
              ) : (
                <span className={`${HEADER_LABEL} ${tone}`}>{column.header}</span>
              )}
            </div>
          );
        })}
      </div>
      {rows.map((row) => {
        const id = getRowId(row);
        const selected = id === selectedId;
        const off = isOff ? isOff(row) : false;
        const openable = isSelectable ? isSelectable(row) : true;
        const tone = off ? 'text-plex-text-muted' : 'text-plex-text';
        const rowBase = `${rowGrid} border-b border-plex-border/60 px-3 py-2.5 text-[13px] last:border-b-0 @max-[540px]:min-h-[60px] @max-[540px]:gap-y-1 @max-[540px]:px-1 @max-[540px]:py-3`;
        const interaction = openable
          ? 'cursor-pointer transition-colors hover:bg-[rgba(242,231,211,0.03)] focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-plex-gold'
          : '';
        const selectedTint = selected && openable ? 'bg-[rgba(242,231,211,0.06)] shadow-[inset_2px_0_0_var(--color-plex-text)]' : '';
        return (
          <div
            key={id}
            {...(openable ? {
              role: 'button',
              tabIndex: 0,
              'aria-label': getRowLabel(row),
              'aria-current': selected ? 'true' : undefined,
              onClick: () => onSelect(row),
              onKeyDown: (e) => onRowKeyDown(e, row),
            } : {})}
            style={gridVars}
            className={`${rowBase} ${interaction} ${selectedTint} ${tone}`}
            data-testid={`${testIdPrefix}-row-${id}`}
          >
            {columns.map((column) => (
              <div key={column.key} className={`min-w-0 ${columnClass(column)}`} data-testid={`${testIdPrefix}-cell-${column.key}-${id}`}>
                {column.cell(row)}
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

DataGrid.propTypes = {
  columns: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    header: PropTypes.string,
    width: PropTypes.string,
    secondary: PropTypes.bool,
    sortKey: PropTypes.string,
    card: PropTypes.oneOf(['title', 'status', 'summary']),
    cardOnly: PropTypes.bool,
    cell: PropTypes.func.isRequired,
  })).isRequired,
  rows: PropTypes.arrayOf(PropTypes.object).isRequired,
  getRowId: PropTypes.func.isRequired,
  getRowLabel: PropTypes.func.isRequired,
  selectedId: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  onSelect: PropTypes.func.isRequired,
  isSelectable: PropTypes.func,
  isOff: PropTypes.func,
  sort: PropTypes.shape({ key: PropTypes.string, dir: PropTypes.oneOf(['asc', 'desc']) }),
  onSort: PropTypes.func,
  testIdPrefix: PropTypes.string.isRequired,
};
