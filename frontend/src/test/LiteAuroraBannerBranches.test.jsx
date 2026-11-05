import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
// The gate is the util's own concern (covered elsewhere); here it lets an alert through whose level
// the banner's tables do not know, to pin the banner's own fallbacks.
vi.mock('../utils/auroraLiteAlert.js', () => ({ isLiteAlertShown: () => true }));
vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

import LiteAuroraBanner from '../components/LiteAuroraBanner.jsx';

describe('LiteAuroraBanner unknown level', () => {
  it('words an unrecognised level as a plain "Alert"', () => {
    render(<LiteAuroraBanner liteAlert={{ level: 'MYSTERY', active: true, triggerType: 'forecast' }} />);
    expect(screen.getByTestId('aurora-banner-pill')).toHaveTextContent('Alert');
    expect(screen.getByTestId('aurora-banner-when')).toHaveTextContent('Forecast · tonight');
  });
});
