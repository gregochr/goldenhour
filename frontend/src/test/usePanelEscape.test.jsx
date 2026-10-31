import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useRef } from 'react';
import PropTypes from 'prop-types';
import usePanelEscape, { PANEL_EDIT_FORM_ATTR } from '../hooks/usePanelEscape.js';

/**
 * A harness, not a consumer: the edit-form exception has no real caller until the Locations and Regions panels
 * land (P5, P6), and the Waitlist panel has no text field of its own. Every other rule is also pinned through the
 * real Waitlist panel in WaitlistManagementView.test.jsx.
 */
function Harness({ onEscape, active = true, paneHidden = false }) {
  const ref = useRef(null);
  usePanelEscape({ active, panelRef: ref, onEscape });
  return (
    <div hidden={paneHidden} data-testid="pane">
      <aside ref={ref} aria-label="panel">
        <button type="button" data-testid="in-panel-button">x</button>
        <input data-testid="in-panel-input" />
        <form {...{ [PANEL_EDIT_FORM_ATTR]: '' }}>
          <input data-testid="edit-input" />
          <textarea data-testid="edit-textarea" />
          <input type="checkbox" data-testid="edit-checkbox" />
        </form>
      </aside>
      <input data-testid="outside-input" />
    </div>
  );
}

Harness.propTypes = {
  onEscape: PropTypes.func.isRequired,
  active: PropTypes.bool,
  paneHidden: PropTypes.bool,
};

describe('usePanelEscape', () => {
  beforeEach(() => vi.clearAllMocks());

  it('calls onEscape when Esc is pressed with focus on the panel', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    fireEvent.keyDown(screen.getByTestId('in-panel-button'), { key: 'Escape' });
    expect(onEscape).toHaveBeenCalledTimes(1);
  });

  it('ignores every other key', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    fireEvent.keyDown(document.body, { key: 'Enter' });
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('listens only while a panel is open', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} active={false} />);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('ignores Esc while another dialog is open', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.appendChild(dialog);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    dialog.remove();
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('ignores Esc while an aria-modal element is open', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    const modal = document.createElement('div');
    modal.setAttribute('aria-modal', 'true');
    document.body.appendChild(modal);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    modal.remove();
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('ignores Esc something already took', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    document.body.addEventListener('keydown', (e) => e.preventDefault(), { once: true });
    document.body.dispatchEvent(event);
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('ignores Esc when the Operations pane is hidden (the reader is on Map or Plan)', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} paneHidden />);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('leaves Esc to a text field outside the edit form', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    fireEvent.keyDown(screen.getByTestId('outside-input'), { key: 'Escape' });
    fireEvent.keyDown(screen.getByTestId('in-panel-input'), { key: 'Escape' });
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('takes Esc from a text input or textarea inside the panel\'s edit form (it cancels the edit)', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    fireEvent.keyDown(screen.getByTestId('edit-input'), { key: 'Escape' });
    fireEvent.keyDown(screen.getByTestId('edit-textarea'), { key: 'Escape' });
    expect(onEscape).toHaveBeenCalledTimes(2);
  });

  it('does not treat a checkbox as a text field', () => {
    const onEscape = vi.fn();
    render(<Harness onEscape={onEscape} />);
    fireEvent.keyDown(screen.getByTestId('edit-checkbox'), { key: 'Escape' });
    expect(onEscape).toHaveBeenCalledTimes(1);
  });

  it('calls the latest onEscape without re-subscribing', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = render(<Harness onEscape={first} />);
    rerender(<Harness onEscape={second} />);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });
});
