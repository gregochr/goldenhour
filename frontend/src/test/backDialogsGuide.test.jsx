import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import GuidePanel from '../components/guide/GuidePanel.jsx';
import SettingsFallback from '../components/SettingsFallback.jsx';
import ChunkErrorBoundary from '../components/shared/ChunkErrorBoundary.jsx';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * Back on the guide's two modal presentations, the Settings loading stand-in and the chunk-failure
 * overlay (back-closes-dialogs-plan.md, PR 2a). The docked guide is not a dialog and never registers.
 */

let b;

const baseProps = {
  page: 'map', hash: '', nonce: 1, focusKey: 1, onTrack: vi.fn(), onClose: vi.fn(),
};

function Boom() {
  throw new Error('chunk gone');
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  b = installBackBrowser();
});

afterEach(() => {
  uninstallBackBrowser();
  vi.restoreAllMocks();
});

describe('the guide', () => {
  it('dialog presentation: Back closes it, as Escape does', async () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="dialog" onClose={onClose} />);
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('sheet presentation: Back closes it, as Escape does', async () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="sheet" onClose={onClose} />);
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('docked presentation is not a layer: no registration and no history call', async () => {
    render(<GuidePanel {...baseProps} presentation="docked" />);
    await b.settle();

    expect(__inspectForTests().layers).toBe(0);
    expect(b.history.pushState).not.toHaveBeenCalled();
  });

  it('closing the dialog with its own close control leaves no marker behind', async () => {
    function Host() {
      const [open, setOpen] = React.useState(true);
      return open ? <GuidePanel {...baseProps} presentation="dialog" onClose={() => setOpen(false)} /> : null;
    }
    render(<Host />);
    await b.settle();
    fireEvent.click(screen.getByTestId('guide-close'));
    await b.settle();
    expect(screen.queryByTestId('guide-dialog')).toBeNull();

    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
  });
});

describe('SettingsFallback', () => {
  it('Back closes Settings while its chunk is still loading', async () => {
    const onBack = vi.fn();
    render(<SettingsFallback onBack={onBack} />);
    await b.settle();

    await b.back();

    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('hands its history entry to the dialog that replaces it: one entry, no second push', async () => {
    const onBack = vi.fn();
    const { rerender } = render(<SettingsFallback onBack={onBack} />);
    await b.settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);

    rerender(<GuidePanel {...baseProps} presentation="dialog" onClose={onBack} />);
    await b.settle();

    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
  });
});

describe('the ChunkErrorBoundary overlay', () => {
  it('Back runs its Close control', async () => {
    const onClose = vi.fn();
    render(<ChunkErrorBoundary variant="overlay" label="Settings" onClose={onClose}><Boom /></ChunkErrorBoundary>);
    expect(screen.getByTestId('chunk-error')).toBeInTheDocument();
    await b.settle();

    await b.back();

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('absorbs Back when the overlay has no Close control', async () => {
    render(<ChunkErrorBoundary variant="overlay" label="Settings"><Boom /></ChunkErrorBoundary>);
    await b.settle();

    await b.back();

    expect(screen.getByTestId('chunk-error')).toBeInTheDocument();
    expect(__inspectForTests()).toMatchObject({ layers: 1, committedDepth: 1 });
    expect(b.index).toBe(1);
  });

  it('registers nothing when its owner already holds the layer (backLayer={false})', async () => {
    render(<ChunkErrorBoundary variant="overlay" label="Ask" onClose={vi.fn()} backLayer={false}><Boom /></ChunkErrorBoundary>);
    await b.settle();

    expect(__inspectForTests().layers).toBe(0);
    expect(b.history.pushState).not.toHaveBeenCalled();
  });

  it('the inline and page variants register nothing', async () => {
    render(<ChunkErrorBoundary variant="inline" onClose={vi.fn()}><Boom /></ChunkErrorBoundary>);
    render(<ChunkErrorBoundary variant="page"><Boom /></ChunkErrorBoundary>);
    await b.settle();

    expect(__inspectForTests().layers).toBe(0);
    expect(b.history.pushState).not.toHaveBeenCalled();
  });
});
