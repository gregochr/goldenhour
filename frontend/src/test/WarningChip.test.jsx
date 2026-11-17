import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WarningChip, { WarningChips, WarningFooter } from '../components/WarningChip.jsx';
import WarningStrip from '../components/WarningStrip.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

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

  it('prints the attribution and the Met Office link where warnings are shown', () => {
    render(<WarningFooter {...base} showAttribution />);
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(screen.getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
    expect(screen.queryByTestId('warning-stale')).toBeNull();
  });

  it('says the warnings may be out of date when stale, with the last check in UK time', () => {
    render(<WarningFooter {...base} showAttribution={false} stale />);
    expect(screen.getByTestId('warning-stale'))
      .toHaveTextContent('Warnings last checked 14:05 — check the Met Office');
    expect(screen.getByTestId('warning-stale-link')).toHaveAttribute('href', METOFFICE_URL);
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });

  it('carries both lines when stale warnings are shown', () => {
    render(<WarningFooter {...base} showAttribution stale lastCheckedAt={null} />);
    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Warnings have not been checked yet');
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('is absent when nothing is shown and nothing is stale', () => {
    const { container } = render(<WarningFooter {...base} showAttribution={false} />);
    expect(container).toBeEmptyDOMElement();
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
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
  });
});
