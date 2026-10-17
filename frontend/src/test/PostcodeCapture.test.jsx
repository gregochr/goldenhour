import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PostcodeCapture from '../components/auth/PostcodeCapture.jsx';

vi.mock('../api/settingsApi.js', () => ({
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));

import { lookupPostcode, saveHome, reverseLookupPostcode } from '../api/settingsApi.js';

const MORPETH = { postcode: 'NE61 1AA', placeName: 'Morpeth, Northumberland', latitude: 55.17, longitude: -1.69 };
const DURHAM = { postcode: 'DH1 3SS', placeName: 'Durham, County Durham', latitude: 54.776, longitude: -1.575 };
const SAVED = {
  role: 'LITE_USER', homePostcode: 'NE61 1AA', homePlaceName: null, homeLatitude: 55.17, homeLongitude: -1.69,
  postcodePromptDue: false,
};

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const mount = (props = {}) => {
  const onSaved = vi.fn();
  const onSkip = vi.fn();
  render(
    <PostcodeCapture
      heading="Where's home?"
      sub="Your postcode sets the sunrise and sunset times shown at the top of the app."
      primaryLabel="Save and continue"
      secondaryLabel="Skip for now"
      onSaved={onSaved}
      onSkip={onSkip}
      testIdPrefix="home"
      {...props}
    />,
  );
  return { onSaved, onSkip };
};

const typeAndLookUp = async (user, postcode = 'NE61 1AA') => {
  await user.type(screen.getByTestId('home-input'), postcode);
  await user.click(screen.getByTestId('home-lookup'));
};

/** Overrides the pointer media query for one test; restored in afterEach. */
const realMatchMedia = globalThis.matchMedia;
const withPointer = (fine) => {
  globalThis.matchMedia = (query) => ({
    matches: fine && query === '(pointer: fine)',
    media: query,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
  });
};

const installGeolocation = (getCurrentPosition) => {
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: { getCurrentPosition } });
};

