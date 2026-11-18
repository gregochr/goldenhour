import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

import AskWarnings from '../components/ask/AskWarnings.jsx';

const warnings = (over = {}) => ({
  stale: false,
  attribution: 'Met Office Weather Warnings © Crown Copyright 2026',
  url: 'https://www.metoffice.gov.uk/public/weather/warnings/',
  items: [{
    warningId: 'W1', level: 'RED', weatherTypes: ['SNOW', 'WIND'], validFrom: null, validTo: null, headline: null,
    updated: false,
  }],
  ...over,
});

describe('AskWarnings', () => {
  it('draws nothing for an answer with no warnings', () => {
    const { container } = render(<AskWarnings warnings={null} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('draws the level word and types as text, and leaves out a headline and validity the server did not send', () => {
    render(<AskWarnings warnings={warnings()} />);

    expect(screen.getByTestId('warning-chip')).toHaveTextContent('Red warning of snow and wind');
    expect(screen.queryByTestId('ask-warning-headline')).toBeNull();
    expect(screen.queryByTestId('ask-warning-validity')).toBeNull();
    expect(screen.queryByTestId('warning-chip-updated')).toBeNull();
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent('Crown Copyright 2026');
  });

  it('prints every warning it was given, most severe first as served', () => {
    const second = { warningId: 'W2', level: 'YELLOW', weatherTypes: ['RAIN'], headline: 'Rain', updated: false };
    render(<AskWarnings warnings={warnings({ items: [...warnings().items, second] })} />);

    expect(screen.getAllByTestId('ask-warning')).toHaveLength(2);
    const [red, yellow] = screen.getAllByTestId('warning-chip');
    expect(red).toHaveTextContent('Red warning of snow and wind');
    expect(yellow).toHaveTextContent('Yellow warning of rain');
  });
});
