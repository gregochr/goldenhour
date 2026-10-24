import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import MapTopicChips from '../components/map/MapTopicChips.jsx';

const TIDE = {
  type: 'KING_TIDE', name: 'King tide', glyph: '🌊', colour: '#6FA8B0',
  where: '66 coastal', label: 'King tide, 66 coastal places', wholeSky: false, bigSeas: false,
};
const AURORA = {
  type: 'AURORA', name: 'Aurora', glyph: '🌌', colour: '#8E86D6',
  where: 'sky-wide', label: 'Aurora, sky-wide', wholeSky: true, bigSeas: false,
};
const SEAS = {
  type: 'BIG_SEAS', name: 'Big seas', glyph: null, colour: '#8FC0C7',
  where: '4 structures', label: 'Big seas, 4 structures', wholeSky: false, bigSeas: true,
};

function renderChips(props = {}) {
  const onToggle = vi.fn();
  const utils = render(
    <MapTopicChips chips={[SEAS, TIDE, AURORA]} selectedType={null} onToggle={onToggle} {...props} />,
  );
  return { onToggle, ...utils };
}

describe('MapTopicChips: the toggle', () => {
  it('renders one toggle button per chip in a labelled group, with the state in aria-pressed', () => {
    renderChips({ selectedType: 'KING_TIDE' });
    const group = screen.getByRole('group', { name: 'Hot topics on this window' });
    expect(group).toBe(screen.getByTestId('wf-topics'));
    const tide = screen.getByRole('button', { name: 'King tide, 66 coastal places' });
    expect(tide).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Aurora, sky-wide' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Big seas, 4 structures' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('keeps the accessible name the same whether pressed or not (aria-pressed carries the state)', () => {
    const { rerender, onToggle } = renderChips({ selectedType: null });
    const before = screen.getByTestId('wf-topic-chip-KING_TIDE').getAttribute('aria-label');
    rerender(<MapTopicChips chips={[SEAS, TIDE, AURORA]} selectedType="KING_TIDE" onToggle={onToggle} />);
    expect(screen.getByTestId('wf-topic-chip-KING_TIDE').getAttribute('aria-label')).toBe(before);
  });

  it('calls onToggle with the chip\'s type on press', () => {
    const { onToggle } = renderChips();
    fireEvent.click(screen.getByTestId('wf-topic-chip-AURORA'));
    expect(onToggle).toHaveBeenCalledWith('AURORA');
  });

  it('hides the glyph from assistive tech and draws the bolt for Big seas', () => {
    renderChips();
    const seas = screen.getByTestId('wf-topic-chip-BIG_SEAS');
    expect(seas.querySelector('[data-testid="wf-bolt"]')).not.toBeNull();
    expect(seas.querySelector('.wf-tch-g').getAttribute('aria-hidden')).toBe('true');
    expect(screen.getByTestId('wf-topic-chip-KING_TIDE').querySelector('.wf-tch-g').textContent).toBe(TIDE.glyph);
  });

  it('carries the topic colour as a custom property from the palette, not a hex of its own', () => {
    renderChips();
    expect(screen.getByTestId('wf-topic-chip-KING_TIDE').style.getPropertyValue('--wf-tc')).toBe('#6FA8B0');
  });

  it('omits the where element when a chip has none', () => {
    renderChips({ chips: [{ ...TIDE, where: null, label: 'King tide' }] });
    expect(screen.queryByTestId('wf-topic-where')).toBeNull();
  });
});

describe('MapTopicChips: the sky-wide line', () => {
  it('prints the notice inside the row for a selected whole-sky chip, and not otherwise', () => {
    const { rerender, onToggle } = renderChips({ selectedType: 'AURORA' });
    const line = screen.getByTestId('wf-topic-skywide');
    expect(line.textContent)
      .toContain('Aurora possible · sky-wide — every location in range sees it or none does');
    expect(screen.getByTestId('wf-topics').contains(line)).toBe(true);
    // Not a live region: the announcement goes through MapView's status line.
    expect(line).not.toHaveAttribute('role');
    expect(line).not.toHaveAttribute('aria-live');
    rerender(<MapTopicChips chips={[SEAS, TIDE, AURORA]} selectedType="KING_TIDE" onToggle={onToggle} />);
    expect(screen.queryByTestId('wf-topic-skywide')).toBeNull();
  });
});

describe('MapTopicChips: focus when the focused chip unmounts', () => {
  it('moves focus to the group', () => {
    const { rerender, onToggle } = renderChips({ selectedType: 'KING_TIDE' });
    const tide = screen.getByTestId('wf-topic-chip-KING_TIDE');
    tide.focus();
    expect(document.activeElement).toBe(tide);
    rerender(<MapTopicChips chips={[SEAS, AURORA]} selectedType={null} onToggle={onToggle} />);
    expect(screen.queryByTestId('wf-topic-chip-KING_TIDE')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-topics'));
  });

  it('⚠️ still parks when the browser fired a blur BEFORE the removal (Chromium does, element still connected)', () => {
    const { rerender, onToggle } = renderChips();
    const tide = screen.getByTestId('wf-topic-chip-KING_TIDE');
    tide.focus();
    // What Chromium does on removing the focused element: blur first, the node still in the document.
    fireEvent.blur(tide);
    rerender(<MapTopicChips chips={[SEAS, AURORA]} selectedType={null} onToggle={onToggle} />);
    expect(document.activeElement).toBe(screen.getByTestId('wf-topics'));
  });

  it('falls back to fallbackFocus when the whole row goes away (suppressed, or an empty phone row)', () => {
    const fallbackFocus = vi.fn();
    const { rerender, onToggle } = renderChips({ fallbackFocus, isMobile: true });
    screen.getByTestId('wf-topic-chip-KING_TIDE').focus();
    rerender(
      <MapTopicChips
        chips={[SEAS, TIDE, AURORA]}
        selectedType={null}
        onToggle={onToggle}
        isMobile
        suppressed
        fallbackFocus={fallbackFocus}
      />,
    );
    expect(screen.queryByTestId('wf-topics')).toBeNull();
    expect(fallbackFocus).toHaveBeenCalledTimes(1);
  });

  it('falls back when the chips drop to none on a phone, and when no window is on screen', () => {
    const fallbackFocus = vi.fn();
    const { rerender, onToggle } = renderChips({ fallbackFocus, isMobile: true });
    screen.getByTestId('wf-topic-chip-AURORA').focus();
    rerender(<MapTopicChips chips={[]} selectedType={null} onToggle={onToggle} isMobile fallbackFocus={fallbackFocus} />);
    expect(fallbackFocus).toHaveBeenCalledTimes(1);
  });

  it('leaves focus alone when an UNFOCUSED chip unmounts', () => {
    const fallbackFocus = vi.fn();
    const { rerender, onToggle } = renderChips({ fallbackFocus });
    const aurora = screen.getByTestId('wf-topic-chip-AURORA');
    aurora.focus();
    rerender(<MapTopicChips chips={[SEAS, AURORA]} selectedType={null} onToggle={onToggle} fallbackFocus={fallbackFocus} />);
    expect(document.activeElement).toBe(screen.getByTestId('wf-topic-chip-AURORA'));
    expect(fallbackFocus).not.toHaveBeenCalled();
  });

  it('does not steal focus after the reader has moved on to something else', () => {
    const { rerender, onToggle } = renderChips();
    const tide = screen.getByTestId('wf-topic-chip-KING_TIDE');
    tide.focus();
    const elsewhere = document.createElement('button');
    document.body.appendChild(elsewhere);
    elsewhere.focus();
    rerender(<MapTopicChips chips={[SEAS, AURORA]} selectedType={null} onToggle={onToggle} />);
    expect(document.activeElement).toBe(elsewhere);
    elsewhere.remove();
  });
});

describe('MapTopicChips: suppressed', () => {
  it('draws nothing at all, chips and empty state alike', () => {
    const { container, rerender, onToggle } = renderChips({ suppressed: true });
    expect(container).toBeEmptyDOMElement();
    rerender(<MapTopicChips chips={[]} selectedType={null} onToggle={onToggle} suppressed />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('MapTopicChips: the empty state', () => {
  it('says so on desktop and tablet, as text and not a button', () => {
    renderChips({ chips: [] });
    const empty = screen.getByTestId('wf-topics-empty');
    expect(empty).toHaveTextContent('No hot topics on this window');
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('renders nothing at all on the phone', () => {
    const { container } = renderChips({ chips: [], isMobile: true });
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when no window is on screen', () => {
    const { container } = renderChips({ chips: [], hasWindow: false });
    expect(container).toBeEmptyDOMElement();
  });

  it('still renders the chips on the phone', () => {
    renderChips({ isMobile: true });
    expect(screen.getAllByRole('button')).toHaveLength(3);
  });
});
