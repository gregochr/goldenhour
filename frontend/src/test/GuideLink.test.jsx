import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GuideLink, { GuideWhy } from '../components/guide/GuideLink.jsx';
import ProUpsellDoor from '../components/shared/ProUpsell.jsx';
import { GUIDE_SECTIONS } from '../utils/guide.js';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

beforeEach(() => mockOpenGuide.mockClear());

describe('GuideLink', () => {
  it('is a labelled button that opens the section its key names', () => {
    render(<GuideLink section="mapFilters" label="How the map filters work" />);
    const link = screen.getByTestId('guide-link');
    expect(link).toHaveAttribute('aria-label', 'How the map filters work');
    expect(link).toHaveAttribute('title', 'How the map filters work');
    expect(link).toHaveAttribute('type', 'button');
    fireEvent.click(link);
    expect(mockOpenGuide).toHaveBeenCalledTimes(1);
    const [page, section, options] = mockOpenGuide.mock.calls[0];
    expect([page, section]).toEqual([GUIDE_SECTIONS.mapFilters.page, GUIDE_SECTIONS.mapFilters.section]);
    // Safari does not focus a clicked button, so the door names itself as the focus-return target.
    expect(options.returnFocus()).toBe(link);
  });

  it('stops the press, so a card or row around it never sees the click', () => {
    const onCard = vi.fn();
    render(
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
      <div onClick={onCard}><GuideLink section="lens" label="L" /></div>,
    );
    fireEvent.click(screen.getByTestId('guide-link'));
    expect(onCard).not.toHaveBeenCalled();
    expect(mockOpenGuide).toHaveBeenCalledTimes(1);
  });

  it('opens nothing for a key that is not in GUIDE_SECTIONS', () => {
    render(<GuideLink section="nope" label="L" />);
    fireEvent.click(screen.getByTestId('guide-link'));
    expect(mockOpenGuide).not.toHaveBeenCalled();
  });
});

describe('GuideWhy', () => {
  it('reads "What does this do?" and opens its section', () => {
    render(<GuideWhy section="subRegions" />);
    const why = screen.getByTestId('guide-why');
    expect(why).toHaveTextContent('What does this do?');
    fireEvent.click(why);
    expect(mockOpenGuide).toHaveBeenCalledWith('settings', 'sub-regions', expect.any(Object));
  });

  it('opens a bare page when given a page and no section (Ask)', () => {
    render(<GuideWhy page="ask-photocast" />);
    fireEvent.click(screen.getByTestId('guide-why'));
    expect(mockOpenGuide).toHaveBeenCalledWith('ask-photocast', '', expect.any(Object));
  });

  it('stops the press and passes the caller\'s focus-return target', () => {
    const onCard = vi.fn();
    const target = document.createElement('button');
    render(
      // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
      <div onClick={onCard}><GuideWhy section="lens" returnFocus={() => target} /></div>,
    );
    fireEvent.click(screen.getByTestId('guide-why'));
    expect(onCard).not.toHaveBeenCalled();
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(target);
  });
});

describe('ProUpsellDoor guide prop', () => {
  const door = (guide) => (
    <ProUpsellDoor label="Best bets" heading="H" body="B" testIdPrefix="d" guide={guide} />
  );

  it('draws no door in the popover without the prop', () => {
    render(door(undefined));
    fireEvent.click(screen.getByTestId('d-trigger'));
    expect(screen.getByTestId('d-popover')).toBeInTheDocument();
    expect(screen.queryByTestId('d-guide')).toBeNull();
  });

  it('draws one at the end of the popover body, and returns focus to the trigger rather than itself', () => {
    render(door({ section: 'bestBet' }));
    const trigger = screen.getByTestId('d-trigger');
    fireEvent.click(trigger);
    fireEvent.click(screen.getByTestId('d-guide'));
    expect(mockOpenGuide).toHaveBeenCalledWith('at-a-glance', 'bestbet', expect.any(Object));
    // The popover closes on focus-out, so the link is gone by the time the guide closes.
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(trigger);
  });
});