describe('PostcodeCapture', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    globalThis.matchMedia = realMatchMedia;
    delete navigator.geolocation;
  });

  describe('the empty state (frame 1)', () => {
    it('shows the heading as an h2, the sub, the label, the placeholder and both buttons verbatim', () => {
      mount();

      expect(screen.getByRole('heading', { level: 2, name: "Where's home?" })).toBeInTheDocument();
      expect(screen.getByText('Your postcode sets the sunrise and sunset times shown at the top of the app.'))
        .toHaveClass('text-xs', 'text-plex-text-secondary');
      expect(screen.getByLabelText('Home postcode')).toBe(screen.getByTestId('home-input'));
      expect(screen.getByPlaceholderText('Enter UK postcode')).toBe(screen.getByTestId('home-input'));
      expect(screen.getByTestId('home-lookup')).toHaveTextContent('Look up');
      expect(screen.getByTestId('home-primary')).toHaveTextContent('Save and continue');
      expect(screen.getByTestId('home-secondary')).toHaveTextContent('Skip for now');
    });

    it('carries the task-4 attributes, never a numeric keypad', () => {
      mount();
      const input = screen.getByTestId('home-input');

      expect(input).toHaveAttribute('autocomplete', 'postal-code');
      expect(input).toHaveAttribute('autocapitalize', 'characters');
      expect(input).toHaveAttribute('autocorrect', 'off');
      expect(input).toHaveAttribute('spellcheck', 'false');
      expect(input).toHaveAttribute('inputmode', 'text');
      expect(input).toHaveAttribute('enterkeyhint', 'search');
      expect(input).not.toHaveAttribute('inputmode', 'numeric');
    });

    it('uses the shared .field style and nothing that outranks it; secondary-ink text buttons', () => {
      mount();
      const input = screen.getByTestId('home-input');

      expect(input).toHaveClass('field');
      expect(input).not.toHaveClass('field-error');
      expect(input).not.toHaveClass('text-sm');
      expect(input).not.toHaveClass('bg-plex-surface-light');
      expect(screen.getByTestId('home-lookup')).toHaveClass('btn-secondary');
      expect(screen.getByTestId('home-primary')).toHaveClass('btn-primary', 'w-full');
      const secondary = screen.getByTestId('home-secondary');
      expect(secondary).toHaveClass('text-plex-text-secondary');
      expect(secondary).not.toHaveClass('text-plex-text-muted');
    });

    it('shows the Pro line to every reader, with a PRO tag and no link', () => {
      mount();
      const line = screen.getByTestId('home-pro-line');

      expect(screen.getByTestId('pro-tag')).toHaveTextContent(/pro/i);
      expect(line).toHaveTextContent('Also gives you drive times from home to every location.');
      expect(line.querySelector('a')).toBeNull();
    });

    it('disables Look up and the primary until there is something to act on', async () => {
      const user = userEvent.setup();
      mount();
      expect(screen.getByTestId('home-lookup')).toBeDisabled();
      expect(screen.getByTestId('home-primary')).toBeDisabled();

      await user.type(screen.getByTestId('home-input'), '   ');
      expect(screen.getByTestId('home-lookup')).toBeDisabled();

      await user.type(screen.getByTestId('home-input'), 'NE61');
      expect(screen.getByTestId('home-lookup')).toBeEnabled();
      expect(screen.getByTestId('home-primary')).toBeDisabled();
    });

    it('prefixes every test id with the consumer\'s prefix', () => {
      mount({ testIdPrefix: 'prompt' });

      ['capture', 'input', 'lookup', 'primary', 'secondary', 'pro-line']
        .forEach((name) => expect(screen.getByTestId(`prompt-${name}`)).toBeInTheDocument());
      expect(screen.queryByTestId('home-input')).toBeNull();
      // The heading is named for the overlay's aria-labelledby.
      expect(document.getElementById('prompt-heading')).toHaveTextContent("Where's home?");
    });
  });

  describe('the lookup (frames 2 and 3)', () => {
    it('looks up the trimmed postcode, shows Looking up… while it is out, then the confirmation row', async () => {
      const user = userEvent.setup();
      const pending = deferred();
      lookupPostcode.mockReturnValue(pending.promise);
      mount();

      await user.type(screen.getByTestId('home-input'), '  NE61 1AA ');
      await user.click(screen.getByTestId('home-lookup'));

      expect(lookupPostcode).toHaveBeenCalledTimes(1);
      expect(lookupPostcode).toHaveBeenCalledWith('NE61 1AA');
      expect(screen.getByTestId('home-lookup')).toHaveTextContent('Looking up…');
      expect(screen.getByTestId('home-lookup')).toBeDisabled();

      await act(async () => { pending.resolve(MORPETH); });

      const found = screen.getByTestId('home-found');
      expect(found).toHaveClass('field');
      expect(found).toHaveTextContent('✓');
      expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth, Northumberland');
      expect(screen.getByTestId('home-found-postcode')).toHaveTextContent('NE61 1AA');
      expect(screen.getByTestId('home-found-postcode')).toHaveClass('font-mono', 'text-[11px]');
      expect(screen.getByTestId('home-change')).toHaveTextContent('Change');
      expect(screen.queryByTestId('home-input')).toBeNull();
      expect(screen.queryByTestId('home-lookup')).toBeNull();
      expect(screen.getByTestId('home-primary')).toBeEnabled();
      expect(screen.getByRole('group', { name: 'Home postcode' })).toBe(found);
      // The primary is described by the confirmation, so a screen reader landing on it hears the place.
      expect(screen.getByTestId('home-primary')).toHaveAttribute('aria-describedby', 'home-found');
    });

    it('Enter in the field runs the lookup, and a second Enter while it is out starts no second one', async () => {
      const user = userEvent.setup();
      const pending = deferred();
      lookupPostcode.mockReturnValue(pending.promise);
      mount();

      await user.type(screen.getByTestId('home-input'), 'NE61 1AA{Enter}{Enter}');

      expect(lookupPostcode).toHaveBeenCalledTimes(1);
      await act(async () => { pending.resolve(MORPETH); });
      expect(screen.getByTestId('home-found')).toBeInTheDocument();
    });

    it('Enter on a blank field runs nothing', async () => {
      const user = userEvent.setup();
      mount();

      await user.type(screen.getByTestId('home-input'), '{Enter}');

      expect(lookupPostcode).not.toHaveBeenCalled();
    });

    it('moves focus to the primary action when the lookup removes the field the reader was in', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      mount();

      await user.type(screen.getByTestId('home-input'), 'NE61 1AA{Enter}');

      await waitFor(() => expect(screen.getByTestId('home-primary')).toHaveFocus());
    });

    it('Change restores the field with its last value, focused, and drops the confirmation', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      mount();
      await typeAndLookUp(user, 'ne61 1aa');
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-change'));

      expect(screen.queryByTestId('home-found')).toBeNull();
      const input = screen.getByTestId('home-input');
      expect(input).toHaveValue('ne61 1aa');
      expect(input).toHaveFocus();
      expect(screen.getByTestId('home-primary')).toBeDisabled();
      expect(saveHome).not.toHaveBeenCalled();
    });

    it('shows Invalid postcode in the error token, marks the field, and keeps what was typed', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockRejectedValue({ response: { status: 400 } });
      mount();

      await typeAndLookUp(user, 'NE61 9ZZ');

      const error = await screen.findByTestId('home-error');
      expect(error).toHaveTextContent('Invalid postcode');
      expect(error).toHaveClass('text-plex-error', 'text-xs');
      expect(error).toHaveAttribute('role', 'alert');
      const input = screen.getByTestId('home-input');
      expect(input).toHaveClass('field', 'field-error');
      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(input).toHaveAttribute('aria-describedby', 'home-error');
      expect(input).toHaveValue('NE61 9ZZ');
      expect(input).toHaveFocus(); // the Look up button the reader pressed was disabled for the request
      expect(screen.getByTestId('home-primary')).toBeDisabled();
      expect(screen.getByTestId('home-lookup')).toBeEnabled();
    });

    it('any failure of the lookup reads Invalid postcode, an outage included', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockRejectedValue({ response: { status: 502 } });
      mount();

      await typeAndLookUp(user, 'NE61 1AA');

      expect(await screen.findByTestId('home-error')).toHaveTextContent('Invalid postcode');
    });

    it('a good lookup after a bad one clears the error', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockRejectedValueOnce(new Error('no')).mockResolvedValueOnce(MORPETH);
      mount();
      await typeAndLookUp(user, 'NE61 9ZZ');
      await screen.findByTestId('home-error');

      await user.click(screen.getByTestId('home-lookup'));

      await screen.findByTestId('home-found');
      expect(screen.queryByTestId('home-error')).toBeNull();
    });
  });

  describe('Save', () => {
    it('saves the looked-up postcode and coordinates with a null radius, exactly once, then notifies', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      saveHome.mockResolvedValue(SAVED);
      const { onSaved, onSkip } = mount();
      await typeAndLookUp(user);
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-primary'));

      expect(saveHome).toHaveBeenCalledTimes(1);
      expect(saveHome).toHaveBeenCalledWith('NE61 1AA', 55.17, -1.69, null);
      expect(saveHome.mock.calls[0]).toHaveLength(4);
      expect(onSaved).toHaveBeenCalledTimes(1);
      expect(onSkip).not.toHaveBeenCalled();
    });

    it('hands the consumer the server response with the looked-up place name put back on it', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      saveHome.mockResolvedValue(SAVED); // PUT /home does not geocode: homePlaceName is null
      const { onSaved } = mount();
      await typeAndLookUp(user);
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-primary'));

      expect(onSaved).toHaveBeenCalledWith({ ...SAVED, homePlaceName: 'Morpeth, Northumberland' });
    });

    it('prefers a place name the server did send', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      saveHome.mockResolvedValue({ ...SAVED, homePlaceName: 'Morpeth' });
      const { onSaved } = mount();
      await typeAndLookUp(user);
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-primary'));

      expect(onSaved.mock.calls[0][0].homePlaceName).toBe('Morpeth');
    });

    it('a double press sends one save', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      const pending = deferred();
      saveHome.mockReturnValue(pending.promise);
      mount();
      await typeAndLookUp(user);
      await screen.findByTestId('home-found');

      await user.dblClick(screen.getByTestId('home-primary'));

      expect(saveHome).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId('home-primary')).toBeDisabled();
      expect(screen.getByTestId('home-secondary')).toBeDisabled();
      await act(async () => { pending.resolve(SAVED); });
    });

    it('a failed save keeps the confirmation row, says so, and a retry saves again', async () => {
      const user = userEvent.setup();
      lookupPostcode.mockResolvedValue(MORPETH);
      saveHome.mockRejectedValueOnce(new Error('503')).mockResolvedValueOnce(SAVED);
      const { onSaved } = mount();
      await typeAndLookUp(user);
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-primary'));

      const failure = await screen.findByTestId('home-save-error');
      expect(failure).toHaveTextContent("Couldn't save. Try again.");
      expect(failure).toHaveClass('text-plex-error');
      expect(failure).toHaveAttribute('role', 'alert');
      expect(screen.getByTestId('home-found')).toBeInTheDocument();
      expect(screen.getByTestId('home-primary')).toBeEnabled();
      expect(screen.getByTestId('home-primary')).toHaveFocus();
      expect(onSaved).not.toHaveBeenCalled();

      await user.click(screen.getByTestId('home-primary'));

      await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
      expect(saveHome).toHaveBeenCalledTimes(2);
      expect(screen.queryByTestId('home-save-error')).toBeNull();
    });
  });

  describe('the secondary action', () => {
    it('calls onSkip once, saves nothing and looks nothing up', async () => {
      const user = userEvent.setup();
      const { onSkip, onSaved } = mount();

      await user.click(screen.getByTestId('home-secondary'));

      expect(onSkip).toHaveBeenCalledTimes(1);
      expect(saveHome).not.toHaveBeenCalled();
      expect(lookupPostcode).not.toHaveBeenCalled();
      expect(onSaved).not.toHaveBeenCalled();
    });

    it('holds both buttons while onSkip is out, so a second press cannot send a second dismissal', async () => {
      const user = userEvent.setup();
      const pending = deferred();
      const onSkip = vi.fn(() => pending.promise);
      mount({ onSkip });

      await user.dblClick(screen.getByTestId('home-secondary'));

      expect(onSkip).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId('home-secondary')).toBeDisabled();
      expect(screen.getByTestId('home-primary')).toBeDisabled();
      await act(async () => { pending.resolve(); });
      expect(screen.getByTestId('home-secondary')).toBeEnabled();
    });

    it('uses the consumer\'s label', () => {
      mount({ secondaryLabel: 'Not now', primaryLabel: 'Save' });

      expect(screen.getByTestId('home-secondary')).toHaveTextContent('Not now');
      expect(screen.getByTestId('home-primary')).toHaveTextContent('Save');
    });
  });

  describe('autofocus follows the pointer rule', () => {
    it('focuses the field under a fine pointer when asked', () => {
      withPointer(true);
      mount({ autoFocus: true });

      expect(screen.getByTestId('home-input')).toHaveFocus();
    });

    it('does not under a coarse pointer — a phone keyboard would cover the card', () => {
      withPointer(false);
      mount({ autoFocus: true });

      expect(screen.getByTestId('home-input')).not.toHaveFocus();
    });

    it('does not when not asked, even under a fine pointer', () => {
      withPointer(true);
      mount();

      expect(screen.getByTestId('home-input')).not.toHaveFocus();
    });
  });

  describe('Use my location', () => {
    it('is absent where the browser has no geolocation', () => {
      mount();

      expect(screen.queryByTestId('home-use-location')).toBeNull();
      expect(screen.queryByText('Use my location')).toBeNull();
    });

    it('is a text link in the gold ink under the field, on its own line', () => {
      installGeolocation(vi.fn());
      mount();

      const link = screen.getByTestId('home-use-location');
      expect(link).toHaveTextContent('Use my location');
      expect(link).toHaveClass('text-xs', 'text-plex-gold');
      expect(link.parentElement.contains(screen.getByTestId('home-input'))).toBe(false);
    });

    it('lands the nearest postcode in the same confirmation row, and Change restores it as the field value', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((ok) => ok({ coords: { latitude: 54.776, longitude: -1.575 } })));
      reverseLookupPostcode.mockResolvedValue(DURHAM);
      mount();

      await user.click(screen.getByTestId('home-use-location'));

      await screen.findByTestId('home-found');
      expect(reverseLookupPostcode).toHaveBeenCalledWith(54.776, -1.575);
      expect(screen.getByTestId('home-place')).toHaveTextContent('Durham, County Durham');
      expect(screen.getByTestId('home-found-postcode')).toHaveTextContent('DH1 3SS');
      expect(screen.getByTestId('home-primary')).toBeEnabled();
      expect(lookupPostcode).not.toHaveBeenCalled();

      await user.click(screen.getByTestId('home-change'));
      expect(screen.getByTestId('home-input')).toHaveValue('DH1 3SS');
    });

    it('saves a located postcode exactly as a typed one', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((ok) => ok({ coords: { latitude: 54.776, longitude: -1.575 } })));
      reverseLookupPostcode.mockResolvedValue(DURHAM);
      saveHome.mockResolvedValue(SAVED);
      mount();
      await user.click(screen.getByTestId('home-use-location'));
      await screen.findByTestId('home-found');

      await user.click(screen.getByTestId('home-primary'));

      expect(saveHome).toHaveBeenCalledWith('DH1 3SS', 54.776, -1.575, null);
    });

    it('reads Locating… while out and holds itself — but not Look up or Skip, so a silent browser cannot trap the reader', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn()); // never answers
      const { onSkip } = mount();
      await user.type(screen.getByTestId('home-input'), 'NE61 1AA');

      await user.click(screen.getByTestId('home-use-location'));

      expect(screen.getByTestId('home-use-location')).toHaveTextContent('Locating…');
      expect(screen.getByTestId('home-use-location')).toBeDisabled();
      expect(screen.getByTestId('home-lookup')).toBeEnabled();
      expect(screen.getByTestId('home-secondary')).toBeEnabled();

      await user.click(screen.getByTestId('home-secondary'));
      expect(onSkip).toHaveBeenCalledTimes(1);
    });

    it('a typed lookup started while locating supersedes it: the late location result is dropped', async () => {
      const user = userEvent.setup();
      let deliver;
      installGeolocation(vi.fn((ok) => { deliver = ok; }));
      reverseLookupPostcode.mockResolvedValue(DURHAM);
      lookupPostcode.mockResolvedValue(MORPETH);
      mount();
      await user.click(screen.getByTestId('home-use-location'));
      await user.type(screen.getByTestId('home-input'), 'NE61 1AA');
      await user.click(screen.getByTestId('home-lookup'));
      await screen.findByTestId('home-found');

      await act(async () => { deliver({ coords: { latitude: 54.776, longitude: -1.575 } }); });

      expect(screen.getByTestId('home-place')).toHaveTextContent('Morpeth, Northumberland');
    });

    it('a location error does NOT mark the field invalid — nothing the reader typed was wrong', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((_ok, fail) => fail({ code: 1 })));
      mount();

      await user.click(screen.getByTestId('home-use-location'));

      await screen.findByTestId('home-error');
      const input = screen.getByTestId('home-input');
      expect(input).not.toHaveClass('field-error');
      expect(input).not.toHaveAttribute('aria-invalid');
      expect(input).toHaveAttribute('aria-describedby', 'home-error');
      expect(input).toHaveFocus();
    });

    it('a refused permission shows its sentence IN PLACE of the error line, and the field is no longer marked', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((_ok, fail) => fail({ code: 1 })));
      lookupPostcode.mockRejectedValue(new Error('no'));
      mount();
      await typeAndLookUp(user, 'NE61 9ZZ');
      expect(await screen.findByTestId('home-error')).toHaveTextContent('Invalid postcode');

      await user.click(screen.getByTestId('home-use-location'));

      const error = await screen.findByTestId('home-error');
      expect(error).toHaveTextContent('Location access is off. Enter your postcode instead.');
      expect(screen.queryByText('Invalid postcode')).toBeNull();
      expect(screen.getByTestId('home-input')).not.toHaveClass('field-error');
    });

    it('a typed lookup after a location error clears the location sentence', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((_ok, fail) => fail({ code: 3 })));
      lookupPostcode.mockResolvedValue(MORPETH);
      mount();
      await user.click(screen.getByTestId('home-use-location'));
      expect(await screen.findByTestId('home-error'))
        .toHaveTextContent("Couldn't get your location. Enter your postcode instead.");

      await typeAndLookUp(user);

      await screen.findByTestId('home-found');
      expect(screen.queryByTestId('home-error')).toBeNull();
    });

    it('no UK postcode at the location reads its own sentence', async () => {
      const user = userEvent.setup();
      installGeolocation(vi.fn((ok) => ok({ coords: { latitude: 48.85, longitude: 2.35 } })));
      reverseLookupPostcode.mockRejectedValue({ response: { status: 400 } });
      mount();

      await user.click(screen.getByTestId('home-use-location'));

      expect(await screen.findByTestId('home-error'))
        .toHaveTextContent("We couldn't find a UK postcode for your location.");
      expect(screen.queryByTestId('home-found')).toBeNull();
    });
  });
});
