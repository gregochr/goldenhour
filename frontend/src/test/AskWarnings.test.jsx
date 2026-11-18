import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import AskWarnings from '../components/ask/AskWarnings.jsx';
import { response, warning } from './warningsFixture.js';

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

describe('AskWarnings — checked against the live polled list', () => {
  const carried = (over = {}) => warnings({
    items: [{
      warningId: 'W1', warningVersion: '2.0', level: 'AMBER', weatherTypes: ['SNOW'], validFrom: null,
      validTo: null, headline: 'Snow', updated: false, ...over,
    }],
  });

  const renderLive = async (served) => {
    getWarnings.mockResolvedValue(served);
    render(<WarningsProvider><AskWarnings warnings={carried()} /></WarningsProvider>);
    await waitFor(() => expect(getWarnings).toHaveBeenCalled());
  };

  it('keeps showing a warning that is still in force at the same version', async () => {
    await renderLive(response([warning({ warningId: 'W1', warningVersion: '2.0', level: 'AMBER' })]));

    expect(await screen.findByTestId('ask-warning')).toBeInTheDocument();
    expect(screen.queryByTestId('ask-warning-retired')).toBeNull();
  });

  it('retires a warning the feed no longer lists: no chip, "no longer in force", check the Met Office', async () => {
    await renderLive(response([]));

    const retired = await screen.findByTestId('ask-warning-retired');
    expect(retired).toHaveAttribute('data-state', 'gone');
    expect(retired).toHaveTextContent('Amber warning of snow: this warning is no longer in force. Check the Met Office.');
    expect(screen.queryByTestId('warning-chip')).toBeNull();
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('retires a warning whose version has moved on, as updated', async () => {
    await renderLive(response([warning({ warningId: 'W1', warningVersion: '3.0', level: 'AMBER' })]));

    const retired = await screen.findByTestId('ask-warning-retired');
    expect(retired).toHaveAttribute('data-state', 'updated');
    expect(retired).toHaveTextContent('has been updated since this answer');
  });

  it('says the warnings may be out of date when the provider is stale, though the answer was fresh', async () => {
    await renderLive(response([warning({ warningId: 'W1', warningVersion: '2.0', level: 'AMBER' })], { stale: true }));

    expect(await screen.findByTestId('ask-warnings-stale')).toBeInTheDocument();
  });

  it('treats a missing version on either side as the same warning', async () => {
    await renderLive(response([warning({ warningId: 'W1', warningVersion: null, level: 'AMBER' })]));

    expect(await screen.findByTestId('ask-warning')).toBeInTheDocument();
  });
});
