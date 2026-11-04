/**
 * The Lite aurora Pro door inside the phone's Windows section (docs/engineering/aurora-pro-door-plan.md §2.2).
 *
 * <p><b>What breaks if these fail:</b> the door joins the listbox, lands after the topics strip instead of
 * before it, or a reader who gets no door sees a different sheet from the one they had.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import { MapPeekWindowsSection } from '../components/map/MapPeekSheet.jsx';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

const ROWS = [
  {
    id: 'a', kind: 'solar', eventType: 'SUNSET', dayLabel: 'Tonight', label: 'Tonight sunset', time: '20:14',
  },
];
const DOOR = { label: 'Aurora tonight' };
const TOPICS = <div data-testid="topics-probe">topics</div>;

const draw = (props = {}) => render(
  <MapPeekWindowsSection
    heading="H"
    rows={ROWS}
    onSelect={() => {}}
    onOpenDrilldown={() => {}}
    topics={TOPICS}
    {...props}
  />,
);
const follows = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);

describe('MapPeekWindowsSection — the Lite aurora door', () => {
  it('sits after the list and its drilldown row and BEFORE the topics strip, outside the listbox', () => {
    draw({ auroraDoor: DOOR });

    const wrapper = screen.getByTestId('wf-map-peek-aurora-door');
    const listbox = screen.getByRole('listbox');
    expect(listbox).not.toContainElement(wrapper);
    expect(follows(listbox, wrapper)).toBe(true);
    expect(follows(screen.getByTestId('wf-map-peek-drilldown'), wrapper)).toBe(true);
    expect(follows(wrapper, screen.getByTestId('topics-probe'))).toBe(true);
    expect(within(listbox).getAllByRole('option')).toHaveLength(ROWS.length);
  });

  it('names its trigger with the label and "Pro", and carries the inline modifier', () => {
    draw({ auroraDoor: DOOR });

    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
    expect(screen.getByTestId('wf-aurora-upsell-door')).toHaveClass('wf-pro-upsell-door--inline');
  });

  it('without the prop (Pro, Admin) no door is drawn and the section is what it was', () => {
    draw();

    expect(screen.queryByTestId('wf-map-peek-aurora-door')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-windows').innerHTML).not.toMatch(/aurora-door|wf-pro-upsell/);
    // Only the drilldown is a plain button (the row is an option).
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });
});

describe('MapPeekWindowsSection — the aurora door\'s guide link (G3)', () => {
  it('offers "What does this do?" for night-sky#aurora-map and returns focus to the door trigger', () => {
    mockOpenGuide.mockClear();
    draw({ auroraDoor: DOOR });
    const trigger = screen.getByTestId('wf-aurora-upsell-trigger');
    fireEvent.click(trigger);
    fireEvent.click(screen.getByTestId('wf-aurora-upsell-guide'));
    expect(mockOpenGuide).toHaveBeenCalledWith('night-sky', 'aurora-map', expect.any(Object));
    expect(mockOpenGuide.mock.calls[0][2].returnFocus()).toBe(trigger);
  });
});
