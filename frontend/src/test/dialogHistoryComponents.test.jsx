import React, { StrictMode, useState } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import Modal from '../components/shared/Modal.jsx';
import BottomSheet from '../components/BottomSheet.jsx';
import useBackLayer from '../hooks/useBackLayer.js';
import { registerBackLayer, __resetForTests, __inspectForTests } from '../utils/dialogHistory.js';
import { createFakeBrowser, tick } from './fakeBrowserHistory.js';

let b;

const settle = async () => {
  await act(async () => {
    await tick();
    b.flush();
    await tick();
    b.flush();
  });
};

/** The harness: a real Modal with a real BottomSheet stacked over it, standing in for consumers. */
function Harness({ modalBack = 'close', sheetBack = 'close', hasOnClose = true, log = [] }) {
  const [modal, setModal] = useState(false);
  const [sheet, setSheet] = useState(false);
  return (
    <div>
      <button type="button" data-testid="open-modal" onClick={() => setModal(true)}>modal</button>
      <button type="button" data-testid="open-sheet" onClick={() => setSheet(true)}>sheet</button>
      {modal && (
        <Modal
          label="Modal"
          {...(modalBack ? { back: modalBack } : {})}
          onClose={hasOnClose ? () => { log.push('modal'); setModal(false); } : undefined}
          data-testid="modal"
        >
          <button type="button" data-testid="modal-close" onClick={() => setModal(false)}>x</button>
        </Modal>
      )}
      <BottomSheet
        open={sheet}
        {...(sheetBack ? { back: sheetBack } : {})}
        onClose={() => { log.push('sheet'); setSheet(false); }}
        label="Sheet"
      >
        <p>sheet body</p>
      </BottomSheet>
    </div>
  );
}

beforeEach(() => {
  document.documentElement.removeAttribute('data-back-depth');
  b = createFakeBrowser();
  __resetForTests({ history: b.history, win: b.win });
});

afterEach(() => {
  __resetForTests();
  document.body.style.overflow = '';
});

describe('a real stacked Modal and BottomSheet', () => {
  it('closes the sheet, then the modal, one per Back press', async () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('open-modal'));
    fireEvent.click(screen.getByTestId('open-sheet'));
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(2);
    expect(__inspectForTests().committedDepth).toBe(2);

    b.userBack();
    await settle();
    expect(screen.queryByRole('dialog', { name: 'Sheet' })).toBeNull();
    expect(screen.getByRole('dialog', { name: 'Modal' })).toBeInTheDocument();

    b.userBack();
    await settle();
    expect(screen.queryByRole('dialog', { name: 'Modal' })).toBeNull();
    expect(b.history.go).not.toHaveBeenCalled();
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
    expect(document.body.style.overflow).toBe('');
  });

  it('traverses once when a dialog is closed with its own button', async () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('open-modal'));
    await settle();
    fireEvent.click(screen.getByTestId('modal-close'));
    await settle();
    expect(b.history.go).toHaveBeenCalledTimes(1);
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(b.index).toBe(0);
  });

  it('closes a sheet with its own close button the same way', async () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('open-sheet'));
    await settle();
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    await settle();
    expect(b.history.go).toHaveBeenCalledWith(-1);
  });

  it('treats a Modal with no onClose as blocking even when asked to close', async () => {
    render(<Harness hasOnClose={false} />);
    fireEvent.click(screen.getByTestId('open-modal'));
    await settle();
    b.userBack();
    await settle();
    expect(screen.getByRole('dialog', { name: 'Modal' })).toBeInTheDocument();
    expect(__inspectForTests().committedDepth).toBe(1);
  });

  it('absorbs Back on a back="block" modal under a closing sheet', async () => {
    const log = [];
    render(<Harness modalBack="block" log={log} />);
    fireEvent.click(screen.getByTestId('open-modal'));
    fireEvent.click(screen.getByTestId('open-sheet'));
    await settle();
    b.userBack();
    await settle();
    expect(log).toEqual(['sheet']);
    b.userBack();
    await settle();
    expect(log).toEqual(['sheet']);
    expect(screen.getByRole('dialog', { name: 'Modal' })).toBeInTheDocument();
  });

  it('runs onClose for back="cancel"', async () => {
    const log = [];
    render(<Harness modalBack="cancel" log={log} />);
    fireEvent.click(screen.getByTestId('open-modal'));
    await settle();
    b.userBack();
    await settle();
    expect(log).toEqual(['modal']);
  });

  it('registers a sheet only while it is open', async () => {
    render(<BottomSheet open={false} back="close" onClose={() => {}}>x</BottomSheet>);
    await settle();
    expect(b.history.pushState).not.toHaveBeenCalled();
    expect(__inspectForTests().layers).toBe(0);
  });

  it('unregisters on unmount (the Rewind remount, logout with a dialog open)', async () => {
    const { unmount } = render(<Harness />);
    fireEvent.click(screen.getByTestId('open-modal'));
    await settle();
    unmount();
    await settle();
    expect(b.history.go).toHaveBeenCalledWith(-1);
    expect(__inspectForTests()).toMatchObject({ layers: 0, committedDepth: 0 });
  });
});

