import React, { useState } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PostcodePromptOverlay from '../components/auth/PostcodePromptOverlay.jsx';

vi.mock('../api/settingsApi.js', () => ({
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));

import { lookupPostcode, saveHome } from '../api/settingsApi.js';

const MORPETH = { postcode: 'NE61 1AA', placeName: 'Morpeth', latitude: 55.17, longitude: -1.69 };

/**
 * A stand-in for the app: a container with a "masthead" control and the overlay mounted among it,
 * the way `AppInner` mounts it. The overlay closes (unmounts) when `onSaved` or `onNotNow` fires,
 * as `AppInner` closes it.
 */
function App({ onSaved = vi.fn(), onNotNow = vi.fn() }) {
  const [open, setOpen] = useState(true);
  return (
    <div data-testid="app-root">
      <button type="button" data-testid="masthead-first">Settings</button>
      <button type="button" data-testid="masthead-second">Sign out</button>
      {open && (
        <PostcodePromptOverlay
          onSaved={(updated) => { onSaved(updated); setOpen(false); }}
          onNotNow={async () => { await onNotNow(); setOpen(false); }}
        />
      )}
    </div>
  );
}

/** The element the render put directly under <body>: what the overlay makes inert. */
const appContainer = () => screen.getByTestId('app-root').parentElement;

const realMatchMedia = globalThis.matchMedia;
const withFinePointer = () => {
  globalThis.matchMedia = (query) => ({
    matches: query === '(pointer: fine)', media: query,
    addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
  });
};

describe('PostcodePromptOverlay', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    globalThis.matchMedia = realMatchMedia;
  });

  it('is a modal dialog named by its heading, portalled to <body>, above the map, with the sign-in copy', () => {
    render(<App />);

    const dialog = screen.getByRole('dialog', { name: 'Set your home postcode' });
    expect(dialog).toBe(screen.getByTestId('postcode-prompt'));
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('data-postcode-prompt');
    expect(dialog.parentElement).toBe(document.body);
    expect(appContainer().contains(dialog)).toBe(false);
    expect(dialog).toHaveClass('fixed', 'inset-0', 'overflow-y-auto', 'bg-plex-bg', 'z-[1400]');
    expect(screen.getByRole('heading', { level: 2, name: 'Set your home postcode' })).toBeInTheDocument();
    expect(screen.getByText(
      "You haven't saved one yet. It sets your light times, and you can change it any time in Settings.",
    )).toBeInTheDocument();
    expect(screen.getByTestId('prompt-primary')).toHaveTextContent('Save');
    expect(screen.getByTestId('prompt-primary')).not.toHaveTextContent('continue');
    expect(screen.getByTestId('prompt-secondary')).toHaveTextContent('Not now');
    expect(screen.getByTestId('prompt-pro-line')).toHaveTextContent(
      'Also gives you drive times from home to every location.',
    );
    // The auth lockup, like every other auth page.
    expect(screen.getByTestId('auth-shell')).toBeInTheDocument();
  });

  it('makes the app container inert and not the dialog; the anchor it walks from sits in the app', () => {
    render(<App />);

    expect(appContainer()).toHaveAttribute('inert');
    expect(screen.getByTestId('postcode-prompt')).not.toHaveAttribute('inert');
    expect(screen.getByTestId('postcode-prompt').closest('[inert]')).toBeNull();
    expect(screen.getByTestId('app-root').contains(screen.getByTestId('postcode-prompt-anchor'))).toBe(true);
    expect(document.body).not.toHaveAttribute('inert');
    expect(document.documentElement).not.toHaveAttribute('inert');
  });

  it('lifts inert when it unmounts', () => {
    const { unmount } = render(<App />);
    const container = appContainer();
    expect(container).toHaveAttribute('inert');

    unmount();

    expect(container).not.toHaveAttribute('inert');
  });

  it('does not set, and so does not clear, an inert something else already holds', () => {
    function Host() {
      const [open, setOpen] = useState(false);
      return (
        <div data-testid="app-root">
          <button type="button" data-testid="show" onClick={() => setOpen(true)}>show</button>
          {open && <PostcodePromptOverlay onSaved={() => {}} onNotNow={() => setOpen(false)} />}
        </div>
      );
    }
    render(<Host />);
    const container = appContainer();
    container.setAttribute('inert', ''); // Ask's sheet got there first

    act(() => { screen.getByTestId('show').click(); });
    expect(screen.getByTestId('postcode-prompt')).toBeInTheDocument();
    act(() => { screen.getByTestId('prompt-secondary').click(); });

    expect(screen.queryByTestId('postcode-prompt')).toBeNull();
    expect(container).toHaveAttribute('inert'); // not ours to remove
  });

  it('moves focus into the dialog on mount', () => {
    render(<App />);

    expect(screen.getByTestId('postcode-prompt')).toHaveFocus();
  });

  it('puts the cursor in the postcode field under a fine pointer', () => {
    withFinePointer();
    render(<App />);

    expect(screen.getByTestId('prompt-input')).toHaveFocus();
  });

  it('leaves the field unfocused under a coarse pointer — the phone keyboard stays down', () => {
    render(<App />);

    expect(screen.getByTestId('prompt-input')).not.toHaveFocus();
  });

  it('Escape does nothing: Save and Not now are the two exits', async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    const onNotNow = vi.fn();
    render(<App onSaved={onSaved} onNotNow={onNotNow} />);

    await user.keyboard('{Escape}');
    await user.click(screen.getByTestId('prompt-input'));
    await user.keyboard('{Escape}');

    expect(screen.getByTestId('postcode-prompt')).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(onNotNow).not.toHaveBeenCalled();
  });

  it('Save hands the settings response (with the place name) to the consumer, then goes', async () => {
    const user = userEvent.setup();
    lookupPostcode.mockResolvedValue(MORPETH);
    saveHome.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: null, postcodePromptDue: false });
    const onSaved = vi.fn();
    render(<App onSaved={onSaved} />);

    await user.type(screen.getByTestId('prompt-input'), 'NE61 1AA');
    await user.click(screen.getByTestId('prompt-lookup'));
    await user.click(await screen.findByTestId('prompt-primary'));

    expect(saveHome).toHaveBeenCalledWith('NE61 1AA', 55.17, -1.69, null);
    expect(onSaved).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith({
      homePostcode: 'NE61 1AA', homePlaceName: 'Morpeth', postcodePromptDue: false,
    });
    expect(screen.queryByTestId('postcode-prompt')).toBeNull();
    expect(appContainer()).not.toHaveAttribute('inert');
  });

  it('Not now calls the consumer once, saves nothing, and goes', async () => {
    const user = userEvent.setup();
    const onNotNow = vi.fn();
    render(<App onNotNow={onNotNow} />);

    await user.click(screen.getByTestId('prompt-secondary'));

    expect(onNotNow).toHaveBeenCalledTimes(1);
    expect(saveHome).not.toHaveBeenCalled();
    expect(lookupPostcode).not.toHaveBeenCalled();
    expect(screen.queryByTestId('postcode-prompt')).toBeNull();
  });

  describe('on close, focus goes back into the app — never to <body>', () => {
    it("to the app's first focusable when nothing held focus (a fresh sign-in)", async () => {
      const user = userEvent.setup();
      render(<App />);
      expect(document.activeElement).toBe(screen.getByTestId('postcode-prompt'));

      await user.click(screen.getByTestId('prompt-secondary'));

      expect(screen.getByTestId('masthead-first')).toHaveFocus();
    });

    it('to the opener when it is still there', () => {
      function Host() {
        const [open, setOpen] = useState(false);
        return (
          <div data-testid="app-root">
            <button type="button" data-testid="opener" onClick={() => setOpen(true)}>open</button>
            {open && <PostcodePromptOverlay onSaved={() => {}} onNotNow={() => setOpen(false)} />}
          </div>
        );
      }
      render(<Host />);
      act(() => { screen.getByTestId('opener').focus(); screen.getByTestId('opener').click(); });
      expect(screen.getByTestId('postcode-prompt')).toHaveFocus();

      act(() => { screen.getByTestId('prompt-secondary').click(); });

      expect(screen.getByTestId('opener')).toHaveFocus();
    });
  });
});
