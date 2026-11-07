import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// A misconfigured VITE_GUIDE_ORIGIN equal to the app's own origin would void the iframe sandbox
// (allow-scripts + allow-same-origin), so the panel must refuse to frame the guide.
vi.mock('../utils/guide.js', async (importOriginal) => ({
  ...(await importOriginal()),
  GUIDE_ORIGIN: window.location.origin,
}));

import GuidePanel from '../components/guide/GuidePanel.jsx';

describe('GuidePanel when the guide origin equals the app origin', () => {
  it('renders no frame and reports the misconfiguration', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <GuidePanel presentation="docked" page="map" nonce={1} onTrack={vi.fn()} onClose={vi.fn()} />,
    );

    expect(screen.getByTestId('guide-dock')).toBeInTheDocument();
    expect(screen.queryByTestId('guide-frame')).toBeNull();
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('GUIDE_ORIGIN equals the app origin'));
    consoleError.mockRestore();
  });
});
