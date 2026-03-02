import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import BottomSheet from '../components/BottomSheet.jsx';

describe('BottomSheet', () => {
  it('renders nothing when open is false', () => {
    const { container } = render(
      <BottomSheet open={false} onClose={() => {}}>
        <p>Content</p>
      </BottomSheet>,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders overlay and sheet content when open is true', () => {
    render(
      <BottomSheet open onClose={() => {}}>
        <p>Sheet content</p>
      </BottomSheet>,
    );
    expect(screen.getByTestId('bottom-sheet-overlay')).toBeInTheDocument();
    expect(screen.getByTestId('bottom-sheet')).toBeInTheDocument();
    expect(screen.getByText('Sheet content')).toBeInTheDocument();
  });

  it('calls onClose when overlay is clicked', () => {
    const onClose = vi.fn();
    render(
      <BottomSheet open onClose={onClose}>
        <p>Content</p>
      </BottomSheet>,
    );
    fireEvent.click(screen.getByTestId('bottom-sheet-overlay'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when close button is clicked', () => {
    const onClose = vi.fn();
    render(
      <BottomSheet open onClose={onClose}>
        <p>Content</p>
      </BottomSheet>,
    );
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('has dialog role and aria-modal on the sheet', () => {
    render(
      <BottomSheet open onClose={() => {}}>
        <p>Content</p>
      </BottomSheet>,
    );
    const sheet = screen.getByTestId('bottom-sheet');
    expect(sheet).toHaveAttribute('role', 'dialog');
    expect(sheet).toHaveAttribute('aria-modal', 'true');
  });

  it('applies slide-up animation class', () => {
    render(
      <BottomSheet open onClose={() => {}}>
        <p>Content</p>
      </BottomSheet>,
    );
    expect(screen.getByTestId('bottom-sheet')).toHaveClass('animate-slide-up');
  });

  it('prevents body scroll when open', () => {
    const { unmount } = render(
      <BottomSheet open onClose={() => {}}>
        <p>Content</p>
      </BottomSheet>,
    );
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });
});
