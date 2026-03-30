import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Modal from '../../components/shared/Modal.jsx';

describe('Modal', () => {
  it('renders children', () => {
    render(<Modal label="Test"><p>Hello</p></Modal>);
    expect(screen.getByText('Hello')).toBeInTheDocument();
  });

  it('defaults to max-w-md', () => {
    render(<Modal label="Test" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-md');
  });

  it('applies max-w-sm', () => {
    render(<Modal label="Test" maxWidth="sm" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-sm');
  });

  it('applies max-w-lg', () => {
    render(<Modal label="Test" maxWidth="lg" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-lg');
  });

  it('sets aria attributes', () => {
    render(<Modal label="My Dialog"><p>Content</p></Modal>);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('aria-label', 'My Dialog');
  });

  it('forwards data-testid', () => {
    render(<Modal label="Test" data-testid="my-modal"><p>Content</p></Modal>);
    expect(screen.getByTestId('my-modal')).toBeInTheDocument();
  });

  it('calls onClose on backdrop click', () => {
    const onClose = vi.fn();
    render(<Modal label="Test" onClose={onClose} data-testid="m"><p>Content</p></Modal>);
    fireEvent.click(screen.getByTestId('m-backdrop'));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('does not crash when onClose is omitted and backdrop is clicked', () => {
    render(<Modal label="Test" data-testid="m"><p>Content</p></Modal>);
    // backdrop click with no onClose should not throw
    fireEvent.click(screen.getByTestId('m-backdrop'));
  });

  it('merges className onto panel', () => {
    render(<Modal label="Test" className="extra-class" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('extra-class');
  });

  it('renders children directly when bare is true (no panel wrapper)', () => {
    render(
      <Modal label="Test" bare data-testid="m">
        <div data-testid="custom-panel">Custom panel</div>
      </Modal>,
    );
    expect(screen.getByTestId('custom-panel')).toBeInTheDocument();
    // No standard panel wrapper
    expect(screen.getByTestId('m').querySelector('[class*="max-w-"]')).toBeNull();
  });

  it('still renders backdrop in bare mode', () => {
    const onClose = vi.fn();
    render(
      <Modal label="Test" bare onClose={onClose} data-testid="m">
        <div>Custom</div>
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('m-backdrop'));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
