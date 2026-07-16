import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SortableHeader from '../../components/shared/SortableHeader.jsx';

/** Renders the header inside the table structure a th requires. */
function renderHeader(props) {
  return render(
    <table>
      <thead>
        <tr>
          <SortableHeader
            label="Name"
            sortKey="name"
            currentSortKey="name"
            currentSortDir="asc"
            onSort={() => {}}
            {...props}
          />
        </tr>
      </thead>
    </table>
  );
}

describe('SortableHeader', () => {
  it('shows the ascending arrow on the active sort column', () => {
    renderHeader({ currentSortKey: 'name', currentSortDir: 'asc' });
    expect(screen.getByRole('button')).toHaveTextContent('Name ▲');
  });

  it('shows the descending arrow when sorted desc', () => {
    renderHeader({ currentSortDir: 'desc' });
    expect(screen.getByRole('button')).toHaveTextContent('Name ▼');
  });

  it('shows no arrow on inactive columns', () => {
    renderHeader({ currentSortKey: 'other' });
    expect(screen.getByRole('button')).toHaveTextContent(/^Name$/);
  });

  it('calls onSort with the sort key when clicked', () => {
    const onSort = vi.fn();
    renderHeader({ onSort });
    fireEvent.click(screen.getByRole('button'));
    expect(onSort).toHaveBeenCalledWith('name');
  });

  it('renders the filter input and forwards changes when onFilter is provided', () => {
    const onFilter = vi.fn();
    renderHeader({ filterValue: '', onFilter });
    const input = screen.getByTestId('filter-name');
    fireEvent.change(input, { target: { value: 'bam' } });
    expect(onFilter).toHaveBeenCalledWith('bam');
  });

  describe('spacer contract (pixel parity for mixed and filter-less tables)', () => {
    // Filter-less column in a table that HAS filtered columns (e.g. Tide stats):
    // the spacer keeps its header height aligned with the filtered ones.
    it('renders the height spacer by default when onFilter is omitted', () => {
      const { container } = renderHeader({});
      expect(screen.queryByTestId('filter-name')).not.toBeInTheDocument();
      expect(container.querySelector('th div.h-\\[26px\\]')).not.toBeNull();
    });

    // Table with NO filter columns at all (e.g. Regions): spacer={false}
    // keeps the original compact header height.
    it('renders neither input nor spacer with spacer={false}', () => {
      const { container } = renderHeader({ spacer: false });
      expect(screen.queryByTestId('filter-name')).not.toBeInTheDocument();
      expect(container.querySelector('th div')).toBeNull();
    });

    it('never renders the spacer when a filter input is present', () => {
      const { container } = renderHeader({ filterValue: '', onFilter: () => {} });
      expect(screen.getByTestId('filter-name')).toBeInTheDocument();
      expect(container.querySelector('th div.h-\\[26px\\]')).toBeNull();
    });
  });
});
