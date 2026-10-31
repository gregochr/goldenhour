import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PropTypes from 'prop-types';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import DataSplitLayout from '../components/data/DataSplitLayout.jsx';
import { foreignModalOver } from '../utils/mapForeignModal.js';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

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
    useIsSplitLayout.mockReturnValue(true);
    window.scrollTo = vi.fn();
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

  describe('the list while a panel is open', () => {
    it('stays interactive in split mode (1000px and up)', () => {
      useIsSplitLayout.mockReturnValue(true);
      renderLayout();
      expect(screen.getByTestId('data-split-list')).not.toHaveAttribute('inert');
    });

    it('is inert in overlay mode, so Tab and clicks cannot reach what the scrim covers', () => {
      useIsSplitLayout.mockReturnValue(false);
      renderLayout();
      expect(screen.getByTestId('data-split-list')).toHaveAttribute('inert');
    });

    it('is inert on the phone page too', () => {
      useIsSplitLayout.mockReturnValue(false);
      useIsMobile.mockReturnValue(true);
      renderLayout();
      expect(screen.getByTestId('data-split-list')).toHaveAttribute('inert');
    });

    it('is never inert with no panel open', () => {
      useIsSplitLayout.mockReturnValue(false);
      renderLayout({ panel: null });
      expect(screen.getByTestId('data-split-list')).not.toHaveAttribute('inert');
    });
  });

  describe('keeping the panel in view', () => {
    it('rides in a box that sticks to the top and is no taller than the viewport', () => {
      renderLayout();
      const box = screen.getByTestId('data-split-panel-box').className;
      expect(box).toContain('sticky');
      expect(box).toContain('top-0');
      expect(box).toContain('max-h-[min(100dvh,100%)]');
      expect(box).toContain('min-h-0');
    });

    it('is not boxed on the phone page, which scrolls as a page', () => {
      useIsMobile.mockReturnValue(true);
      renderLayout();
      expect(screen.getByTestId('data-split-panel-box').className).not.toContain('sticky');
    });
  });

  describe('focus', () => {
    const TITLE = <h2 tabIndex={-1} data-panel-title="" data-testid="title">Entry</h2>;

    function Harness(props) {
      return (
        <DataSplitLayout
          list={(
            <>
              <h2>The list</h2>
              <input type="search" data-testid="search" />
              <button type="button" data-testid="grid-row-5">row</button>
            </>
          )}
          onClose={vi.fn()}
          panelLabel="x"
          backLabel="Tab"
          rowTestIdPrefix="grid"
          {...props}
        />
      );
    }

    Harness.propTypes = { panel: PropTypes.node, selectionKey: PropTypes.number, list: PropTypes.node };

    it('moves focus to the panel title when a panel opens', () => {
      const { rerender } = render(<Harness panel={null} />);
      rerender(<Harness panel={TITLE} selectionKey={5} />);
      expect(screen.getByTestId('title')).toHaveFocus();
    });

    it('moves focus to the new title when the open entry changes', () => {
      const { rerender } = render(<Harness panel={TITLE} selectionKey={5} />);
      screen.getByTestId('search').focus();
      rerender(<Harness panel={TITLE} selectionKey={6} />);
      expect(screen.getByTestId('title')).toHaveFocus();
    });

    it('returns focus to the row that opened the panel when it closes', () => {
      const { rerender } = render(<Harness panel={null} />);
      rerender(<Harness panel={TITLE} selectionKey={5} />);
      rerender(<Harness panel={null} selectionKey={null} />);
      expect(screen.getByTestId('grid-row-5')).toHaveFocus();
    });

    it('falls back to the search box when that row is gone', () => {
      const { rerender } = render(<Harness panel={null} />);
      rerender(<Harness panel={TITLE} selectionKey={99} />);
      rerender(<Harness panel={null} selectionKey={null} />);
      expect(screen.getByTestId('search')).toHaveFocus();
    });

    it('falls back to the list heading when there is no search box either', () => {
      const bare = { list: <h2>The list</h2> };
      const { rerender } = render(<Harness {...bare} panel={null} />);
      rerender(<Harness {...bare} panel={TITLE} selectionKey={99} />);
      rerender(<Harness {...bare} panel={null} selectionKey={null} />);
      expect(screen.getByRole('heading', { name: 'The list' })).toHaveFocus();
    });

    it('does not take focus the reader has moved elsewhere', () => {
      const { rerender } = render(<Harness panel={null} />);
      rerender(<Harness panel={TITLE} selectionKey={5} />);
      screen.getByTestId('search').focus();
      rerender(<Harness panel={null} selectionKey={null} />);
      expect(screen.getByTestId('search')).toHaveFocus();
    });
  });

  describe('phone scroll position', () => {
    it('opens the page at the top and puts the list back where it was scrolled to', () => {
      useIsMobile.mockReturnValue(true);
      Object.defineProperty(window, 'scrollY', { value: 480, configurable: true });
      const { rerender } = render(
        <DataSplitLayout list={<p>l</p>} panel={null} onClose={vi.fn()} panelLabel="x" backLabel="Tab" />,
      );
      fireEvent.scroll(window);
      rerender(<DataSplitLayout list={<p>l</p>} panel={<p>p</p>} onClose={vi.fn()} panelLabel="x" backLabel="Tab" />);
      expect(window.scrollTo).toHaveBeenLastCalledWith(0, 0);
      Object.defineProperty(window, 'scrollY', { value: 0, configurable: true });
      rerender(<DataSplitLayout list={<p>l</p>} panel={null} onClose={vi.fn()} panelLabel="x" backLabel="Tab" />);
      expect(window.scrollTo).toHaveBeenLastCalledWith(0, 480);
    });

    it('does not touch the scroll position on tablet or desktop', () => {
      renderLayout();
      expect(window.scrollTo).not.toHaveBeenCalled();
    });
  });
  describe('mode changes and the phone back button', () => {
    function Harness({ focusKey }) {
      return (
        <DataSplitLayout
          list={<p>the list</p>}
          panel={<h2 tabIndex={-1} data-panel-title="">Title<button type="button">inner</button></h2>}
          onClose={vi.fn()}
          panelLabel="Entry panel"
          backLabel="Waitlist"
          selectionKey={1}
          focusKey={focusKey}
        />
      );
    }
    Harness.propTypes = { focusKey: PropTypes.string.isRequired };

    it('moves focus to the title again when only the focusKey (the panel mode) changes', () => {
      const { rerender } = render(<Harness focusKey="1:view" />);
      const title = screen.getByRole('heading', { name: /Title/ });
      expect(title).toHaveFocus();

      screen.getByRole('button', { name: 'inner' }).focus();
      rerender(<Harness focusKey="1:edit" />);

      expect(title).toHaveFocus();
    });

    it('does not move focus when nothing about the mode changed', () => {
      const { rerender } = render(<Harness focusKey="1:view" />);
      const inner = screen.getByRole('button', { name: 'inner' });
      inner.focus();

      rerender(<Harness focusKey="1:view" />);

      expect(inner).toHaveFocus();
    });

    it('the phone back button calls onBack when given, and onClose otherwise', () => {
      useIsMobile.mockReturnValue(true);
      const onBack = vi.fn();
      const { onClose } = renderLayout({ onBack });
      fireEvent.click(screen.getByTestId('data-split-back'));
      expect(onBack).toHaveBeenCalledTimes(1);
      expect(onClose).not.toHaveBeenCalled();
    });
  });
});