describe('an absent back prop', () => {
  it('makes zero history calls and registers nothing, for Modal and BottomSheet', async () => {
    Object.defineProperty(navigator, 'userActivation', { value: { isActive: true }, configurable: true });
    __resetForTests();
    const spies = ['pushState', 'replaceState', 'go', 'back', 'forward'].map((m) => vi.spyOn(window.history, m));
    const { unmount } = render(
      <Harness modalBack={null} sheetBack={null} />,
    );
    fireEvent.click(screen.getByTestId('open-modal'));
    fireEvent.click(screen.getByTestId('open-sheet'));
    await tick();
    // Both dialogs are open, and neither has registered anything.
    expect(screen.getByRole('dialog', { name: 'Modal' })).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Sheet' })).toBeInTheDocument();
    expect(__inspectForTests().layers).toBe(0);
    fireEvent.click(screen.getByTestId('modal-close'));
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    await tick();
    unmount();
    await tick();
    delete navigator.userActivation;
    spies.forEach((spy) => expect(spy).not.toHaveBeenCalled());
    expect(__inspectForTests().layers).toBe(0);
    expect(document.documentElement.hasAttribute('data-back-depth')).toBe(false);
    vi.restoreAllMocks();
  });
});

describe('an absent back prop on the Chromium fake', () => {
  it('touches neither history nor scrollRestoration', async () => {
    render(<Harness modalBack={null} sheetBack={null} />);
    fireEvent.click(screen.getByTestId('open-modal'));
    fireEvent.click(screen.getByTestId('open-sheet'));
    await settle();
    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    fireEvent.click(screen.getByTestId('modal-close'));
    await settle();
    expect(b.log).toEqual([]);
    expect(b.history.go).not.toHaveBeenCalled();
  });
});

describe('BottomSheet scroll lock', () => {
  const Two = ({ a, b: second }) => (
    <>
      <BottomSheet open={a} onClose={() => {}}>a</BottomSheet>
      <BottomSheet open={second} onClose={() => {}}>b</BottomSheet>
    </>
  );

  it('releases the lock only when the last sheet closes, even if both close in one commit', () => {
    const { rerender } = render(<Two a b />);
    expect(document.body.style.overflow).toBe('hidden');
    rerender(<Two a={false} b={false} />);
    expect(document.body.style.overflow).toBe('');
  });

  it('keeps the page locked while one sheet is still open', () => {
    const { rerender } = render(<Two a b />);
    rerender(<Two a={false} b />);
    expect(document.body.style.overflow).toBe('hidden');
    rerender(<Two a={false} b={false} />);
    expect(document.body.style.overflow).toBe('');
  });

  it('restores an overflow the page had before the first lock', () => {
    document.body.style.overflow = 'scroll';
    const { rerender } = render(<Two a b />);
    rerender(<Two a={false} b={false} />);
    expect(document.body.style.overflow).toBe('scroll');
  });
});

describe('useBackLayer', () => {
  function Probe({ open = true, onBack, mode }) {
    useBackLayer({ open, onBack, mode });
    return null;
  }

  it('nets one push under StrictMode', async () => {
    render(<StrictMode><Probe onBack={() => {}} /></StrictMode>);
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
  });

  it('calls the latest onBack and updates mode in place, without touching history', async () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = render(<Probe onBack={first} mode="block" />);
    await settle();
    rerender(<Probe onBack={second} mode="block" />);
    rerender(<Probe onBack={second} mode="close" />);
    await settle();
    expect(b.history.pushState).toHaveBeenCalledTimes(1);
    expect(b.history.go).not.toHaveBeenCalled();
    b.userBack();
    await settle();
    expect(second).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
  });

  it('tolerates a missing onBack', async () => {
    render(<Probe />);
    await settle();
    b.userBack();
    await settle();
    // The popped layer is still mounted (nothing closed it), so it is re-adopted entry-less after a task.
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 5); }); });
    expect(__inspectForTests()).toMatchObject({ layers: 1, entried: 0, committedDepth: 0 });
  });

  it('registers and unregisters as open flips', async () => {
    const { rerender } = render(<Probe open={false} onBack={() => {}} />);
    await settle();
    expect(__inspectForTests().layers).toBe(0);
    rerender(<Probe open onBack={() => {}} />);
    await settle();
    expect(__inspectForTests().layers).toBe(1);
    rerender(<Probe open={false} onBack={() => {}} />);
    await settle();
    expect(__inspectForTests().layers).toBe(0);
  });

  it('is usable from a class component through registerBackLayer', async () => {
    const onBack = vi.fn();
    class Legacy extends React.Component {
      componentDidMount() { this.layer = registerBackLayer({ onBack, mode: 'close' }); }

      componentWillUnmount() { this.layer.release(); }

      render() { return <p>legacy</p>; }
    }
    render(<Legacy />);
    await settle();
    b.userBack();
    await settle();
    expect(onBack).toHaveBeenCalledTimes(1);
  });
});

describe('real jsdom history (wiring only)', () => {
  afterEach(() => {
    delete navigator.userActivation;
  });

  it('pushes a marked entry and closes the dialog on a real history.back()', async () => {
    Object.defineProperty(navigator, 'userActivation', { value: { isActive: true }, configurable: true });
    __resetForTests();
    render(<Harness />);
    fireEvent.click(screen.getByTestId('open-modal'));
    await waitFor(() => expect(window.history.state?.phLayer?.depth).toBe(1));
    window.history.back();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Modal' })).toBeNull());
    expect(window.history.state?.phLayer).toBeUndefined();
  });
});
