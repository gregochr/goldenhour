import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuroraBanner from '../components/AuroraBanner.jsx';

/**
 * The desktop banner's collapsed strip and expand toggle (docs/engineering/aurora-banner-collapse-plan.md).
 * The mocks follow AuroraBanner.test.jsx: the layout, the hooks and the lazy thumbnail are values the
 * test hands the banner, so each rule about WHICH layout shows can be failed on its own.
 */
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: vi.fn() }));
vi.mock('../hooks/useAuroraScores.js', () => ({ useAuroraScores: vi.fn() }));

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

let mockBriefing;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => mockBriefing,
}));

vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: function ThumbStub() {
    return <div data-testid="aurora-field-stub" />;
  },
}));

import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

const MODERATE = {
  level: 'MODERATE',
  hexColour: '#ff0000',
  description: 'Possible aurora on the northern horizon',
  active: true,
  triggerType: 'forecast',
};
const STRONG = { ...MODERATE, level: 'STRONG', description: 'Aurora likely across northern England' };

const layout = () => screen.getByTestId('aurora-banner').getAttribute('data-layout');
const toggle = () => screen.getByTestId('aurora-banner-toggle');

/** Renders the banner for `status`, returning the view and a `show` that swaps the status under it. */
function mountBanner(status, props = {}) {
  useAuroraStatus.mockReturnValue({ status, loading: false });
  const view = render(<AuroraBanner {...props} />);
  const show = (next) => {
    useAuroraStatus.mockReturnValue({ status: next, loading: false });
    view.rerender(<AuroraBanner {...props} />);
  };
  return { view, show };
}

