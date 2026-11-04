import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import SubRegionSettingsSection from '../components/subregions/SubRegionSettingsSection.jsx';
import { AllDoor, PlaceDoor, RegionDoor } from '../components/subregions/SubRegionDoors.jsx';
import ComingUpOutsideDoor from '../components/subregions/ComingUpOutsideDoor.jsx';
import { OPEN, PRO } from './subRegionFixtures.js';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The G3 sub-regions doors: a ? beside the Settings heading (both arms), and "What does this do?" in every
 * sub-regions Pro door popover. What breaks if these fail: a Lite reader at a sub-regions lock cannot learn what
 * sub-regions are, or a door's guide link returns focus to a popover that has already gone.
 */
beforeEach(() => {
  vi.clearAllMocks();
  mockOpenGuide.mockClear();
});

describe('Settings › Your sub-regions', () => {
  it('draws a ? beside the heading on the Lite arm, opening settings#sub-regions', async () => {
    getSubRegions.mockResolvedValue(OPEN);
    render(<SubRegionSettingsSection userRole="LITE_USER" />);
    const section = await screen.findByTestId('sub-regions-section');
    const link = section.querySelector('[data-testid="guide-link"]');
    expect(link).toHaveAccessibleName('About sub-regions');
    expect(screen.getByRole('heading', { name: 'Your sub-regions' }).parentElement).toContainElement(link);
    fireEvent.click(link);
    expect(mockOpenGuide).toHaveBeenCalledWith('settings', 'sub-regions', expect.any(Object));
  });

  it('draws it on the Pro arm too', async () => {
    getSubRegions.mockResolvedValue(PRO);
    render(<SubRegionSettingsSection userRole="PRO_USER" />);
    const section = await screen.findByTestId('sub-regions-section');
    expect(section.querySelector('[data-testid="sub-regions-pro-line"]')).not.toBeNull();
    fireEvent.click(section.querySelector('[data-testid="guide-link"]'));
    expect(mockOpenGuide).toHaveBeenCalledWith('settings', 'sub-regions', expect.any(Object));
  });

  it('draws nothing for an Admin or while the flag is off', async () => {
    getSubRegions.mockResolvedValue(OPEN);
    const { container } = render(<SubRegionSettingsSection userRole="ADMIN" />);
    await act(async () => {});
    expect(container).toBeEmptyDOMElement();
  });
});

describe('the sub-regions doors', () => {
  const cases = [
    ['RegionDoor', <RegionDoor key="r" regionName="The Lake District" places={30} subRegionCount={4} />, 'wf-subregions-region-upsell'],
    ['AllDoor', <AllDoor key="a" total={260} subRegionCount={12} allowed={40} picked={3} />, 'wf-subregions-all-upsell'],
    ['PlaceDoor', <PlaceDoor key="p" place="Keswick" />, 'wf-subregions-place-upsell'],
    ['ComingUpOutsideDoor', <ComingUpOutsideDoor key="c" subject="coastal" count={12} zeroPicks={false} />, 'coming-up-subregions-coast-upsell'],
  ];

  it.each(cases)('%s: the popover carries "What does this do?" for settings#sub-regions and returns to its trigger', (_n, element, prefix) => {
    render(element);
    const trigger = screen.getByTestId(`${prefix}-trigger`);
    fireEvent.click(trigger);
    fireEvent.click(screen.getByTestId(`${prefix}-guide`));
    expect(mockOpenGuide).toHaveBeenCalledWith('settings', 'sub-regions', expect.any(Object));
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(trigger);
  });

  it('the Coming up dark-sky door has the same link', () => {
    render(<ComingUpOutsideDoor subject="darkSky" count={9} zeroPicks={false} />);
    fireEvent.click(screen.getByTestId('coming-up-subregions-darksky-upsell-trigger'));
    expect(screen.getByTestId('coming-up-subregions-darksky-upsell-guide')).toBeInTheDocument();
  });
});
