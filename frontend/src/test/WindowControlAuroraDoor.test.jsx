/**
 * The Lite aurora Pro door inside `WindowControl`'s menu (docs/engineering/aurora-pro-door-plan.md §2.2).
 *
 * <p><b>What breaks if these fail:</b> the door lands inside the listbox (whose children must be options),
 * an arrow key on it steps the window and unmounts it with focus inside, Escape strands focus on
 * `.wf-map-tab`, or a reader who gets no door sees a different menu from the one they had.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowControl from '../components/map/WindowControl.jsx';

const EVENTS = [
  {
    id: 'solar:2026-09-02:SUNRISE', kind: 'solar', eventType: 'SUNRISE', date: '2026-09-02', label: 'Today', time: '06:30', bestRating: 3, scored: true, badges: [],
  },
  {
    id: 'solar:2026-09-02:SUNSET', kind: 'solar', eventType: 'SUNSET', date: '2026-09-02', label: 'Tonight', time: '19:45', bestRating: 4, scored: true, badges: [],
  },
  {
    id: 'solar:2026-09-03:SUNRISE', kind: 'solar', eventType: 'SUNRISE', date: '2026-09-03', label: 'Tomorrow', time: '06:32', bestRating: 5, scored: true, badges: [],
  },
];
const DOOR = { label: 'Aurora tonight' };

function draw(props = {}) {
  const onSelect = vi.fn();
  const utils = render(
    <WindowControl events={EVENTS} activeIndex={1} onSelect={onSelect} onOpenWindowPanel={() => {}} {...props} />,
  );
  return { onSelect, ...utils };
}
const openMenu = () => fireEvent.click(screen.getByTestId('wf-win-pill'));

describe('WindowControl — the Lite aurora door', () => {
  it('sits in the open menu after the listbox AND the drilldown row, never inside the listbox', () => {
    draw({ auroraDoor: DOOR });
    openMenu();

    const menu = screen.getByTestId('wf-win-menu');
    const listbox = screen.getByTestId('wf-win-listbox');
    const more = screen.getByTestId('wf-win-more');
    const wrapper = screen.getByTestId('wf-win-aurora-door');

    expect(menu).toContainElement(wrapper);
    expect(listbox).not.toContainElement(wrapper);
    expect(listbox.compareDocumentPosition(wrapper) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(more.compareDocumentPosition(wrapper) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The listbox still holds options and only options.
    const kids = Array.from(listbox.querySelectorAll('button')).filter((b) => b.getAttribute('role') !== 'option');
    expect(kids).toHaveLength(0);
    expect(within(listbox).getAllByRole('option')).toHaveLength(EVENTS.length);
  });

  it('names its trigger with the label and "Pro", and carries the inline modifier', () => {
    draw({ auroraDoor: DOOR });
    openMenu();

    expect(screen.getByRole('button', { name: 'Aurora tonight Pro' })).toBeInTheDocument();
    expect(screen.getByTestId('wf-aurora-upsell-door')).toHaveClass('wf-pro-upsell-door--inline');
  });

  it('is not an event: the row count and the steppers are the same with or without it', () => {
    const { unmount } = draw({ auroraDoor: DOOR });
    openMenu();
    const withDoor = screen.getAllByRole('option').length;
    const prevWith = screen.getByTestId('wf-win-prev').disabled;
    const nextWith = screen.getByTestId('wf-win-next').disabled;
    unmount();

    draw();
    openMenu();
    expect(screen.getAllByRole('option')).toHaveLength(withDoor);
    expect(screen.getByTestId('wf-win-prev').disabled).toBe(prevWith);
    expect(screen.getByTestId('wf-win-next').disabled).toBe(nextWith);
  });

  it('without the prop the menu has no door, and the DOM is the one it always was', () => {
    draw();
    openMenu();

    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
    expect(screen.queryByTestId('wf-aurora-upsell-door')).toBeNull();
    const menuHtml = screen.getByTestId('wf-win-menu').innerHTML;
    expect(menuHtml).not.toMatch(/aurora-door|wf-pro-upsell/);
  });

  it('draws nothing while the menu is closed', () => {
    draw({ auroraDoor: DOOR });
    expect(screen.queryByTestId('wf-win-aurora-door')).toBeNull();
  });

  it('Escape with the popover open closes the popover only; the menu stays open', () => {
    draw({ auroraDoor: DOOR });
    openMenu();
    fireEvent.click(screen.getByTestId('wf-aurora-upsell-trigger'));
    expect(screen.getByTestId('wf-aurora-upsell-popover')).toBeInTheDocument();

    fireEvent.keyDown(screen.getByTestId('wf-aurora-upsell-popover'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-aurora-upsell-popover')).toBeNull();
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    // A second Escape closes the menu.
    fireEvent.keyDown(screen.getByTestId('wf-aurora-upsell-trigger'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
  });

  it.each(['ArrowLeft', 'ArrowRight'])('%s on the trigger neither steps the window nor closes the menu', (key) => {
    const { onSelect } = draw({ auroraDoor: DOOR });
    openMenu();

    fireEvent.keyDown(screen.getByTestId('wf-aurora-upsell-trigger'), { key });

    expect(onSelect).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
  });

  it.each(['ArrowLeft', 'ArrowRight'])('%s on "Not now" neither steps the window nor closes the menu', (key) => {
    const { onSelect } = draw({ auroraDoor: DOOR });
    openMenu();
    fireEvent.click(screen.getByTestId('wf-aurora-upsell-trigger'));

    fireEvent.keyDown(screen.getByTestId('wf-aurora-upsell-dismiss'), { key });

    expect(onSelect).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    expect(screen.getByTestId('wf-aurora-upsell-popover')).toBeInTheDocument();
  });

  it('the arrow keys still step the window from the pill, door or not', () => {
    const { onSelect } = draw({ auroraDoor: DOOR });
    fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'ArrowRight' });
    expect(onSelect).toHaveBeenCalledWith(EVENTS[2]);
  });

  it('Escape on the closed trigger closes the menu and focuses the pill, not the tab behind', () => {
    draw({ auroraDoor: DOOR });
    openMenu();
    const trigger = screen.getByTestId('wf-aurora-upsell-trigger');
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    fireEvent.keyDown(trigger, { key: 'Escape' });

    expect(screen.queryByTestId('wf-win-menu')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });

  it('the door going away while focused (menu still open) hands focus to the pill, not the tab behind', () => {
    const onSelect = vi.fn();
    const { rerender } = render(
      <WindowControl events={EVENTS} activeIndex={1} onSelect={onSelect} auroraDoor={DOOR} />,
    );
    openMenu();
    screen.getByTestId('wf-aurora-upsell-trigger').focus();

    rerender(<WindowControl events={EVENTS} activeIndex={1} onSelect={onSelect} auroraDoor={null} />);

    expect(screen.getByTestId('wf-win-menu')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-win-pill'));
  });
});