describe('AuroraBanner collapse (640px and up)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsMobile = false;
    useAuroraViewline.mockReturnValue({ viewline: null, source: null });
    // A rated run exists, so the banner has a door: the door tests below cannot pass vacuously.
    useAuroraScores.mockReturnValue({ ready: true, spots: [] });
    mockBriefing = { heatSpots: [], reachById: new Map(), effectiveReachById: new Map(), origin: null };
  });

  describe('the toggle', () => {
    it('shows a MODERATE alert as the strip, expands it to the card and collapses it again', () => {
      mountBanner(MODERATE);
      expect(layout()).toBe('strip');
      expect(toggle()).toHaveAttribute('aria-expanded', 'false');
      expect(toggle()).toHaveAccessibleName('Show aurora details');

      fireEvent.click(toggle());
      expect(layout()).toBe('card');
      expect(toggle()).toHaveAttribute('aria-expanded', 'true');
      expect(toggle()).toHaveAccessibleName('Hide aurora details');

      fireEvent.click(toggle());
      expect(layout()).toBe('strip');
      expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    });

    it('is not the door: neither click calls onViewOnMap', () => {
      const onViewOnMap = vi.fn();
      mountBanner(MODERATE, { onViewOnMap });
      fireEvent.click(toggle());
      fireEvent.click(toggle());
      expect(onViewOnMap).not.toHaveBeenCalled();
    });

    it('is not the door from the keyboard: Enter and Space on it never call onViewOnMap', async () => {
      const user = userEvent.setup();
      const onViewOnMap = vi.fn();
      mountBanner(MODERATE, { onViewOnMap });
      // Control: the strip's own door does answer a key, so the negative below is not vacuous.
      screen.getByTestId('aurora-banner').focus();
      await user.keyboard('{Enter}');
      expect(onViewOnMap).toHaveBeenCalledTimes(1);
      onViewOnMap.mockClear();

      toggle().focus();
      await user.keyboard('{Enter}');
      expect(layout()).toBe('card');
      toggle().focus();
      await user.keyboard(' ');
      expect(layout()).toBe('strip');
      expect(onViewOnMap).not.toHaveBeenCalled();
    });

    it('opens a STRONG alert as the card', () => {
      mountBanner(STRONG);
      expect(layout()).toBe('card');
      expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    });

    it('renders no toggle on a phone, whatever the level', () => {
      mockIsMobile = true;
      const { show } = mountBanner(MODERATE);
      expect(layout()).toBe('strip');
      expect(screen.queryByTestId('aurora-banner-toggle')).not.toBeInTheDocument();
      show(STRONG);
      expect(layout()).toBe('strip');
      expect(screen.queryByTestId('aurora-banner-toggle')).not.toBeInTheDocument();
    });

    it('keeps role="alert" on the root in both states', () => {
      mountBanner(MODERATE);
      expect(screen.getByRole('alert')).toBe(screen.getByTestId('aurora-banner'));
      fireEvent.click(toggle());
      expect(screen.getByRole('alert')).toBe(screen.getByTestId('aurora-banner'));
    });
  });

  describe('the override follows the alert, not the clock', () => {
    it('follows an escalation: collapsed at MODERATE, the card at STRONG', () => {
      const { show } = mountBanner(MODERATE);
      expect(layout()).toBe('strip');
      show(STRONG);
      expect(layout()).toBe('card');
    });

    it('follows a de-escalation: collapsed at STRONG, back to the strip at MODERATE', () => {
      const { show } = mountBanner(STRONG);
      fireEvent.click(toggle());
      expect(layout()).toBe('strip');
      show(MODERATE);
      expect(layout()).toBe('strip');
    });

    it('does not bring an old choice back: expand at MODERATE, escalate untouched, drop back to the strip', () => {
      const { show } = mountBanner(MODERATE);
      fireEvent.click(toggle());
      expect(layout()).toBe('card');
      show(STRONG);
      expect(layout()).toBe('card');
      show(MODERATE);
      expect(layout()).toBe('strip');
    });

    it('forgets the choice when the banner stops rendering and the same alert returns', () => {
      const { show } = mountBanner(MODERATE);
      fireEvent.click(toggle());
      expect(layout()).toBe('card');
      show(null);
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
      show(MODERATE);
      expect(layout()).toBe('strip');
    });

    it('treats a simulated alert and a real one at the same level as different alerts', () => {
      const { show } = mountBanner({ ...MODERATE, simulated: true });
      fireEvent.click(toggle());
      expect(layout()).toBe('card');
      show(MODERATE);
      expect(layout()).toBe('strip');
    });

    it('keeps the choice when a forecast alert is re-triggered in real time at the same level', () => {
      const { show } = mountBanner(MODERATE);
      fireEvent.click(toggle());
      show({ ...MODERATE, triggerType: 'realtime' });
      expect(layout()).toBe('card');
    });

    it('keeps the choice across a refresh of the same alert', () => {
      const { show } = mountBanner(MODERATE);
      fireEvent.click(toggle());
      show({ ...MODERATE, kp: 6.1 });
      expect(layout()).toBe('card');
    });
  });

  describe('focus', () => {
    it('follows the toggle to its counterpart when it was pressed from the keyboard', async () => {
      const user = userEvent.setup();
      mountBanner(MODERATE);
      toggle().focus();
      await user.keyboard('{Enter}');
      expect(layout()).toBe('card');
      expect(document.activeElement).toBe(toggle());
      expect(toggle()).toHaveAttribute('aria-expanded', 'true');

      await user.keyboard('{Enter}');
      expect(layout()).toBe('strip');
      expect(document.activeElement).toBe(toggle());
      expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    });

    it('does not take focus when the toggle never had it (Safari does not focus on a mouse click)', () => {
      mountBanner(MODERATE);
      fireEvent.click(toggle());
      expect(layout()).toBe('card');
      expect(document.activeElement).not.toBe(toggle());
    });

    it('keeps focus on the toggle across an automatic escalation and de-escalation', () => {
      const { show } = mountBanner(MODERATE);
      toggle().focus();
      show(STRONG);
      expect(layout()).toBe('card');
      expect(document.activeElement).toBe(toggle());
      show(MODERATE);
      expect(layout()).toBe('strip');
      expect(document.activeElement).toBe(toggle());
    });

    it('keeps focus on the dismiss button across an automatic escalation and de-escalation', () => {
      const { show } = mountBanner(MODERATE);
      screen.getByTestId('aurora-banner-dismiss').focus();
      show(STRONG);
      expect(layout()).toBe('card');
      expect(document.activeElement).toBe(screen.getByTestId('aurora-banner-dismiss'));
      show(MODERATE);
      expect(layout()).toBe('strip');
      expect(document.activeElement).toBe(screen.getByTestId('aurora-banner-dismiss'));
    });

    it('does not steal focus from elsewhere when the level changes', () => {
      const { show } = mountBanner(MODERATE);
      const other = document.createElement('button');
      document.body.appendChild(other);
      toggle().focus();
      other.focus();
      show(STRONG);
      expect(document.activeElement).toBe(other);
      other.remove();
    });

    it('does not move focus onto the toggle when an escalation finds it elsewhere', () => {
      const { show } = mountBanner(MODERATE);
      screen.getByTestId('aurora-banner-dismiss').focus();
      show(STRONG);
      expect(layout()).toBe('card');
      expect(document.activeElement).not.toBe(toggle());
    });
  });

  describe('the viewline', () => {
    const FIELD = { id: 1, name: 'Kielder', lat: 55.2, lng: -2.6, rid: 'Kielder', stars: 5 };
    const asked = () => useAuroraViewline.mock.calls.at(-1)[0];
    const everAsked = () => useAuroraViewline.mock.calls.some(([enabled]) => enabled === true);

    beforeEach(() => {
      useAuroraScores.mockReturnValue({ ready: true, spots: [FIELD] });
    });

    it('makes no request while collapsed, and asks once expanded', () => {
      mountBanner(MODERATE);
      expect(asked()).toBe(false);
      expect(everAsked()).toBe(false);
      fireEvent.click(toggle());
      expect(asked()).toBe(true);
      fireEvent.click(toggle());
      expect(asked()).toBe(false);
    });

    it('asks at once for a STRONG alert, which opens expanded', () => {
      mountBanner(STRONG);
      expect(asked()).toBe(true);
    });
  });

  describe('dismiss', () => {
    it('still dismisses the strip, and the dismissal holds when the alert is not escalated', () => {
      const { show } = mountBanner(MODERATE);
      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
      show({ ...MODERATE });
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });

    it('still dismisses the card, without opening the door', () => {
      const onViewOnMap = vi.fn();
      mountBanner(STRONG, { onViewOnMap });
      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
      expect(onViewOnMap).not.toHaveBeenCalled();
    });
  });
});
