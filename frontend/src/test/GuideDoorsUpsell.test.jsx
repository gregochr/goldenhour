import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AskEmptyState from '../components/ask/AskEmptyState.jsx';
import AskUpsellPopover from '../components/ask/AskUpsell.jsx';
import TideUpsellPopover, { TideUpsellContent } from '../components/map/TideUpsell.jsx';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

/**
 * The G3 doors on the upsell popovers that are not `ProUpsellDoor`s (the tide panel's and Ask's), and on Ask's
 * empty state. What breaks if these fail: a Lite reader at a lock has no way to learn what it is for, a popover
 * door returns focus to a node that went with the popover, or a Pro reader sees a door that is only for the lock.
 */
beforeEach(() => mockOpenGuide.mockClear());

describe('Ask empty state', () => {
  const ask = (allowance) => ({ allowance });

  it('puts a ? beside the allowance line that opens ask-photocast#typed', () => {
    render(
      <AskEmptyState
        questions={[]}
        runLabel={null}
        ask={ask({ loaded: true, enabled: true, left: 2, limit: 3, typedAvailable: true })}
        onOpen={vi.fn()}
      />,
    );
    const link = screen.getByTestId('guide-link');
    expect(link).toHaveAccessibleName('How Ask questions work');
    expect(screen.getByTestId('ask-allowance').parentElement).toBe(link.parentElement);
    fireEvent.click(link);
    expect(mockOpenGuide).toHaveBeenCalledWith('ask-photocast', 'typed', expect.any(Object));
  });

  it('still draws the ? when no allowance is known yet', () => {
    render(
      <AskEmptyState questions={[]} ask={ask({ loaded: false, enabled: null })} onOpen={vi.fn()} />,
    );
    expect(screen.getByTestId('guide-link')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-allowance')).toBeNull();
  });
});

describe('Ask upsell popover', () => {
  function popover() {
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    const triggerRef = { current: trigger };
    const rootRef = { current: null };
    render(
      <AskUpsellPopover
        id="ask-up"
        rootRef={rootRef}
        triggerRef={triggerRef}
        onClose={vi.fn()}
        onFocusAway={vi.fn()}
        foreignOpen={() => false}
        placement="field"
      />,
    );
    return trigger;
  }

  it('offers "What does this do?" that opens the Ask page and returns focus to the trigger, not to itself', () => {
    const trigger = popover();
    fireEvent.click(screen.getByTestId('wf-ask-upsell-guide'));
    expect(mockOpenGuide).toHaveBeenCalledWith('ask-photocast', '', expect.any(Object));
    // The popover closes on focus-out, so the link is gone by the time the guide closes.
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(trigger);
  });
});

describe('Tide upsell', () => {
  it('the popover offers "What does this do?" for map#tide and returns focus to the door that opened it', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    render(
      <TideUpsellPopover
        id="tide-up"
        rootRef={{ current: null }}
        onClose={vi.fn()}
        onFocusAway={vi.fn()}
        doorSelector="[data-tide-door]"
        returnFocus={() => opener}
      />,
    );
    fireEvent.click(screen.getByTestId('wf-tide-upsell-guide'));
    expect(mockOpenGuide).toHaveBeenCalledWith('map', 'tide', expect.any(Object));
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(opener);
  });

  it('the phone peek section\'s inline copy offers the same door, returning focus to the door itself', () => {
    render(<TideUpsellContent headingId="h" testIdPrefix="wf-map-peek-tide-upsell" />);
    const why = screen.getByTestId('wf-map-peek-tide-upsell-guide');
    fireEvent.click(why);
    expect(mockOpenGuide).toHaveBeenCalledWith('map', 'tide', expect.any(Object));
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(why);
  });
});
