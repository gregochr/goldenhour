import React, { useRef, useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MastheadMenu from '../components/MastheadMenu.jsx';

/**
 * The phone masthead's ⋯ menu (map-mobile-compact-plan.md C1, D-2, R3–R5).
 *
 * <p>Hosted in a harness that owns the open state exactly as the shell does, beside a stand-in for
 * the Map pane that listens for Escape and presses the way the real pane does — the menu floats over
 * it, and the property under test is that a press which dismisses the menu reaches nothing else.
 */
function Harness({ initialOpen = false, statusLine = null, handlers = {}, onMapEscape, onMapPress }) {
  const [open, setOpen] = useState(initialOpen);
  const buttonRef = useRef(null);
  return (
    // The root listens for presses too, so a catcher press that BUBBLED would be heard here.
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div onClick={onMapPress}>
      <MastheadMenu
        open={open}
        onOpenChange={setOpen}
        buttonRef={buttonRef}
        statusLine={statusLine}
        onSettings={handlers.onSettings ?? vi.fn()}
        onChangePostcode={handlers.onChangePostcode ?? vi.fn()}
        onSignOut={handlers.onSignOut ?? vi.fn()}
        onWarmSettings={handlers.onWarmSettings}
        onGuide={handlers.onGuide}
      />
      <button type="button" data-testid="elsewhere">elsewhere</button>
      {/* The Map pane stand-in: a document-level Escape listener and a press listener, as the real
          pane's popovers carry. */}
      <div data-testid="map-pane" onPointerDown={onMapPress} onClick={onMapPress} role="presentation" />
      <EscapeListener onEscape={onMapEscape} />
    </div>
  );
}

function EscapeListener({ onEscape }) {
  React.useEffect(() => {
    if (!onEscape) return undefined;
    const handler = (e) => { if (e.key === 'Escape') onEscape(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onEscape]);
  return null;
}

const openMenu = () => fireEvent.click(screen.getByTestId('masthead-menu-button'));

describe('MastheadMenu — opening and the button', () => {
  it('is closed at rest, with the button naming the popover it controls', () => {
    render(<Harness />);
    const button = screen.getByTestId('masthead-menu-button');
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAttribute('aria-controls', 'wf-mast-menu-pop');
    expect(button).toHaveAccessibleName('Menu');
  });

  it('opens on a press and says so, and a second press closes it', () => {
    render(<Harness />);
    openMenu();
    expect(screen.getByTestId('masthead-menu-button')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('group', { name: 'Account and settings' })).toBeInTheDocument();
    openMenu();
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
  });

  it('is a plain group of buttons, not role=menu, with three rows and no status line by default', () => {
    render(<Harness initialOpen />);
    expect(screen.queryByRole('menu')).toBeNull();
    const rows = screen.getByTestId('masthead-menu').querySelectorAll('button');
    expect([...rows].map((b) => b.textContent)).toEqual([
      'Settings', 'Change home postcode', 'Sign out',
    ]);
    // Every row's class carries the 44px rule (`.wf-mast-menu-row`, pinned in the cascade test).
    rows.forEach((b) => expect(b).toHaveClass('wf-mast-menu-row'));
  });

  it('keeps the settings and sign-out test ids on its rows', () => {
    render(<Harness initialOpen />);
    expect(screen.getByTestId('window-first-settings')).toHaveTextContent('Settings');
    expect(screen.getByTestId('window-first-signout')).toHaveTextContent('Sign out');
  });

  it('focuses the button when it opens, whatever the device did with the press', () => {
    render(<Harness />);
    openMenu();
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
  });
});

describe('MastheadMenu — closing', () => {
  it('closes on Escape, returns focus to the button, and stops the key at the menu', () => {
    const onMapEscape = vi.fn();
    render(<Harness onMapEscape={onMapEscape} />);
    openMenu();
    screen.getByTestId('window-first-settings').focus();

    const notPrevented = fireEvent.keyDown(screen.getByTestId('window-first-settings'), { key: 'Escape' });

    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
    // ⚠️ The Map's own Escape rules must not hear the press that closed the menu (R3): a
    // document-level listener beyond the menu stays silent, and the key's default is prevented.
    expect(onMapEscape).not.toHaveBeenCalled();
    expect(notPrevented).toBe(false);
  });

  it('leaves Escape alone while closed, so the Map keeps its own rule', () => {
    const onMapEscape = vi.fn();
    render(<Harness onMapEscape={onMapEscape} />);
    screen.getByTestId('masthead-menu-button').focus();

    fireEvent.keyDown(screen.getByTestId('masthead-menu-button'), { key: 'Escape' });

    expect(onMapEscape).toHaveBeenCalledTimes(1);
  });

  it('draws a catcher under the popover whose press closes it and reaches nothing beneath', () => {
    const onMapPress = vi.fn();
    render(<Harness onMapPress={onMapPress} />);
    openMenu();
    onMapPress.mockClear();

    fireEvent.click(screen.getByTestId('masthead-menu-catcher'));

    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(screen.queryByTestId('masthead-menu-catcher')).toBeNull();
    expect(onMapPress).not.toHaveBeenCalled();
    // Focus returns to ⋯, never to <body>, after a tap on dead space.
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
  });

  it('closes when focus Tabs out of the wrapper onto another control', () => {
    render(<Harness initialOpen />);
    screen.getByTestId('window-first-signout').focus();

    fireEvent.blur(screen.getByTestId('window-first-signout'), {
      relatedTarget: screen.getByTestId('elsewhere'),
    });

    expect(screen.queryByTestId('masthead-menu')).toBeNull();
  });

  it('⚠️ stays open when focus moves between its own controls, or to nothing', () => {
    // A null relatedTarget is what Safari reports for a tap on any button; closing on it would
    // unmount the row before its click lands.
    render(<Harness initialOpen />);
    fireEvent.blur(screen.getByTestId('window-first-settings'), {
      relatedTarget: screen.getByTestId('masthead-menu-postcode'),
    });
    expect(screen.getByTestId('masthead-menu')).toBeInTheDocument();
    fireEvent.blur(screen.getByTestId('window-first-settings'), { relatedTarget: null });
    expect(screen.getByTestId('masthead-menu')).toBeInTheDocument();
  });
});

describe('MastheadMenu — the rows', () => {
  it.each([
    ['window-first-settings', 'onSettings'],
    ['masthead-menu-postcode', 'onChangePostcode'],
    ['window-first-signout', 'onSignOut'],
  ])('%s calls its handler once, after closing the menu and focusing ⋯', (testId, key) => {
    const handler = vi.fn();
    let focusWhenCalled = null;
    let openWhenCalled = null;
    handler.mockImplementation(() => {
      focusWhenCalled = document.activeElement;
      openWhenCalled = screen.queryByTestId('masthead-menu') !== null;
    });
    render(<Harness initialOpen handlers={{ [key]: handler }} />);

    fireEvent.click(screen.getByTestId(testId));

    expect(handler).toHaveBeenCalledTimes(1);
    // R5: ⋯ already holds focus when the handler runs, so a dialog it opens records ⋯ as its opener
    // rather than a row that is about to unmount. (The menu itself unmounts on the next commit.)
    expect(focusWhenCalled).toBe(screen.getByTestId('masthead-menu-button'));
    expect(openWhenCalled).toBe(true);
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
  });

  it('warms the settings chunk on hover and focus of the Settings row only', () => {
    const onWarmSettings = vi.fn();
    render(<Harness initialOpen handlers={{ onWarmSettings }} />);

    fireEvent.pointerEnter(screen.getByTestId('window-first-settings'));
    expect(onWarmSettings).toHaveBeenCalledTimes(1);
    fireEvent.pointerEnter(screen.getByTestId('window-first-signout'));
    expect(onWarmSettings).toHaveBeenCalledTimes(1);
  });
});

describe('MastheadMenu — the admin status line', () => {
  const LINE = <button type="button" data-testid="health-status-line">UP</button>;

  it('draws the status line first when one is handed over', () => {
    render(<Harness initialOpen statusLine={LINE} />);
    const buttons = [...screen.getByTestId('masthead-menu').querySelectorAll('button')];
    expect(buttons.map((b) => b.getAttribute('data-testid'))).toEqual([
      'health-status-line', 'window-first-settings', 'masthead-menu-postcode', 'window-first-signout',
    ]);
  });

  it('draws none for a reader who was handed none (the non-admin case)', () => {
    render(<Harness initialOpen />);
    expect(screen.queryByTestId('health-status-line')).toBeNull();
  });

  it('closes the menu around a press on it and hands focus to ⋯, after the line has acted', () => {
    const acted = vi.fn();
    const line = <button type="button" data-testid="health-status-line" onClick={acted}>UP</button>;
    render(<Harness initialOpen statusLine={line} />);

    fireEvent.click(screen.getByTestId('health-status-line'));

    expect(acted).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
    expect(screen.getByTestId('masthead-menu-button')).toHaveFocus();
  });
});

describe('MastheadMenu — the Guide row (G2)', () => {
  it('is drawn only when the shell passes a handler, between the postcode row and Sign out', () => {
    render(<Harness initialOpen handlers={{ onGuide: vi.fn() }} />);
    const rows = Array.from(screen.getByTestId('masthead-menu').querySelectorAll('.wf-mast-menu-row'))
      .map((row) => row.textContent);
    expect(rows).toEqual(['Settings', 'Change home postcode', 'Guide', 'Sign out']);
  });

  it('is absent without one (Operations has no guide page)', () => {
    render(<Harness initialOpen />);
    expect(screen.queryByTestId('masthead-menu-guide')).toBeNull();
  });

  it('closes the menu, focuses ⋯ first and then calls the handler, as every row does', () => {
    let focusedWhenCalled = null;
    const onGuide = vi.fn(() => { focusedWhenCalled = document.activeElement; });
    render(<Harness initialOpen handlers={{ onGuide }} />);
    fireEvent.click(screen.getByTestId('masthead-menu-guide'));
    expect(onGuide).toHaveBeenCalledTimes(1);
    expect(focusedWhenCalled).toBe(screen.getByTestId('masthead-menu-button'));
    expect(screen.queryByTestId('masthead-menu')).toBeNull();
  });
});
