import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ChunkErrorBoundary from '../components/shared/ChunkErrorBoundary.jsx';

function Boom() {
  throw new Error('chunk gone');
}

function setup(props = {}, child = <Boom />) {
  return render(<ChunkErrorBoundary {...props}>{child}</ChunkErrorBoundary>);
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ChunkErrorBoundary', () => {
  it('renders its children untouched while nothing has failed', () => {
    setup({}, <p>fine</p>);
    expect(screen.getByText('fine')).toBeInTheDocument();
    expect(screen.queryByTestId('chunk-error')).toBeNull();
  });

  it('defaults to a compact inline block naming the generic surface', () => {
    setup({ id: 'dock-1' });
    expect(screen.getByTestId('chunk-error')).toHaveTextContent("Couldn't load — This part of the app didn't arrive.");
    const dock = screen.getByRole('complementary', { name: 'This part of the app' });
    expect(dock).toHaveAttribute('id', 'dock-1');
  });

  it('names the failed surface in the message and heading', () => {
    setup({ label: 'Settings' });
    expect(screen.getByTestId('chunk-error')).toHaveTextContent("Couldn't load — Settings didn't arrive.");
  });

  it('page variant fills the screen without a dialog', () => {
    setup({ variant: 'page' });
    expect(screen.getByTestId('chunk-error')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('complementary')).toBeNull();
  });

  it('overlay variant is a labelled modal dialog portalled to the body', () => {
    const { container } = setup({ variant: 'overlay', label: 'Ask' });
    const dialog = screen.getByRole('dialog', { name: 'Ask' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(container).not.toContainElement(dialog);
    expect(document.body).toContainElement(dialog);
  });

  it('renders nothing for a failure while closed', () => {
    setup({ open: false });
    expect(screen.queryByTestId('chunk-error')).toBeNull();
  });

  it('calls the supplied reload instead of reloading the page', () => {
    const reload = vi.fn();
    setup({ reload });
    fireEvent.click(screen.getByTestId('chunk-error-reload'));
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('offers Close only when onClose is given', () => {
    const { unmount } = setup();
    expect(screen.queryByTestId('chunk-error-close')).toBeNull();
    unmount();
    setup({ onClose: vi.fn() });
    expect(screen.getByTestId('chunk-error-close')).toBeInTheDocument();
  });

  describe('Close focus return', () => {
    let rafCallbacks;

    beforeEach(() => {
      rafCallbacks = [];
      vi.stubGlobal('requestAnimationFrame', (cb) => { rafCallbacks.push(cb); return rafCallbacks.length; });
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    it('calls onClose and returns focus to the control that opened the surface', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();
      const focusSpy = vi.spyOn(opener, 'focus');
      const onClose = vi.fn();
      setup({ onClose, variant: 'overlay' });

      fireEvent.click(screen.getByTestId('chunk-error-close'));
      expect(onClose).toHaveBeenCalledTimes(1);
      act(() => rafCallbacks.forEach((cb) => cb()));
      expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true });
      opener.remove();
    });

    it('does not move focus when the opener has left the page by the next frame', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();
      const focusSpy = vi.spyOn(opener, 'focus');
      setup({ onClose: vi.fn() });

      fireEvent.click(screen.getByTestId('chunk-error-close'));
      opener.remove();
      act(() => rafCallbacks.forEach((cb) => cb()));
      expect(focusSpy).not.toHaveBeenCalled();
    });

    it('schedules no focus return when focus was on the body', () => {
      document.activeElement?.blur?.();
      const onClose = vi.fn();
      setup({ onClose });

      fireEvent.click(screen.getByTestId('chunk-error-close'));
      expect(onClose).toHaveBeenCalledTimes(1);
      expect(rafCallbacks).toHaveLength(0);
    });
  });
});
