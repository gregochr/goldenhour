import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { SubRegionActionButton, SubRegionStatusLine } from '../components/subregions/SubRegionActions.jsx';
import { AllDoor, PlaceDoor, RegionDoor } from '../components/subregions/SubRegionDoors.jsx';
import {
  subregionsAllBody, subregionsPlaceBody, subregionsRegionBody, subregionsRegionLabel,
} from '../components/shared/ProUpsell.jsx';
import {
  FIRST_CHOICE, FREE_WEEK, LOCKED_FULL, LOCKED_ROOM, OPEN, OVER_LIMIT, PRO,
} from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The shared S4 status read, its button and line, and the three sub-regions doors
 * (sub-regions-plan §11 S5a-3, decisions 9 and 11).
 *
 * <p><b>What breaks if these fail:</b> a Pro, Admin or flag-off reader's Map starts asking for sub-region
 * state; a status shows the wrong verb; the status is cached or never refetched after a save; a door carries a
 * link, the word "upgrade", a missing pill, or a hard-coded count; one Escape closes the host with the door.
 */

function Surface({ onOpen = vi.fn(), enabled = true, version = 0 }) {
  return (
    <SubRegionStatusProvider enabled={enabled} version={version} onOpen={onOpen}>
      <SubRegionStatusLine />
      <SubRegionActionButton />
    </SubRegionStatusProvider>
  );
}

describe('SubRegionStatusProvider: the one shared read', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('reads nothing while the body is not shaped (Pro, Admin, flag off)', async () => {
    render(<Surface enabled={false} />);

    await Promise.resolve();
    expect(getSubRegions).not.toHaveBeenCalled();
    expect(screen.queryByTestId('wf-subregion-action')).toBeNull();
    expect(screen.queryByTestId('wf-subregion-status-line')).toBeNull();
  });

  it('reads once when shaped, and again when the version moves (a save in Settings)', async () => {
    getSubRegions.mockResolvedValueOnce(OPEN).mockResolvedValueOnce(LOCKED_FULL);
    const { rerender } = render(<Surface version={0} />);
    expect(await screen.findByTestId('wf-subregion-action')).toHaveTextContent('Change your sub-regions');
    expect(getSubRegions).toHaveBeenCalledTimes(1);

    rerender(<Surface version={1} />);

    await waitFor(() => expect(getSubRegions).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId('wf-subregion-action')).toBeNull());
    expect(screen.getByTestId('wf-subregion-status-line'))
      .toHaveTextContent('You can next change your sub-regions on 25 November.');
  });

  it('draws nothing when the read fails', async () => {
    getSubRegions.mockRejectedValue(new Error('boom'));
    render(<Surface />);

    await waitFor(() => expect(getSubRegions).toHaveBeenCalled());
    expect(screen.queryByTestId('wf-subregion-action')).toBeNull();
  });
});

