import React, { useState } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PostcodePromptOverlay from '../components/auth/PostcodePromptOverlay.jsx';
import SubRegionSignInPrompt from '../components/subregions/SubRegionSignInPrompt.jsx';
import useBackLayer from '../hooks/useBackLayer.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';
import { FIRST_CHOICE, makeState } from './subRegionFixtures.js';

vi.mock('../api/settingsApi.js', () => ({
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  reverseLookupPostcode: vi.fn(),
}));
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));

import { lookupPostcode, saveHome } from '../api/settingsApi.js';
import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';

/**
 * Back on the first-run prompts (back-closes-dialogs-plan.md, PR 3): once the reader has interacted, Back is
 * absorbed while the postcode prompt or the sub-region sign-in prompt is open, and nothing beneath closes.
 * Before any activation nothing is pushed, so Back leaves the page as it does today.
 */

const MORPETH = { postcode: 'NE61 1AA', placeName: 'Morpeth', latitude: 55.17, longitude: -1.69 };

let b;
const setActivation = (on) => {
  Object.defineProperty(globalThis.navigator, 'userActivation', {
    value: { isActive: on }, configurable: true, writable: true,
  });
};

/** An ordinary closing layer under the prompt, so "closes nothing beneath it" is observable. */
function Beneath({ onBack }) {
  useBackLayer({ open: true, onBack });
  return null;
}

function PostcodeHost({ onBack }) {
  const [open, setOpen] = useState(true);
  return (
    <div>
      <Beneath onBack={onBack} />
      {open && <PostcodePromptOverlay onSaved={() => setOpen(false)} onNotNow={() => setOpen(false)} />}
    </div>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  b = installBackBrowser();
  setActivation(false);
});

afterEach(() => {
  delete globalThis.navigator.userActivation;
  uninstallBackBrowser();
});

/** Makes the page "active" and clicks the prompt's own heading (inert to every exit). */
async function interact(user, name) {
  setActivation(true);
  await user.click(screen.getByRole('heading', { level: 2, name }));
  await b.settle();
}

describe('PostcodePromptOverlay Back guard', () => {
  it('pushes nothing before any activation', async () => {
    const onBack = vi.fn();
    render(<PostcodeHost onBack={onBack} />);
    await b.settle();

    expect(screen.getByTestId('postcode-prompt')).toBeInTheDocument();
    // Only the ordinary layer beneath registered; the prompt's guard is still waiting.
    expect(__inspectForTests().layers).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('listens on the prompt itself: a click outside it, even with activation live, registers nothing', async () => {
    render(<PostcodeHost onBack={vi.fn()} />);
    await b.settle();

    setActivation(true);
    fireEvent.click(document.body);
    await b.settle();

    expect(__inspectForTests().layers).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('after a click inside it, Back keeps it open and closes nothing beneath it', async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    render(<PostcodeHost onBack={onBack} />);
    await interact(user, 'Set your home postcode');
    expect(__inspectForTests().layers).toBe(2);

    await b.back();

    expect(screen.getByTestId('postcode-prompt')).toBeInTheDocument();
    expect(onBack).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(2);
    // Absorbed, not popped: the entry was pushed back, so a second Back is absorbed too.
    expect(__inspectForTests().committedDepth).toBe(2);
    await b.back();
    expect(__inspectForTests().committedDepth).toBe(2);
    expect(onBack).not.toHaveBeenCalled();
  });

  it('Not now releases the guard', async () => {
    const user = userEvent.setup();
    render(<PostcodeHost onBack={() => {}} />);
    await interact(user, 'Set your home postcode');

    expect(__inspectForTests().committedDepth).toBe(2);

    await user.click(screen.getByTestId('prompt-secondary'));
    await b.settle();

    expect(screen.queryByTestId('postcode-prompt')).toBeNull();
    expect(__inspectForTests().layers).toBe(1);
    expect(__inspectForTests().committedDepth).toBe(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
  });

  it('Save releases the guard', async () => {
    const user = userEvent.setup();
    lookupPostcode.mockResolvedValue(MORPETH);
    saveHome.mockResolvedValue({ homePostcode: 'NE61 1AA', homePlaceName: null, postcodePromptDue: false });
    render(<PostcodeHost onBack={() => {}} />);
    await interact(user, 'Set your home postcode');

    expect(__inspectForTests().committedDepth).toBe(2);
    await user.type(screen.getByTestId('prompt-input'), 'NE61 1AA');
    await user.click(screen.getByTestId('prompt-lookup'));
    await user.click(await screen.findByTestId('prompt-primary'));
    await b.settle();

    expect(screen.queryByTestId('postcode-prompt')).toBeNull();
    expect(__inspectForTests().layers).toBe(1);
    expect(__inspectForTests().committedDepth).toBe(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
  });
});

describe('SubRegionSignInPrompt Back guard', () => {
  const renderPrompt = async (onBack) => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    render(<><Beneath onBack={onBack} /><SubRegionSignInPrompt userRole="LITE_USER" /></>);
    await screen.findByRole('dialog', { name: 'Choose your sub-regions' });
    await b.settle();
  };

  it('pushes nothing before any activation', async () => {
    await renderPrompt(vi.fn());

    expect(__inspectForTests().layers).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('listens on the prompt itself: a click outside it, even with activation live, registers nothing', async () => {
    await renderPrompt(vi.fn());

    setActivation(true);
    fireEvent.click(document.body);
    await b.settle();

    expect(__inspectForTests().layers).toBe(1);
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('after a click inside it, Back keeps it open and closes nothing beneath it', async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    await renderPrompt(onBack);
    await interact(user, 'Choose your sub-regions');
    expect(__inspectForTests().layers).toBe(2);

    await b.back();

    expect(screen.getByRole('dialog', { name: 'Choose your sub-regions' })).toBeInTheDocument();
    expect(onBack).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(2);
    // Absorbed, not popped: the entry was pushed back, so a second Back is absorbed too.
    expect(__inspectForTests().committedDepth).toBe(2);
    await b.back();
    expect(__inspectForTests().committedDepth).toBe(2);
    expect(onBack).not.toHaveBeenCalled();
  });

  it('Continue releases the guard', async () => {
    const user = userEvent.setup();
    saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13, 20], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
    await renderPrompt(vi.fn());
    await interact(user, 'Choose your sub-regions');

    expect(__inspectForTests().committedDepth).toBe(2);

    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await screen.findByTestId('sub-region-toast');
    await act(async () => { await b.settle(); });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(__inspectForTests().layers).toBe(1);
    expect(__inspectForTests().committedDepth).toBe(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
  });
});
