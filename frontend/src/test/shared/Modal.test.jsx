import React, { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import Modal from '../../components/shared/Modal.jsx';

/**
 * A trigger that opens a dialog — the shape every real consumer has, and the only way to observe
 * focus RESTORE, which needs something to restore to. `removeTriggerOnClose` reproduces the case
 * where the dialog outlives the element that opened it.
 */
function TriggerAndDialog({ removeTriggerOnClose = false, ...modalProps }) {
  const [open, setOpen] = useState(false);
  const [triggerGone, setTriggerGone] = useState(false);
  const close = () => {
    setOpen(false);
    if (removeTriggerOnClose) setTriggerGone(true);
  };
  return (
    <div>
      {!triggerGone && (
        <button type="button" onClick={() => setOpen(true)}>Open</button>
      )}
      {open && (
        <Modal label="Test dialog" onClose={close} data-testid="m" {...modalProps}>
          <button type="button">Inside</button>
        </Modal>
      )}
    </div>
  );
}

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

  describe('focus', () => {
    it('takes focus when it opens', async () => {
      // `role="dialog"` and `aria-modal` are semantics; neither moves focus. Without this a
      // keyboard user had to Tab through the whole page behind the backdrop to reach a dialog that
      // had just opened over it.
      render(<TriggerAndDialog />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));

      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
    });

    it('focuses the container, not the first control inside it', async () => {
      // Deliberate: focusing the container works when the dialog has nothing focusable yet (content
      // still loading) or nothing focusable at all, and it leaves a consumer free to autofocus its
      // own field afterwards. A screen reader reads the dialog's accessible name on landing.
      render(<TriggerAndDialog />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));

      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
      expect(screen.getByRole('button', { name: 'Inside' })).not.toHaveFocus();
    });

    it('yields to a consumer that has already focused one of its own controls', async () => {
      // The container focus is deferred by a frame, so a consumer that focuses synchronously on
      // mount would otherwise have it taken straight back. Which effect lands first depends on how
      // fast that consumer's fetch is, so both orders have to end in the same place. This is not
      // hypothetical: the settings modal focuses its postcode field, and it regressed on the first
      // cut of this hook.
      // The self-focusing element is a CHILD COMPONENT of Modal, not its parent — and that is what
      // makes this test bite. React runs child effects before parent ones, so this focuses the
      // field BEFORE Modal's own effect runs. Written the other way round (the effect on a wrapper
      // around Modal) it runs after, the consumer wins by ordering alone, and deleting the guard
      // leaves the test green.
      function SelfFocusingChild() {
        const ref = React.useRef(null);
        React.useEffect(() => { ref.current?.focus(); }, []);
        return <input ref={ref} data-testid="own-field" />;
      }
      render(
        <Modal label="Test dialog" data-testid="m">
          <SelfFocusingChild />
        </Modal>,
      );

      await waitFor(() => expect(screen.getByTestId('own-field')).toHaveFocus());
      // And it stays there once the deferred container focus has had its chance to fire.
      await new Promise((r) => { requestAnimationFrame(() => requestAnimationFrame(r)); });
      expect(screen.getByTestId('own-field')).toHaveFocus();
    });

    it('gives focus back to whatever opened it', async () => {
      render(<TriggerAndDialog closeOnEscape />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      trigger.focus();
      fireEvent.click(trigger);
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());

      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(trigger).toHaveFocus());
    });

    it('does not throw when the thing that opened it has since been removed', async () => {
      // A dialog can be closed by something other than the user — a poll, an SSE event, a parent
      // re-render that drops the row the trigger lived on. Focusing a detached node throws the
      // user's place away rather than returning it, so the restore is skipped instead.
      render(<TriggerAndDialog closeOnEscape removeTriggerOnClose />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());

      expect(() => fireEvent.keyDown(document, { key: 'Escape' })).not.toThrow();
      expect(screen.queryByTestId('m')).toBeNull();
    });
  });

  describe('Escape', () => {
    it('closes the dialog when the caller opted in', () => {
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledOnce();
    });

    it('does nothing by default, because most dialogs here hold something to lose', () => {
      // Eleven of the fifteen render sites would lose state: four carry unsaved forms, one holds a
      // generated password that has to be copied before it is gone. Turning dismissal on everywhere
      // would have converted a dead handler into a data-loss handler.
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose}><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    });

    it('does nothing on a dialog with no onClose, even when asked', () => {
      // The settings modal's refresh spinner is deliberately unclosable and passes no handler.
      expect(() => {
        render(<Modal label="Test" closeOnEscape><p>Content</p></Modal>);
        fireEvent.keyDown(document, { key: 'Escape' });
      }).not.toThrow();
    });

    it('stops listening once it has closed', () => {
      const onClose = vi.fn();
      const { unmount } = render(
        <Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>,
      );
      unmount();

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    });

    it('ignores every other key', () => {
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Enter' });
      fireEvent.keyDown(document, { key: ' ' });
      fireEvent.keyDown(document, { key: 'Tab' });
      expect(onClose).not.toHaveBeenCalled();
    });
  });
});
