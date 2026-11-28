import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WarningChip, { WarningChips, WarningFooter } from '../components/WarningChip.jsx';
import WarningStrip from '../components/WarningStrip.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';
import { mockOpenGuide } from './guideContextMock.js';

describe('WarningChip', () => {
  it('prints the level word and type as text, never colour alone', () => {
    render(<WarningChip warning={warning({ level: 'AMBER', weatherTypes: ['RAIN', 'WIND'] })} href={METOFFICE_URL} />);
    const chip = screen.getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Amber warning of rain and wind');
    expect(chip).toHaveAttribute('data-level', 'AMBER');
  });

  it.each([
    ['RED', 'bg-[#ca0a37]', 'text-white'],
    ['AMBER', 'bg-[#ff9900]', 'text-black'],
    ['YELLOW', 'bg-[#ffe923]', 'text-black'],
  ])('%s takes the official colour and the guide\'s ink', (level, bg, ink) => {
    render(<WarningChip warning={warning({ level })} href={METOFFICE_URL} />);
    const chip = screen.getByTestId('warning-chip');
    expect(chip).toHaveClass(bg);
    expect(chip).toHaveClass(ink);
  });

  it('draws an unknown level neutral, with its word, rather than borrowing another level\'s colour', () => {
    render(<WarningChip warning={warning({ level: 'GREEN' })} href={METOFFICE_URL} />);
    const chip = screen.getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Green warning of wind');
    expect(chip.className).not.toMatch(/ca0a37|ff9900|ffe923/);
  });

  it('is a link to the Met Office page that opens a new tab, safely, with a full accessible name', () => {
    render(<WarningChip warning={warning({ updated: true })} href={METOFFICE_URL} />);
    const link = screen.getByRole('link', { name: /Yellow warning of wind,\s*Updated,\s*Met Office Weather Warnings/ });
    expect(link).toHaveAttribute('href', METOFFICE_URL);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('labels an updated warning "Updated" and leaves a first issue unlabelled', () => {
    const { unmount } = render(<WarningChip warning={warning({ updated: true })} href={METOFFICE_URL} />);
    expect(screen.getByTestId('warning-chip-updated')).toHaveTextContent('Updated');
    unmount();
    render(<WarningChip warning={warning({ updated: false })} href={METOFFICE_URL} />);
    expect(screen.queryByTestId('warning-chip-updated')).toBeNull();
  });

  it('is plain text when there is no page to link to', () => {
    render(<WarningChip warning={warning()} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByTestId('warning-chip')).toHaveTextContent('Yellow warning of wind');
  });

  it('draws no icon: no svg, no glyph, no image', () => {
    render(<WarningChip warning={warning({ level: 'RED' })} href={METOFFICE_URL} />);
    const chip = screen.getByTestId('warning-chip');
    expect(chip.querySelector('svg, img')).toBeNull();
    expect(chip.textContent).not.toMatch(/[▲⚠↑★]/);
  });

  it('draws nothing for a warning with no level', () => {
    const { container } = render(<WarningChip warning={warning({ level: null })} href={METOFFICE_URL} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('WarningChips', () => {
  it('renders nothing for no warnings', () => {
    const { container } = render(<WarningChips warnings={[]} href={METOFFICE_URL} />);
    expect(container).toBeEmptyDOMElement();
    const again = render(<WarningChips warnings={undefined} href={METOFFICE_URL} />);
    expect(again.container).toBeEmptyDOMElement();
  });

  it('renders a labelled group of chips in the order given', () => {
    render(
      <WarningChips
        warnings={[warning({ warningId: 'r', level: 'RED' }), warning({ warningId: 'y' })]}
        href={METOFFICE_URL}
        testId="mine"
      />,
    );
    const group = screen.getByRole('group', { name: 'Met Office weather warnings' });
    expect(group).toHaveAttribute('data-testid', 'mine');
    expect(screen.getAllByTestId('warning-chip').map((c) => c.dataset.level)).toEqual(['RED', 'YELLOW']);
  });
});

describe('WarningFooter', () => {
  const base = {
    stale: false, lastCheckedAt: '2026-08-14T13:05:00Z', attribution: ATTRIBUTION, href: METOFFICE_URL,
  };
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-08-14T15:00:00Z'));
  });
  afterEach(() => { vi.useRealTimers(); });

  it('prints the served attribution verbatim AS the link to the Met Office overview, new tab, then an arrow', () => {
    render(<WarningFooter {...base} showAttribution />);
    const link = screen.getByRole('link', {
      name: 'Met Office Weather Warnings \u00a9 Crown Copyright 2026, Met Office warnings page, opens in a new tab',
    });
    expect(link).toBe(screen.getByTestId('warning-source-link'));
    expect(link).toHaveAttribute('href', METOFFICE_URL);
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link.textContent).toContain('Met Office Weather Warnings \u00a9 Crown Copyright 2026');
    expect(link.textContent).toContain('\u2197');
    expect(screen.queryByTestId('warning-stale')).toBeNull();
  });

  it('no longer prints "Read it on the Met Office site" or "check the Met Office"', () => {
    render(<WarningFooter {...base} showAttribution stale />);
    expect(screen.queryByText(/Read it on the Met Office site/)).toBeNull();
    expect(screen.queryByText(/check the Met Office/)).toBeNull();
    expect(screen.queryByTestId('warning-stale-link')).toBeNull();
  });

  it('holds the last word, the arrow and the ? together, so nothing is stranded', () => {
    render(<WarningFooter {...base} showAttribution />);
    const nowrap = screen.getByTestId('warning-source-link').querySelector('.whitespace-nowrap');
    expect(nowrap).toHaveTextContent('2026');
    expect(nowrap).toHaveTextContent('\u2197');
    // A word joiner sits between the link and the ?, so the ? cannot start a line of its own.
    const line = screen.getByTestId('warning-attribution');
    expect(line.textContent.endsWith('\u2060')).toBe(true);
    expect(screen.getByTestId('warning-guide-link')).toBeInTheDocument();
  });

  it('pins the ? to the far right when the footer is box-wide (the Map)', () => {
    render(<WarningFooter {...base} showAttribution guideAtEnd />);
    expect(screen.getByTestId('warning-attribution-line')).toHaveClass('flex', 'justify-between');
    expect(screen.getByTestId('warning-attribution').textContent.endsWith('\u2060')).toBe(false);
  });

  it('says the warnings may be out of date on a line of its own above the attribution when stale', () => {
    render(<WarningFooter {...base} showAttribution stale />);
    const stale = screen.getByTestId('warning-stale');
    expect(stale).toHaveTextContent('Last checked 1 h ago. These may be out of date.');
    expect(stale.tagName).toBe('P');
    expect(screen.getByTestId('warning-attribution-line').tagName).toBe('P');
    expect(stale.compareDocumentPosition(screen.getByTestId('warning-attribution')))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it('still says so, never an all-clear, and still links to the Met Office, when stale with no warning shown', () => {
    render(<WarningFooter {...base} showAttribution={false} stale />);
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Last checked 1 h ago. These may be out of date.');
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });

  it('admits when no check has succeeded yet', () => {
    render(<WarningFooter {...base} showAttribution stale lastCheckedAt={null} />);
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Not checked yet. These may be out of date.');
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('is absent when nothing is shown and nothing is stale', () => {
    const { container } = render(<WarningFooter {...base} showAttribution={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  describe('the guide door', () => {
    beforeEach(() => mockOpenGuide.mockClear());

    it('ends the source line with a named ? that opens the weather warnings guide', () => {
      render(<WarningFooter {...base} showAttribution />);
      const door = screen.getByTestId('warning-guide-link');
      expect(door).toHaveAttribute('aria-label', 'About Met Office weather warnings');
      fireEvent.click(door);
      expect(mockOpenGuide).toHaveBeenCalledWith('weather-warnings', 'what', expect.any(Object));
    });

    it('opens the freshness section when the feed is stale', () => {
      render(<WarningFooter {...base} showAttribution={false} stale />);
      const door = screen.getByTestId('warning-guide-link');
      expect(door).toHaveAttribute('aria-label', 'How fresh Met Office weather warnings are');
      fireEvent.click(door);
      expect(mockOpenGuide).toHaveBeenCalledWith('weather-warnings', 'fresh', expect.any(Object));
    });

    it('is not drawn where the footer is not (nothing shown, nothing stale)', () => {
      render(<WarningFooter {...base} showAttribution={false} />);
      expect(screen.queryByTestId('warning-guide-link')).toBeNull();
    });
  });
});

describe('WarningStrip', () => {
  beforeEach(() => { getWarnings.mockReset(); });

  const mount = async (warnings, served) => {
    getWarnings.mockResolvedValue(served);
    render(<WarningsProvider><WarningStrip warnings={warnings} /></WarningsProvider>);
    await act(async () => {});
  };

  it('shows the chips and the source line for a window with a warning', async () => {
    const w = warning();
    await mount([w], response([w]));
    expect(screen.getByTestId('warning-chip')).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-guide-link')).toBeInTheDocument();
  });

  it('prints nothing while the feature is off', async () => {
    await mount([warning()], response([], { enabled: false }));
    expect(screen.queryByTestId('warning-strip')).toBeNull();
  });

  it('prints nothing for a clear window on a fresh feed (it never claims "no warnings")', async () => {
    await mount([], response([]));
    expect(screen.queryByTestId('warning-strip')).toBeNull();
  });

  it('prints only the stale line for a clear window on a stale feed', async () => {
    await mount([], response([], { stale: true }));
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
  });
});
