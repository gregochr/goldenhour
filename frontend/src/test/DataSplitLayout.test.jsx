import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { useIsMobile } from '../hooks/useIsMobile.js';
import DataSplitLayout from '../components/data/DataSplitLayout.jsx';
import { foreignModalOver } from '../utils/mapForeignModal.js';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

function renderLayout(props = {}) {
  const onClose = vi.fn();
  render(
    <DataSplitLayout
      list={<p>the list</p>}
      panel={<p>the panel</p>}
      onClose={onClose}
      panelLabel="Entry panel"
      backLabel="Waitlist"
      {...props}
    />,
  );
  return { onClose };
}

describe('DataSplitLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useIsMobile.mockReturnValue(false);
  });

  it('draws only the list when no panel is open', () => {
    renderLayout({ panel: null });
    expect(screen.getByText('the list')).toBeInTheDocument();
    expect(screen.queryByTestId('data-split-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('data-split-scrim')).not.toBeInTheDocument();
  });

  describe('panel semantics', () => {
    it('is an aside with an accessible name, never a dialog and never aria-modal', () => {
      renderLayout();
      const panel = screen.getByRole('complementary', { name: 'Entry panel' });
      expect(panel).not.toHaveAttribute('role', 'dialog');
      expect(panel).not.toHaveAttribute('aria-modal');
      expect(document.querySelector('[role="dialog"], [aria-modal]')).toBeNull();
    });

    it('is invisible to the Map pane\'s foreign-modal predicate, on desktop, tablet and phone alike', () => {
      for (const mobile of [false, true]) {
        useIsMobile.mockReturnValue(mobile);
        const { unmount } = render(
          <DataSplitLayout list={<p>l</p>} panel={<p>p</p>} onClose={vi.fn()} panelLabel="x" backLabel="Tab" />,
        );
        expect(foreignModalOver(null)).toBe(false);
        unmount();
      }
    });
  });

  describe('desktop and tablet', () => {
    it('splits at 1000px (list + 440px panel) and overlays below it, by class', () => {
      renderLayout();
      expect(screen.getByTestId('data-split').className).toContain('min-[1000px]:grid-cols-[minmax(0,1fr)_440px]');
      const panel = screen.getByTestId('data-split-panel');
      expect(panel.className).toContain('absolute');
      expect(panel.className).toContain('w-[440px]');
      expect(panel.className).toContain('min-[1000px]:static');
      expect(panel.className).toContain('shadow-[-12px_0_32px_rgba(0,0,0,.45)]');
    });

    it('keeps the list on screen beside or behind the panel', () => {
      renderLayout();
      expect(screen.getByTestId('data-split-list')).not.toHaveAttribute('hidden');
    });

    it('draws a decorative scrim that is gone from 1000px up, and pressing it closes the panel', () => {
      const { onClose } = renderLayout();
      const scrim = screen.getByTestId('data-split-scrim');
      expect(scrim).toHaveAttribute('aria-hidden', 'true');
      expect(scrim.className).toContain('min-[1000px]:hidden');
      expect(scrim.className).toContain('bg-[rgba(10,7,6,0.66)]');
      fireEvent.click(scrim);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('carries no phone-only back bar', () => {
      renderLayout();
      expect(screen.queryByTestId('data-split-backbar')).not.toBeInTheDocument();
      expect(screen.getByTestId('data-split-panel').className).not.toContain('safe-');
    });
  });

  describe('phone (639px and under)', () => {
    beforeEach(() => useIsMobile.mockReturnValue(true));

    it('hides the list behind the panel but keeps it mounted, so its state survives', () => {
      renderLayout();
      expect(screen.getByTestId('data-split-list')).toHaveAttribute('hidden');
      expect(screen.getByText('the list')).toBeInTheDocument();
      expect(screen.getByText('the list')).not.toBeVisible();
    });

    it('has no scrim and draws the panel in flow, not over the list', () => {
      renderLayout();
      expect(screen.queryByTestId('data-split-scrim')).not.toBeInTheDocument();
      expect(screen.getByTestId('data-split-panel').className).not.toContain('absolute');
    });

    it('draws a 52px back bar whose 44px button names the tab and closes the panel', () => {
      const { onClose } = renderLayout();
      expect(screen.getByTestId('data-split-backbar').className).toContain('h-[52px]');
      const back = screen.getByRole('button', { name: 'Back to Waitlist' });
      expect(back.className).toContain('min-h-[44px]');
      fireEvent.click(back);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('puts the primary action on the right of the back bar', () => {
      renderLayout({ backAction: <button type="button">Edit</button> });
      expect(screen.getByTestId('data-split-backbar')).toContainElement(screen.getByRole('button', { name: 'Edit' }));
    });

    it('respects the safe-area insets', () => {
      renderLayout();
      const cls = screen.getByTestId('data-split-panel').className;
      expect(cls).toContain('pb-[var(--safe-b)]');
      expect(cls).toContain('pl-[var(--safe-l)]');
      expect(cls).toContain('pr-[var(--safe-r)]');
    });

    it('shows the list again when no panel is open', () => {
      renderLayout({ panel: null });
      expect(screen.getByTestId('data-split-list')).not.toHaveAttribute('hidden');
      expect(screen.getByText('the list')).toBeInTheDocument();
    });
  });
});
