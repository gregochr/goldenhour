import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import DataGrid from '../components/data/DataGrid.jsx';

const ROWS = [
  { id: 1, name: 'Alnmouth', status: 'On', note: '3 locations' },
  { id: 2, name: 'Bamburgh', status: 'Off', note: '1 location' },
];

const COLUMNS = [
  { key: 'name', header: 'Name', width: '2fr', card: 'title', sortKey: 'name', cell: (r) => <span>{r.name}</span> },
  { key: 'status', header: 'Status', width: '1fr', sortKey: 'status', cell: (r) => <span>{r.status}</span> },
  { key: 'note', header: 'Note', width: '.5fr', secondary: true, cell: (r) => <span>{r.note}</span> },
  { key: 'card-status', card: 'status', cardOnly: true, cell: (r) => <span>{r.status}</span> },
  { key: 'card-summary', card: 'summary', cardOnly: true, cell: (r) => <span>{r.note}</span> },
];

function renderGrid(props = {}) {
  const onSelect = vi.fn();
  render(
    <DataGrid
      columns={COLUMNS}
      rows={ROWS}
      getRowId={(r) => r.id}
      getRowLabel={(r) => `${r.name}, ${r.status}`}
      onSelect={onSelect}
      testIdPrefix="grid"
      {...props}
    />,
  );
  return { onSelect };
}

