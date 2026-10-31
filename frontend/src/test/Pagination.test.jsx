import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Pagination from '../components/Pagination.jsx';

const defaults = {
  page: 1,
  totalPages: 3,
  pageSize: 10,
  totalItems: 28,
  onNextPage: vi.fn(),
  onPrevPage: vi.fn(),
  onFirstPage: vi.fn(),
  onLastPage: vi.fn(),
  onSetPageSize: vi.fn(),
};

describe('Pagination', () => {
  it('renders showing summary with correct range', () => {
    render(<Pagination {...defaults} />);

    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–10 of 28');
  });

  it('renders correct summary for page 2', () => {
    render(<Pagination {...defaults} page={2} />);

    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('11–20 of 28');
  });

  it('renders correct summary for last page', () => {
    render(<Pagination {...defaults} page={3} />);

    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('21–28 of 28');
  });

  it('disables Prev and First on first page', () => {
    render(<Pagination {...defaults} page={1} />);

    expect(screen.getByTestId('pagination-prev')).toBeDisabled();
    expect(screen.getByTestId('pagination-first')).toBeDisabled();
    expect(screen.getByTestId('pagination-next')).not.toBeDisabled();
    expect(screen.getByTestId('pagination-last')).not.toBeDisabled();
  });

  it('disables Next and Last on last page', () => {
    render(<Pagination {...defaults} page={3} />);

    expect(screen.getByTestId('pagination-next')).toBeDisabled();
    expect(screen.getByTestId('pagination-last')).toBeDisabled();
    expect(screen.getByTestId('pagination-prev')).not.toBeDisabled();
    expect(screen.getByTestId('pagination-first')).not.toBeDisabled();
  });

  it('calls onNextPage when Next clicked', () => {
    const onNextPage = vi.fn();
    render(<Pagination {...defaults} page={1} onNextPage={onNextPage} />);

    fireEvent.click(screen.getByTestId('pagination-next'));

    expect(onNextPage).toHaveBeenCalledTimes(1);
  });

  it('calls onPrevPage when Prev clicked', () => {
    const onPrevPage = vi.fn();
    render(<Pagination {...defaults} page={2} onPrevPage={onPrevPage} />);

    fireEvent.click(screen.getByTestId('pagination-prev'));

    expect(onPrevPage).toHaveBeenCalledTimes(1);
  });

  it('calls onFirstPage when First clicked', () => {
    const onFirstPage = vi.fn();
    render(<Pagination {...defaults} page={2} onFirstPage={onFirstPage} />);

    fireEvent.click(screen.getByTestId('pagination-first'));

    expect(onFirstPage).toHaveBeenCalledTimes(1);
  });

  it('calls onLastPage when Last clicked', () => {
    const onLastPage = vi.fn();
    render(<Pagination {...defaults} page={1} onLastPage={onLastPage} />);

    fireEvent.click(screen.getByTestId('pagination-last'));

    expect(onLastPage).toHaveBeenCalledTimes(1);
  });

  it('renders page indicator', () => {
    render(<Pagination {...defaults} page={2} />);

    expect(screen.getByTestId('pagination-indicator')).toHaveTextContent('Page 2 of 3');
  });

  it('highlights active page size chip', () => {
    render(<Pagination {...defaults} pageSize={10} />);

    const chip10 = screen.getByTestId('pagination-size-10');
    const chip25 = screen.getByTestId('pagination-size-25');

    expect(chip10.className).toContain('text-plex-gold');
    expect(chip25.className).not.toContain('text-plex-gold');
  });

  it('calls onSetPageSize when a page size chip is clicked', () => {
    const onSetPageSize = vi.fn();
    render(<Pagination {...defaults} onSetPageSize={onSetPageSize} />);

    fireEvent.click(screen.getByTestId('pagination-size-25'));

    expect(onSetPageSize).toHaveBeenCalledWith(25);
  });

  it('words the label as a range with an en dash, in mono 11px', () => {
    render(<Pagination {...defaults} page={2} totalItems={266} totalPages={27} />);

    const label = screen.getByTestId('pagination-summary');
    expect(label).toHaveTextContent('11–20 of 266');
    expect(label.className).toContain('font-mono');
    expect(label.className).toContain('text-[11px]');
  });

  it('names the buttons Previous and Next', () => {
    render(<Pagination {...defaults} page={2} />);

    expect(screen.getByRole('button', { name: 'Previous' })).toBe(screen.getByTestId('pagination-prev'));
    expect(screen.getByRole('button', { name: 'Next' })).toBe(screen.getByTestId('pagination-next'));
  });

  it('gives every phone button 9x14 padding and a 44px target, by class, and leaves the desktop classes alone', () => {
    render(<Pagination {...defaults} page={2} />);

    for (const id of ['pagination-first', 'pagination-prev', 'pagination-next', 'pagination-last', 'pagination-size-10']) {
      const cls = screen.getByTestId(id).className;
      expect(cls).toContain('max-[639px]:px-[14px]');
      expect(cls).toContain('max-[639px]:py-[9px]');
      expect(cls).toContain('max-[639px]:min-h-11');
    }
    expect(screen.getByTestId('pagination-next').className).toContain('px-2 py-0.5');
  });

  it('is not rendered when totalPages <= 1', () => {
    const { container } = render(
      <Pagination {...defaults} totalPages={1} totalItems={5} />,
    );

    expect(container.innerHTML).toBe('');
    expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
  });
});
