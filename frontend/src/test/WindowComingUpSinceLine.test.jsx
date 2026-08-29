import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowComingUpSinceLine from '../components/WindowComingUpSinceLine.jsx';

/** A qualifying entry, in the shape the since-line reads (a subset of `ComingUpEntry`). */
const sinceEntry = (over = {}) => ({
  id: 'supermoon:2026-08-08:2026-08-08',
  bits: 8.2,
  title: 'Supermoon',
  enteredWindow: '2026-08-08',
  scoreNote: 'Rarity alone carries it over the top contour.',
  joinNote: null,
  ...over,
});

describe('WindowComingUpSinceLine', () => {
  it('renders nothing when there is no badge', () => {
    const { container } = render(
      <WindowComingUpSinceLine badge={null} entry={null} onMarkSeen={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the badge is set but the entry is missing (defensive)', () => {
    const { container } = render(
      <WindowComingUpSinceLine badge={{ band: 'announce', count: 1 }} entry={null} onMarkSeen={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('states the count, the title, the arrival date and scoreNote for an announce badge', () => {
    render(
      <WindowComingUpSinceLine
        badge={{ band: 'announce', count: 1 }}
        entry={sinceEntry()}
        onMarkSeen={vi.fn()}
      />,
    );
    const line = screen.getByTestId('coming-up-since');
    expect(line).toHaveTextContent('1 announced');
    expect(line).toHaveTextContent('the Supermoon entered the window, 8 Aug.');
    expect(line).toHaveTextContent('Rarity alone carries it over the top contour.');
    expect(line).toHaveTextContent('8.2 bits');
    expect(line.className).not.toContain('wf-cu-since-rare');
  });

  it('states the diamond and bits, no count, for an interrupt badge', () => {
    render(
      <WindowComingUpSinceLine
        badge={{ band: 'interrupt', count: null }}
        entry={sinceEntry({ bits: 11.6, title: 'Solar eclipse' })}
        onMarkSeen={vi.fn()}
      />,
    );
    const line = screen.getByTestId('coming-up-since');
    expect(line).toHaveTextContent('◆ 11.6 bits');
    expect(line).toHaveTextContent('the Solar eclipse entered the window, 8 Aug.');
    expect(line.className).toContain('wf-cu-since-rare');
    // The count never appears anywhere on an interrupt line, unlike the announce state above.
    expect(line).not.toHaveTextContent(/^\d+ announced/);
  });

  it('falls back to joinNote when scoreNote is absent — a merged (coincidence) winner', () => {
    render(
      <WindowComingUpSinceLine
        badge={{ band: 'announce', count: 1 }}
        entry={sinceEntry({
          scoreNote: null,
          joinNote: 'One perigee causes both, so the pair scores as the maximum of the two.',
        })}
        onMarkSeen={vi.fn()}
      />,
    );
    expect(screen.getByTestId('coming-up-since')).toHaveTextContent(
      'One perigee causes both, so the pair scores as the maximum of the two.',
    );
  });

  it('renders Mark seen and fires the handler on click', () => {
    const onMarkSeen = vi.fn();
    render(
      <WindowComingUpSinceLine
        badge={{ band: 'announce', count: 1 }}
        entry={sinceEntry()}
        onMarkSeen={onMarkSeen}
      />,
    );
    fireEvent.click(screen.getByTestId('coming-up-since-mark-seen'));
    expect(onMarkSeen).toHaveBeenCalledTimes(1);
  });
});