describe('the status button and the line, one case per status', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it.each([
    ['FIRST_CHOICE', FIRST_CHOICE, 'Choose your sub-regions'],
    ['FREE_UNTIL', FREE_WEEK, 'Change your sub-regions'],
    ['OPEN', OPEN, 'Change your sub-regions'],
    ['LOCKED under the limit', LOCKED_ROOM, 'Add a sub-region'],
    ['overLimit', OVER_LIMIT, 'Change your sub-regions'],
  ])('%s offers "%s" and opens Settings on press', async (_name, state, label) => {
    getSubRegions.mockResolvedValue(state);
    const onOpen = vi.fn();
    render(<Surface onOpen={onOpen} />);

    const button = await screen.findByRole('button', { name: label });
    expect(screen.getByTestId('wf-subregion-status-line')).toBeInTheDocument();
    fireEvent.click(button);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('LOCKED at the limit has no button and the server\'s date, in UK form', async () => {
    getSubRegions.mockResolvedValue(LOCKED_FULL);
    render(<Surface />);

    expect(await screen.findByTestId('wf-subregion-status-line'))
      .toHaveTextContent('You can next change your sub-regions on 25 November.');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('NOT_APPLICABLE draws nothing at all', async () => {
    getSubRegions.mockResolvedValue(PRO);
    render(<Surface />);

    await waitFor(() => expect(getSubRegions).toHaveBeenCalled());
    expect(screen.queryByTestId('wf-subregion-action')).toBeNull();
    expect(screen.queryByTestId('wf-subregion-status-line')).toBeNull();
  });

  it('draws no button with no way to open Settings (a dead control is worse than none)', async () => {
    getSubRegions.mockResolvedValue(OPEN);
    render(<Surface onOpen={null} />);

    await screen.findByTestId('wf-subregion-status-line');
    expect(screen.queryByTestId('wf-subregion-action')).toBeNull();
  });

  it('an explicit opener wins over the provider\'s', async () => {
    getSubRegions.mockResolvedValue(OPEN);
    const providers = vi.fn();
    const mine = vi.fn();
    render(
      <SubRegionStatusProvider enabled onOpen={providers}>
        <SubRegionActionButton onOpen={mine} />
      </SubRegionStatusProvider>,
    );

    fireEvent.click(await screen.findByTestId('wf-subregion-action'));

    expect(mine).toHaveBeenCalledTimes(1);
    expect(providers).not.toHaveBeenCalled();
  });
});

describe('the door copy: served counts, singulars, the applied pick count', () => {
  it('"Every sub-region" names the roster, the places the picks cover and the picks applied, not the limit', () => {
    expect(subregionsAllBody({
      total: 180, subRegionCount: 24, allowed: 32, picked: 3,
    })).toBe('Pro rates all 180 places across the 24 sub-regions. You see ratings for 32 places in your three sub-regions.');
  });

  it('is singular at one pick and one place', () => {
    expect(subregionsAllBody({
      total: 180, subRegionCount: 24, allowed: 1, picked: 1,
    })).toBe('Pro rates all 180 places across the 24 sub-regions. You see ratings for 1 place in your one sub-region.');
  });

  it('is singular for a roster of one sub-region', () => {
    expect(subregionsAllBody({
      total: 5, subRegionCount: 1, allowed: 2, picked: 1,
    })).toMatch(/across the 1 sub-region\. /);
  });

  it('the region door names the places, the sub-regions and two example places', () => {
    expect(subregionsRegionBody({
      regionName: 'The Lake District', places: 62, subRegionCount: 5, examples: ['Castlerigg', 'Blea Tarn'],
    })).toBe('Pro rates all 62 places across the five sub-regions of the Lake District, Castlerigg and Blea Tarn among them, for every sunrise and sunset.');
  });

  it('the region door is singular, takes one example when there is one, and none when there are none', () => {
    expect(subregionsRegionBody({
      regionName: 'Solo', places: 1, subRegionCount: 1, examples: ['A Place'],
    })).toBe('Pro rates all 1 place across the one sub-region of Solo, A Place among them, for every sunrise and sunset.');
    expect(subregionsRegionBody({
      regionName: 'Solo', places: 4, subRegionCount: 2, examples: [],
    })).toBe('Pro rates all 4 places across the two sub-regions of Solo, for every sunrise and sunset.');
  });

  it('a region name takes "the" only when it has one', () => {
    expect(subregionsRegionLabel('The Lake District')).toBe('Ratings across the Lake District');
    expect(subregionsRegionLabel('Northumberland & Tyneside')).toBe('Ratings across Northumberland & Tyneside');
  });

  it('the place door names the place twice', () => {
    expect(subregionsPlaceBody('Malham Cove')).toBe(
      'Pro gives every place in every sub-region its star rating and verdict for each window, Malham Cove included.',
    );
  });
});

describe.each([
  ['the region door', 'wf-subregions-region-upsell', () => (
    <RegionDoor regionName="Northumberland & Tyneside" places={44} subRegionCount={4} />
  )],
  ['the every-sub-region door', 'wf-subregions-all-upsell', () => (
    <AllDoor total={180} subRegionCount={24} allowed={32} picked={3} />
  )],
  ['the place door', 'wf-subregions-place-upsell', () => <PlaceDoor place="Malham Cove" />],
])('%s', (_name, prefix, makeDoor) => {
  function renderInDialog() {
    const outerEscape = vi.fn();
    render(
      <SubRegionStatusProvider enabled={false}>
        {/* A host dialog: an Escape that reaches it would close it. */}
        {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
        <div role="dialog" aria-label="host" tabIndex={-1} onKeyDown={(e) => { if (e.key === 'Escape') outerEscape(); }}>
          {makeDoor()}
        </div>
      </SubRegionStatusProvider>,
    );
    return { outerEscape };
  }

  it('has the Pro pill inside its trigger, and is closed to start', () => {
    renderInDialog();

    const trigger = screen.getByTestId(`${prefix}-trigger`);
    expect(within(trigger).getByText('Pro')).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens a non-modal popover with a heading, a body and "Not now": no link, no "upgrade"', () => {
    const { container } = render(
      <SubRegionStatusProvider enabled={false}>{makeDoor()}</SubRegionStatusProvider>,
    );

    fireEvent.click(screen.getByTestId(`${prefix}-trigger`));

    const popover = screen.getByTestId(`${prefix}-popover`);
    expect(popover).not.toHaveAttribute('aria-modal');
    expect(screen.getByTestId(`${prefix}-heading`)).toBeInTheDocument();
    expect(screen.getByTestId(`${prefix}-body`)).toBeInTheDocument();
    expect(screen.getByTestId(`${prefix}-dismiss`)).toHaveTextContent('Not now');
    expect(container.querySelector('a')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(container.textContent).not.toMatch(/upgrade/i);
  });

  it('closes on one Escape, and that Escape does not reach the host dialog', () => {
    const { outerEscape } = renderInDialog();
    fireEvent.click(screen.getByTestId(`${prefix}-trigger`));
    expect(screen.getByTestId(`${prefix}-popover`)).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId(`${prefix}-heading`), { key: 'Escape' });

    expect(screen.queryByTestId(`${prefix}-popover`)).toBeNull();
    expect(outerEscape).not.toHaveBeenCalled();
    expect(screen.getByTestId(`${prefix}-trigger`)).toHaveFocus();
  });
});

describe('the region door reads its example places from the S4 options', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('names the first place of the region\'s first two sub-regions once the status has loaded', async () => {
    getSubRegions.mockResolvedValue(FIRST_CHOICE);
    render(
      <SubRegionStatusProvider enabled>
        <RegionDoor regionName="Northumberland & Tyneside" places={44} subRegionCount={4} />
      </SubRegionStatusProvider>,
    );
    await waitFor(() => expect(getSubRegions).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });

    fireEvent.click(screen.getByTestId('wf-subregions-region-upsell-trigger'));

    expect(screen.getByTestId('wf-subregions-region-upsell-body'))
      .toHaveTextContent('Berwick Pier and Bamburgh Castle among them');
    expect(screen.getByTestId('wf-subregions-region-upsell-body'))
      .toHaveTextContent('Pro rates all 44 places across the four sub-regions of Northumberland & Tyneside');
  });
});
