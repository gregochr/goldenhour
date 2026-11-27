/**
 * The warning lanes in {@code WindowGlanceLanes} (weather-warnings-plan.md W3).
 *
 * <p>What breaks if these fail: a warning lane is drawn after the topic lanes (the plan says first), topic lanes
 * overlap the warning rows, the level word is missing from a lane, a lane has no accessible destination, the source
 * line is lost, the stale admission is lost, or the phone copy repeats a lane on a day it does not cover.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import WindowGlanceLanes from '../components/WindowGlanceLanes.jsx';
import { ATTRIBUTION, METOFFICE_URL, warning } from './warningsFixture.js';

const topicLane = {
  key: 'INVERSION@2026-10-25', type: 'INVERSION', name: 'Inversion', colour: '#4A90A4', startCol: 2, endCol: 2,
  row: 1, detail: 'dawn', detailDate: '2026-10-25', peak: null, startsBefore: false, endsAfter: false,
  dates: ['2026-10-25'], jump: null, mapDoor: null, target: '2026-10-25:SUNRISE',
};

const wLane = (over = {}) => ({
  key: 'w1@2026-10-24',
  warning: warning({ warningId: 'w1', level: 'AMBER', weatherTypes: ['SNOW'] }),
  startCol: 1,
  endCol: 2,
  row: 1,
  dates: ['2026-10-24', '2026-10-25'],
  firstCardKey: '2026-10-24:SUNSET',
  tappable: true,
  ...over,
});

const footer = (over = {}) => ({
  showAttribution: true, stale: false, lastCheckedAt: '2026-10-24T10:00:00Z', attribution: ATTRIBUTION,
  href: METOFFICE_URL, ...over,
});

afterEach(cleanup);

describe('warning lanes on the desktop grid', () => {
  it('draws the warning lane first in the stack, before every topic lane, and shifts the topic rows below it', () => {
    render(
      <WindowGlanceLanes
        lanes={[topicLane]}
        onTap={() => {}}
        warningLanes={[wLane(), wLane({ key: 'w2@2026-10-25', row: 2, startCol: 2, endCol: 2, dates: ['2026-10-25'],
          warning: warning({ warningId: 'w2', level: 'YELLOW' }) })]}
        warningFooter={footer()}
      />,
    );
    const items = screen.getByTestId('wf-glance-lanes').children;
    expect([...items].map((li) => li.getAttribute('data-testid') ?? 'topic')).toEqual([
      'wf-warning-lane-item', 'wf-warning-lane-item', 'wf-warning-lane-footer', 'topic',
    ]);
    const rows = [...items].map((li) => li.style.getPropertyValue('--lr'));
    // Two warning rows, the source line on row 3, the topic lane (model row 1) on row 4.
    expect(rows).toEqual(['1', '2', '3', '4']);
    expect(items[0].style.getPropertyValue('--ls')).toBe('1');
    expect(items[0].style.getPropertyValue('--le')).toBe('3');
  });

  it('leaves the topic rows exactly where they were when no warning applies', () => {
    render(<WindowGlanceLanes lanes={[topicLane]} onTap={() => {}} />);
    expect(screen.getByTestId('wf-glance-lane').closest('li').style.getPropertyValue('--lr')).toBe('1');
  });

  it('prints the level word, the weather type and the Updated label, with no icon', () => {
    render(<WindowGlanceLanes
      lanes={[]}
      onTap={() => {}}
      warningLanes={[wLane({ warning: warning({ warningId: 'w1', level: 'RED', weatherTypes: ['RAIN'], updated: true }) })]}
    />);
    const lane = screen.getByTestId('wf-warning-lane');
    expect(lane).toHaveTextContent('Red warning of rain');
    expect(within(lane).getByTestId('wf-warning-lane-updated')).toHaveTextContent('Updated');
    expect(lane.querySelector('svg, img')).toBeNull();
    expect(lane.className).toContain('bg-[#ca0a37]');
  });

  it('names the lane from its content, with the span and the destination, so the visible words are in the name', () => {
    render(<WindowGlanceLanes lanes={[]} onTap={() => {}} warningLanes={[wLane()]} onWarningTap={() => {}} />);
    const button = screen.getByRole('button', { name: /^Amber warning of snow, .*Saturday to Sunday, opens that window on the map$/ });
    expect(button).toBeInTheDocument();
  });

  it('opens the first window when pressed', () => {
    const onWarningTap = vi.fn();
    const lane = wLane();
    render(<WindowGlanceLanes lanes={[]} onTap={() => {}} warningLanes={[lane]} onWarningTap={onWarningTap} />);
    fireEvent.click(screen.getByTestId('wf-warning-lane'));
    expect(onWarningTap).toHaveBeenCalledWith(lane);
  });

  it('is plain text, not a button, when the strip cannot open the window or gives no handler', () => {
    const { unmount } = render(
      <WindowGlanceLanes lanes={[]} onTap={() => {}} warningLanes={[wLane({ tappable: false })]} onWarningTap={() => {}} />,
    );
    expect(screen.getByTestId('wf-warning-lane').tagName).toBe('DIV');
    expect(screen.queryByRole('button')).toBeNull();
    unmount();
    render(<WindowGlanceLanes lanes={[]} onTap={() => {}} warningLanes={[wLane()]} />);
    expect(screen.getByTestId('wf-warning-lane').tagName).toBe('DIV');
    expect(screen.getByTestId('wf-warning-lane')).not.toHaveTextContent('opens the');
  });

  it('prints an unknown level neutral with its own word, and draws nothing for a lane with no level', () => {
    render(<WindowGlanceLanes
      lanes={[]}
      onTap={() => {}}
      warningLanes={[
        wLane({ warning: warning({ warningId: 'u', level: 'PURPLE', weatherTypes: [] }) }),
        wLane({ key: 'n', row: 2, warning: warning({ warningId: 'n', level: '' }) }),
      ]}
    />);
    const lanes = screen.getAllByTestId('wf-warning-lane');
    expect(lanes).toHaveLength(1);
    expect(lanes[0]).toHaveTextContent('Purple warning');
    expect(lanes[0].className).toContain('bg-plex-surface-light');
  });

  it('carries the attribution and the Met Office link under the lanes', () => {
    render(<WindowGlanceLanes lanes={[]} onTap={() => {}} warningLanes={[wLane()]} warningFooter={footer()} />);
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('prints the stale admission with no lanes at all, never an all-clear', () => {
    render(<WindowGlanceLanes
      lanes={[]}
      onTap={() => {}}
      warningFooter={footer({ showAttribution: false, stale: true })}
    />);
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Warnings last checked');
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
    expect(document.body.textContent).not.toMatch(/no warnings/i);
  });

  it('renders nothing with no lanes, no warnings and a footer with nothing to say', () => {
    const { container } = render(
      <WindowGlanceLanes lanes={[]} onTap={() => {}} warningFooter={footer({ showAttribution: false })} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('warning lanes on the phone', () => {
  it('draws only the lanes that cover the day, first in the cell, with no inline grid placement', () => {
    render(
      <WindowGlanceLanes
        lanes={[{ ...topicLane, startCol: 1, endCol: 1, dates: ['2026-10-24'] }]}
        date="2026-10-24"
        onTap={() => {}}
        warningLanes={[wLane(), wLane({ key: 'w3', dates: ['2026-10-26'], warning: warning({ warningId: 'w3' }) })]}
      />,
    );
    const cell = screen.getByTestId('wf-glance-lanes-day');
    const items = within(cell).getAllByRole('listitem');
    expect(items[0]).toHaveAttribute('data-testid', 'wf-warning-lane-item');
    expect(within(cell).getAllByTestId('wf-warning-lane')).toHaveLength(1);
    expect(items[0].getAttribute('style')).toBeNull();
    expect(within(cell).getByTestId('wf-warning-lane')).toHaveTextContent('Amber warning of snow');
  });

  it('lists a day\'s lanes most severe first whatever row they hold on the grid', () => {
    render(
      <WindowGlanceLanes
        lanes={[]}
        date="2026-10-24"
        onTap={() => {}}
        warningLanes={[
          wLane({ key: 'y', row: 1, warning: warning({ warningId: 'y', level: 'YELLOW' }) }),
          wLane({ key: 'r', row: 2, warning: warning({ warningId: 'r', level: 'RED', weatherTypes: ['RAIN'] }) }),
        ]}
      />,
    );
    expect(screen.getAllByTestId('wf-warning-lane').map((l) => l.dataset.level)).toEqual(['RED', 'YELLOW']);
  });

  it('speaks the day it is on', () => {
    render(<WindowGlanceLanes lanes={[]} date="2026-10-25" onTap={() => {}} warningLanes={[wLane()]} onWarningTap={() => {}} />);
    expect(screen.getByRole('button', { name: /Amber warning of snow, Sunday, opens that window on the map/ })).toBeInTheDocument();
  });

  it('renders nothing for a day with no warning lane and no footer', () => {
    const { container } = render(
      <WindowGlanceLanes lanes={[]} date="2026-10-27" onTap={() => {}} warningLanes={[wLane()]} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the stale line in a day cell that has nothing else', () => {
    render(<WindowGlanceLanes
      lanes={[]}
      date="2026-10-27"
      onTap={() => {}}
      warningFooter={footer({ showAttribution: false, stale: true })}
    />);
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
  });
});
