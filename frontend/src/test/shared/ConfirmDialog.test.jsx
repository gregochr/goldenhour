import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ConfirmDialog from '../../components/shared/ConfirmDialog.jsx';

const defaults = {
  title: 'Delete item?',
  message: 'This cannot be undone.',
  confirmLabel: 'Delete',
  onConfirm: () => {},
  onCancel: () => {},
};

describe('ConfirmDialog', () => {
  it('renders title, message, and confirm label', () => {
    render(<ConfirmDialog {...defaults} />);
    expect(screen.getByText('Delete item?')).toBeInTheDocument();
    expect(screen.getByText('This cannot be undone.')).toBeInTheDocument();
    expect(screen.getByText('Delete')).toBeInTheDocument();
  });

  it('renders children between message and buttons', () => {
    render(
      <ConfirmDialog {...defaults}>
        <p>Extra info</p>
      </ConfirmDialog>,
    );
    expect(screen.getByText('Extra info')).toBeInTheDocument();
  });

  it('calls onConfirm when confirm clicked', () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog {...defaults} onConfirm={onConfirm} />);
    fireEvent.click(screen.getByTestId('confirm-dialog-confirm'));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('calls onCancel when cancel clicked', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog {...defaults} onCancel={onCancel} />);
    fireEvent.click(screen.getByTestId('confirm-dialog-cancel'));
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('uses btn-primary by default (non-destructive)', () => {
    render(<ConfirmDialog {...defaults} />);
    expect(screen.getByTestId('confirm-dialog-confirm').className).toContain('btn-primary');
  });

  it('uses red styling when destructive', () => {
    render(<ConfirmDialog {...defaults} destructive />);
    const btn = screen.getByTestId('confirm-dialog-confirm');
    expect(btn.className).toContain('bg-red-700');
    expect(btn.className).not.toContain('btn-primary');
  });

  it('preserves data-testid values', () => {
    render(<ConfirmDialog {...defaults} />);
    expect(screen.getByTestId('confirm-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('confirm-dialog-confirm')).toBeInTheDocument();
  });

  it('passes maxWidth to Modal', () => {
    render(<ConfirmDialog {...defaults} maxWidth="sm" />);
    const panel = screen.getByTestId('confirm-dialog').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-sm');
  });

  it('calls onCancel on backdrop click', () => {
    const onCancel = vi.fn();
    render(<ConfirmDialog {...defaults} onCancel={onCancel} />);
    fireEvent.click(screen.getByTestId('confirm-dialog-backdrop'));
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
