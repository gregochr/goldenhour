import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { describe, it, expect, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
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

  /**
   * {@code stacked} — M5's containment opt-in.
   *
   * <p>⚠️ Everything here asserts the ATTRIBUTES, never the behaviour, and that is a limit of the
   * environment rather than a gap in the suite: {@code 'inert' in HTMLElement.prototype} is
   * {@code false} in this project's jsdom, so a test claiming "Tab cannot leave" would pass against
   * an element carrying no guard at all. The behaviour was measured in a real browser (Chromium,
   * `visibilityState: 'visible'`) and the numbers are in the plan's M5 row: with two layers open, a
   * 24-press Tab walk from the top one never entered the layer beneath.
   */
  describe('stacked', () => {
    it('emits NEITHER attribute by default, which is what keeps every other caller unchanged', () => {
      // The pinning half of plan §3 rule 10. Only the Plan-screen dialogs (window popup, sheet,
      // search) pass this prop; every other one — the outcome modal, the settings modal, the
      // aurora modals, the admin views — must render exactly what it rendered before.
      render(<Modal label="Test"><p>Content</p></Modal>);
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('aria-modal', 'true');
      expect(dialog).not.toHaveAttribute('inert');
    });

    it('takes inert and DROPS aria-modal when something is over it', () => {
      // Both halves, because they fix different readers. `inert` takes the layer out of the tab
      // order and out of the accessibility tree; dropping `aria-modal` is what stops two elements
      // claiming to be the one modal, which is what a screen reader resolves the stack from.
      render(<Modal label="Test" stacked><p>Content</p></Modal>);
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('inert');
      expect(dialog).not.toHaveAttribute('aria-modal');
      expect(dialog).toHaveAttribute('aria-label', 'Test');
    });

    it('⚠️ puts focus back inside itself when the layer over it goes', async () => {
      // The regression the first cut shipped, caught in a browser: `inert` blurs whatever inside it
      // had focus, and because `stacked` is a PROP that lands in React's mutation phase the blur
      // beats every effect in the commit — including `useDialogFocus`'s reading of
      // `document.activeElement`. So the arriving dialog recorded `<body>` as the place to go back
      // to, and Escape dropped the reader at the top of the document instead of on the chip they
      // opened the sheet from.
      //
      // jsdom does not implement `inert`, so it cannot reproduce the blur — the blur is simulated
      // here, which is what makes this a test of the REMEDY (does the dialog remember for itself?)
      // rather than of the environment.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      chip.focus();
      expect(document.activeElement).toBe(chip);

      // Stack something over it, and blur exactly as `inert` would in a browser.
      rerender(<Host stacked />);
      expect(screen.getByTestId('under')).toHaveAttribute('inert');
      chip.blur();
      expect(document.activeElement).toBe(document.body);

      // Unstack: the layer underneath restores its own last-focused control.
      rerender(<Host stacked={false} />);
      await act(async () => {});
      expect(document.activeElement).toBe(chip);
    });

    it('⚠️ records the opener on POINTERDOWN too, because Safari does not focus a button on click', () => {
      // The engine assumption the first cut made, found by an adversarial review. macOS and iOS
      // Safari do not move focus to a `<button>` on a click unless Full Keyboard Access is on, and
      // that is the default — so on the very gesture this mechanism was measured against (click a
      // chip on the popup's map, press Escape) no focus event fires, `lastInside` stays null, and
      // the fix degrades silently to the `<body>` behaviour it exists to replace. Both browser
      // measurements behind it were headless Chromium.
      //
      // Simulated by firing `pointerDown` WITHOUT a focus event, which is exactly what that engine
      // does — and which is why this cannot be folded into the test above.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      fireEvent.pointerDown(chip);
      expect(document.activeElement).not.toBe(chip);

      rerender(<Host stacked />);
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('records nothing from a press on the backdrop, which is not a control inside it', () => {
      // `pointerdown` fires for the scrim as well, and the scrim's job is to CLOSE. Recording it
      // would make the restore focus a `role="presentation"` div — a focus target that announces
      // nothing and that a reader cannot leave by any route they would guess.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      fireEvent.pointerDown(chip);
      // The press that must not overwrite it. Asserted as "the chip is still what comes back"
      // rather than "the backdrop is not focused": a `role="presentation"` div takes no focus, so
      // the weaker form passes against a version that records the backdrop and then silently fails
      // to focus it — measured.
      fireEvent.pointerDown(screen.getByTestId('under-backdrop'));

      rerender(<Host stacked />);
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('⚠️ records a CONTROL inside it, never the dialog root itself', () => {
      // `useDialogFocus` focuses the ROOT when a dialog opens (deliberately — see its header), and
      // that focus event bubbles through this recorder. Without the guard the root becomes the thing
      // restored, so an uncovered dialog puts focus on its own container rather than on the chip the
      // reader was on: a weaker form of the exact defect this effect exists to fix, and one no other
      // test could see.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      chip.focus();
      // The root takes focus AFTER the control, which is the real ordering on a fast pointer route:
      // `useDialogFocus` focuses the root a frame after mount, by which time a click has already
      // moved focus to a control inside.
      screen.getByTestId('under').focus();

      rerender(<Host stacked />);
      // `inert` blurs whatever inside had focus; jsdom does not implement it, so it is simulated.
      document.activeElement.blur();
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('does NOT yank focus back from wherever the reader has since moved to', async () => {
      // The guard on the restore. A keyboard reader can Tab out of the topmost dialog into the page
      // — that is the app's settled, unchanged behaviour — and a dialog underneath that grabbed
      // focus the moment it was uncovered would fight them for it.
      const Host = ({ stacked }) => (
        <div>
          <button type="button" data-testid="outside">Outside</button>
          <Modal label="Under" stacked={stacked} data-testid="under">
            <button type="button" data-testid="chip">Chip</button>
          </Modal>
        </div>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      screen.getByTestId('chip').focus();
      rerender(<Host stacked />);
      expect(screen.getByTestId('under')).toHaveAttribute('inert');

      // The reader Tabs out into the page while the top layer is up — the app's settled behaviour.
      const outside = screen.getByTestId('outside');
      outside.focus();
      rerender(<Host stacked={false} />);
      await act(async () => {});
      expect(document.activeElement).toBe(outside);
    });

    it('leaves the unstacked path exactly as it was — the container takes focus, nothing else moves', async () => {
      // ⚠️ Named for what it can actually see. An adversarial review disabled the whole restore
      // effect and this test still passed, because `lastInside` is null on a dialog that is never
      // stacked and the effect returns at its first guard — so what is asserted is
      // `useDialogFocus`'s own behaviour, unchanged. That is worth an assertion (it is the
      // guarantee rule 10 asks for) and it is NOT a pin on the new effect; the three tests above
      // are.
      render(<TriggerAndDialog />);
      const trigger = screen.getByText('Open');
      trigger.focus();
      fireEvent.click(trigger);
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
      expect(screen.getByTestId('m')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('m')).toHaveAttribute('aria-modal', 'true');
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
      // Most render sites would lose state: some carry unsaved forms, one holds a generated
      // password that has to be copied before it is gone. Turning dismissal on everywhere would
      // have converted a dead handler into a data-loss handler.
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
