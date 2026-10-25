import React, { useState } from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import ProUpsellDoor, {
  SCORES_UPSELL_BODY, SCORES_UPSELL_HEADING, SCORES_UPSELL_LABEL,
} from '../components/shared/ProUpsell.jsx';

/**
 * The generic Pro upsell door (docs/engineering/pro-features-gate-plan.md §4).
 *
 * <p><b>What breaks if these fail:</b> the door stays open over content it should not cover, its Escape
 * reaches a host dialog and closes the whole sheet with it, focus is dropped on {@code <body>}, or the
 * trigger announces without its Pro marker.
 */

function Door(props) {
  return (
    <ProUpsellDoor
      label={SCORES_UPSELL_LABEL}
      heading={SCORES_UPSELL_HEADING}
      body={SCORES_UPSELL_BODY}
      testIdPrefix="d"
      {...props}
    />
  );
}

const open = () => fireEvent.click(screen.getByTestId('d-trigger'));

describe('ProUpsellDoor', () => {
  it('names its trigger with the label and "Pro", and starts closed', () => {
    render(<Door />);
    const trigger = screen.getByRole('button', { name: 'Scores and the why Pro' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('puts the Pro pill inside the trigger', () => {
    render(<Door />);
    expect(screen.getByTestId('d-trigger')).toHaveTextContent(/Pro$/);
    expect(within(screen.getByTestId('d-trigger')).getByText('Pro')).toBeInTheDocument();
  });

  it('opens a non-modal dialog named by its heading and described by its body, focus on the heading', () => {
    render(<Door />);
    open();
    const trigger = screen.getByTestId('d-trigger');
    const dialog = screen.getByRole('dialog', { name: 'Fiery Sky and Golden Hour scores' });
    expect(dialog).not.toHaveAttribute('aria-modal');
    expect(dialog).toHaveAccessibleDescription(SCORES_UPSELL_BODY);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(trigger).toHaveAttribute('aria-controls', dialog.id);
    expect(screen.getByTestId('d-heading')).toHaveFocus();
  });

  it('carries no link and no "upgrade" word', () => {
    render(<Door />);
    open();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByTestId('d-door').textContent.toLowerCase()).not.toContain('upgrade');
  });

  it('"Not now" closes it and returns focus to the trigger', () => {
    render(<Door />);
    open();
    fireEvent.click(screen.getByTestId('d-dismiss'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('d-trigger')).toHaveFocus();
    expect(screen.getByTestId('d-trigger')).toHaveAttribute('aria-expanded', 'false');
  });

  it('Escape on the popover closes it, returns focus, and is stopped before the document', () => {
    render(<Door />);
    open();
    let reached = false;
    const spy = () => { reached = true; };
    document.addEventListener('keydown', spy);
    const proceeded = fireEvent.keyDown(screen.getByTestId('d-heading'), { key: 'Escape' });
    document.removeEventListener('keydown', spy);
    expect(reached).toBe(false);
    expect(proceeded).toBe(false); // preventDefault was called
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('d-trigger')).toHaveFocus();
  });

  it('Escape on the trigger closes an open popover, stopped before the document', () => {
    render(<Door />);
    open();
    screen.getByTestId('d-trigger').focus();
    let reached = false;
    const spy = () => { reached = true; };
    document.addEventListener('keydown', spy);
    const proceeded = fireEvent.keyDown(screen.getByTestId('d-trigger'), { key: 'Escape' });
    document.removeEventListener('keydown', spy);
    expect(reached).toBe(false);
    expect(proceeded).toBe(false);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('d-trigger')).toHaveFocus();
  });

  it('Escape while closed is left alone for the host', () => {
    render(<Door />);
    let reached = false;
    const spy = () => { reached = true; };
    document.addEventListener('keydown', spy);
    fireEvent.keyDown(screen.getByTestId('d-trigger'), { key: 'Escape' });
    document.removeEventListener('keydown', spy);
    expect(reached).toBe(true);
  });

  it('an outside pointerdown closes it', () => {
    render(<div><Door /><p data-testid="elsewhere">elsewhere</p></div>);
    open();
    fireEvent.pointerDown(screen.getByTestId('elsewhere'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a pointerdown inside the popover leaves it open', () => {
    render(<Door />);
    open();
    fireEvent.pointerDown(screen.getByTestId('d-body'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('a focusin outside closes it and does not move focus', () => {
    render(<div><button type="button" data-testid="other">other</button><Door /></div>);
    open();
    act(() => { screen.getByTestId('other').focus(); });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('other')).toHaveFocus();
  });

  it('a press inside the popover whose focus lands on the popover itself keeps it open', () => {
    render(<div tabIndex={-1} data-testid="modal-root"><Door /></div>);
    open();
    const dialog = screen.getByRole('dialog');
    fireEvent.pointerDown(screen.getByTestId('d-body'));
    // What a browser does on a mousedown in non-focusable text: focus the nearest focusable ancestor,
    // which must be the popover (tabIndex -1), not the host's root.
    expect(dialog).toHaveAttribute('tabindex', '-1');
    act(() => { dialog.focus(); });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('focus landing on the host root outside the door still closes it', () => {
    render(<div tabIndex={-1} data-testid="modal-root"><Door /></div>);
    open();
    act(() => { screen.getByTestId('modal-root').focus(); });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a focusin on the trigger keeps it open', () => {
    render(<Door />);
    open();
    act(() => { screen.getByTestId('d-trigger').focus(); });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('unmounting while open does not strand focus on the body', () => {
    function Host() {
      const [shown, setShown] = useState(true);
      return (
        <div tabIndex={-1} data-testid="host">
          <button type="button" data-testid="hide" onClick={() => setShown(false)}>hide</button>
          {shown && <Door />}
        </div>
      );
    }
    render(<Host />);
    open();
    expect(screen.getByTestId('d-heading')).toHaveFocus();
    // Remove the door while focus is inside its popover.
    act(() => { fireEvent.click(screen.getByTestId('hide')); });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.body).not.toHaveFocus();
  });

  it('toggles closed when the trigger is pressed again', () => {
    render(<Door />);
    open();
    open();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