describe('DataGrid', () => {
  it('opens a row on click', () => {
    const { onSelect } = renderGrid();
    fireEvent.click(screen.getByTestId('grid-row-1'));
    expect(onSelect).toHaveBeenCalledWith(ROWS[0]);
  });

  it('opens a row on Enter and on Space, and Space does not scroll the page', () => {
    const { onSelect } = renderGrid();
    const row = screen.getByTestId('grid-row-2');
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(onSelect).toHaveBeenLastCalledWith(ROWS[1]);
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    row.dispatchEvent(space);
    expect(onSelect).toHaveBeenCalledTimes(2);
    expect(space.defaultPrevented).toBe(true);
  });

  it('ignores other keys', () => {
    const { onSelect } = renderGrid();
    fireEvent.keyDown(screen.getByTestId('grid-row-1'), { key: 'a' });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('draws each row as a focusable button named by its label, with no table roles', () => {
    renderGrid();
    const row = screen.getByRole('button', { name: 'Alnmouth, On' });
    expect(row).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('row')).not.toBeInTheDocument();
  });

  it('holds no interactive element inside a row', () => {
    renderGrid();
    for (const row of screen.getAllByRole('button', { name: /,/ })) {
      expect(row.querySelector('button, a, input, select, textarea, [tabindex]')).toBeNull();
    }
  });

  it('marks the open row with aria-current and the inset selected bar', () => {
    renderGrid({ selectedId: 2 });
    expect(screen.getByTestId('grid-row-2')).toHaveAttribute('aria-current', 'true');
    expect(screen.getByTestId('grid-row-2').className).toContain('shadow-[inset_2px_0_0');
    expect(screen.getByTestId('grid-row-1')).not.toHaveAttribute('aria-current');
  });

  it('draws an off row in the quiet ink', () => {
    renderGrid({ isOff: (r) => r.status === 'Off' });
    expect(screen.getByTestId('grid-row-2').className).toContain('text-plex-text-muted');
    expect(screen.getByTestId('grid-row-1').className).not.toContain('text-plex-text-muted');
  });

  describe('container-query layout (jsdom has no container queries; the classes are the contract)', () => {
    it('is an @container whose secondary column drops at 820px and whose rows become cards at 540px', () => {
      renderGrid();
      expect(screen.getByTestId('grid').className).toContain('@container');
      const note = screen.getByTestId('grid-cell-note-1');
      expect(note.className).toContain('@max-[820px]:hidden');
      expect(note.className).toContain('@max-[540px]:hidden');
      expect(screen.getByTestId('grid-row-1').className).toContain('@max-[540px]:min-h-[60px]');
    });

    it('hides the header in card mode', () => {
      renderGrid();
      expect(screen.getByTestId('grid-header').className).toContain('@max-[540px]:hidden');
    });

    it('shows a card-only cell only in card mode, with the summary spanning both columns', () => {
      renderGrid();
      const summary = screen.getByTestId('grid-cell-card-summary-1');
      expect(summary.className).toContain('hidden');
      expect(summary.className).toContain('@max-[540px]:block');
      expect(summary.className).toContain('@max-[540px]:col-span-2');
      expect(screen.getByTestId('grid-cell-card-status-1').className).toContain('@max-[540px]:justify-self-end');
    });

    it('lets a normal column fill a card line: shown in both modes, right of line 1 or across line 2', () => {
      renderGrid({
        columns: [
          COLUMNS[0],
          { key: 'role', header: 'Role', width: '1fr', card: 'status', cell: (r) => <span>{r.status}</span> },
          { key: 'subs', header: 'Subs', width: '1fr', card: 'summary', cell: (r) => <span>{r.note}</span> },
          { key: 'extra', header: 'Extra', width: '1fr', secondary: true, cell: (r) => <span>{r.note}</span> },
        ],
      });
      const role = screen.getByTestId('grid-cell-role-1').className;
      expect(role).toContain('@max-[540px]:order-2');
      expect(role).toContain('@max-[540px]:justify-self-end');
      expect(role).not.toContain('@max-[540px]:hidden');
      const subs = screen.getByTestId('grid-cell-subs-1').className;
      expect(subs).toContain('@max-[540px]:order-3');
      expect(subs).toContain('@max-[540px]:col-span-2');
      expect(subs).not.toContain('@max-[540px]:hidden');
      expect(screen.getByTestId('grid-cell-extra-1').className).toContain('@max-[540px]:hidden');
      // They are real columns too: in the wide template.
      expect(screen.getByTestId('grid-row-1').getAttribute('style')).toContain('--data-cols: minmax(0,2fr) minmax(0,1fr) minmax(0,1fr) minmax(0,1fr)');
    });

    it('keeps the title in the card and gives the template without secondary or card-only columns for narrow lists', () => {
      renderGrid();
      expect(screen.getByTestId('grid-cell-name-1').className).toContain('@max-[540px]:order-1');
      const style = screen.getByTestId('grid-row-1').getAttribute('style');
      expect(style).toContain('--data-cols: minmax(0,2fr) minmax(0,1fr) minmax(0,.5fr)');
      expect(style).toContain('--data-cols-lean: minmax(0,2fr) minmax(0,1fr)');
    });
  });

  describe('header', () => {
    it('does not draw card-only columns', () => {
      renderGrid();
      const header = screen.getByTestId('grid-header');
      expect(within(header).getByText('Name')).toBeInTheDocument();
      expect(within(header).queryByText('3 locations')).not.toBeInTheDocument();
    });

    it('draws a sortable header as a button, the sorted one with an arrow and its state in its name', () => {
      const onSort = vi.fn();
      renderGrid({ sort: { key: 'name', dir: 'asc' }, onSort });
      const button = screen.getByTestId('grid-sort-name');
      expect(button).toHaveTextContent('Name ▲');
      expect(screen.getByRole('button', { name: 'Name, sorted ascending' })).toBe(button);
      expect(button.parentElement).not.toHaveAttribute('aria-sort');
      expect(screen.getByRole('button', { name: 'Status' })).toBe(screen.getByTestId('grid-sort-status'));
      fireEvent.click(screen.getByTestId('grid-sort-status'));
      expect(onSort).toHaveBeenCalledWith('status');
    });

    it('shows ▼ and "sorted descending" when the sort runs the other way', () => {
      renderGrid({ sort: { key: 'status', dir: 'desc' }, onSort: vi.fn() });
      expect(screen.getByTestId('grid-sort-status')).toHaveTextContent('Status ▼');
      expect(screen.getByRole('button', { name: 'Status, sorted descending' })).toBeInTheDocument();
    });

    it('draws plain labels when no sort handler is given', () => {
      renderGrid();
      expect(screen.queryByTestId('grid-sort-name')).not.toBeInTheDocument();
    });
  });
});
