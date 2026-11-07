import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import InfoTip from '../components/InfoTip.jsx';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function stubRect() {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    top: 200, bottom: 220, left: 100, right: 116, width: 16, height: 20,
  });
}

describe('InfoTip branches', () => {
  it('places a below popover under the trigger', () => {
    stubRect();
    render(<InfoTip text="Help" position="below" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    const style = screen.getByTestId('infotip-popover').style;
    expect(style.top).toBe('226px');
    expect(style.left).toBe('100px');
  });

  it('re-anchors an open popover when the page scrolls', () => {
    stubRect();
    render(<InfoTip text="Help" position="below" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByTestId('infotip-popover').style.top).toBe('226px');
    HTMLElement.prototype.getBoundingClientRect.mockReturnValue({
      top: 300, bottom: 320, left: 100, right: 116, width: 16, height: 20,
    });
    fireEvent.scroll(window);
    expect(screen.getByTestId('infotip-popover').style.top).toBe('326px');
  });

  it('stays open for a press inside the popover and closes for one outside', () => {
    render(<InfoTip text="Help text" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    fireEvent.mouseDown(screen.getByTestId('infotip-popover'));
    expect(screen.getByTestId('infotip-popover')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByTestId('infotip-popover')).not.toBeInTheDocument();
  });

  it('the card variant without an accent colour uses the neutral heading and border', () => {
    render(<InfoTip text="Body text" heading="Mechanism" />);
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    const popover = screen.getByTestId('infotip-popover');
    expect(popover).toHaveTextContent('Mechanism');
    expect(popover.style.borderLeft).toContain('3px solid');
    expect(screen.getByText('Mechanism').style.color).toBe('var(--color-plex-text)');
  });

  it('the card variant with an accent colour tints the heading and keeps clicks inside', () => {
    const onOuterClick = vi.fn();
    const onOuterKey = vi.fn();
    render(
      // eslint-disable-next-line jsx-a11y/no-static-element-interactions
      <div onClick={onOuterClick} onKeyDown={onOuterKey}>
        <InfoTip text="Body text" heading="Mechanism" accentColor="rgb(1, 2, 3)" />
      </div>,
    );
    fireEvent.click(screen.getByTestId('infotip-trigger'));
    expect(screen.getByText('Mechanism').style.color).toBe('rgb(1, 2, 3)');
    fireEvent.click(screen.getByTestId('infotip-popover'));
    fireEvent.keyDown(screen.getByTestId('infotip-popover'), { key: 'a' });
    expect(onOuterClick).not.toHaveBeenCalled();
    expect(onOuterKey).not.toHaveBeenCalled();
  });
});
