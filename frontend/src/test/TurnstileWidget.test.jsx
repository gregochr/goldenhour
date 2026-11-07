import { render, screen, cleanup, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import TurnstileWidget from '../components/TurnstileWidget.jsx';

describe('TurnstileWidget', () => {
  let renderOpts;

  beforeEach(() => {
    renderOpts = {};
    window.turnstile = {
      render: vi.fn((_container, opts) => {
        renderOpts = opts;
        return 'widget-42';
      }),
      remove: vi.fn(),
    };
  });

  afterEach(() => {
    delete window.turnstile;
    vi.useRealTimers();
  });

  it('renders the container element', () => {
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    expect(screen.getByTestId('turnstile-widget')).toBeInTheDocument();
  });

  it('calls window.turnstile.render on mount', () => {
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    expect(window.turnstile.render).toHaveBeenCalledTimes(1);
  });

  it('invokes onVerify when the callback fires', () => {
    const onVerify = vi.fn();
    render(<TurnstileWidget onVerify={onVerify} onExpire={vi.fn()} />);
    renderOpts.callback('token-abc');
    expect(onVerify).toHaveBeenCalledWith('token-abc');
  });

  it('invokes onExpire when the expired-callback fires', () => {
    const onExpire = vi.fn();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={onExpire} />);
    renderOpts['expired-callback']();
    expect(onExpire).toHaveBeenCalled();
  });

  it('cleans up widget on unmount', () => {
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    cleanup();
    expect(window.turnstile.remove).toHaveBeenCalledWith('widget-42');
  });

  it('calls onLoadFail when window.turnstile.render throws', () => {
    window.turnstile.render = vi.fn(() => { throw new Error('Domain not whitelisted'); });
    const onLoadFail = vi.fn();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} onLoadFail={onLoadFail} />);
    expect(onLoadFail).toHaveBeenCalledTimes(1);
  });

  it('does not crash the component when render throws and onLoadFail is omitted', () => {
    window.turnstile.render = vi.fn(() => { throw new Error('Domain not whitelisted'); });
    expect(() =>
      render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />)
    ).not.toThrow();
    expect(screen.getByTestId('turnstile-widget')).toBeInTheDocument();
  });

  it('calls onLoadFail after 10 s when window.turnstile never loads', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const onLoadFail = vi.fn();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} onLoadFail={onLoadFail} />);
    expect(onLoadFail).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(10000));
    expect(onLoadFail).toHaveBeenCalledTimes(1);
  });
});

describe('TurnstileWidget script and polling', () => {
  const SCRIPT_SELECTOR = 'script[src="https://challenges.cloudflare.com/turnstile/v0/api.js"]';

  afterEach(() => {
    delete window.turnstile;
    document.querySelectorAll(SCRIPT_SELECTOR).forEach((s) => s.remove());
    vi.useRealTimers();
  });

  it('injects the Turnstile script once, even across several mounts', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const first = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    const second = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    const scripts = document.querySelectorAll(SCRIPT_SELECTOR);
    expect(scripts).toHaveLength(1);
    expect(scripts[0].async).toBe(true);
    expect(scripts[0].defer).toBe(true);
    first.unmount();
    second.unmount();
  });

  it('renders as soon as the script finishes loading, via the poll', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const onVerify = vi.fn();
    render(<TurnstileWidget onVerify={onVerify} onExpire={vi.fn()} />);
    let opts;
    window.turnstile = {
      render: vi.fn((_c, o) => { opts = o; return 'late-1'; }),
      remove: vi.fn(),
    };
    act(() => vi.advanceTimersByTime(200));
    expect(window.turnstile.render).toHaveBeenCalledTimes(1);
    opts.callback('late-token');
    expect(onVerify).toHaveBeenCalledWith('late-token');
    // polling stopped: no second render on later ticks
    act(() => vi.advanceTimersByTime(2000));
    expect(window.turnstile.render).toHaveBeenCalledTimes(1);
  });

  it('removes a widget rendered via the poll on unmount', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const { unmount } = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    window.turnstile = { render: vi.fn(() => 'late-2'), remove: vi.fn() };
    act(() => vi.advanceTimersByTime(200));
    unmount();
    expect(window.turnstile.remove).toHaveBeenCalledWith('late-2');
  });

  it('stops polling on unmount so a late script load renders nothing', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const { unmount } = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    unmount();
    window.turnstile = { render: vi.fn(), remove: vi.fn() };
    act(() => vi.advanceTimersByTime(1000));
    expect(window.turnstile.render).not.toHaveBeenCalled();
  });

  it('does not throw when the script never loads and onLoadFail is omitted', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    expect(() => act(() => vi.advanceTimersByTime(10000))).not.toThrow();
  });

  it('unmounts cleanly when the Turnstile API has vanished since render', () => {
    window.turnstile = { render: vi.fn(() => 'w'), remove: vi.fn() };
    const remove = window.turnstile.remove;
    const { unmount } = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    delete window.turnstile;
    expect(() => unmount()).not.toThrow();
    expect(remove).not.toHaveBeenCalled();
  });

  it('unmounts cleanly after a polled render when the API has vanished', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const { unmount } = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    window.turnstile = { render: vi.fn(() => 'p'), remove: vi.fn() };
    act(() => vi.advanceTimersByTime(200));
    delete window.turnstile;
    expect(() => unmount()).not.toThrow();
  });

  it('signals onLoadFail when a polled render throws', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const onLoadFail = vi.fn();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} onLoadFail={onLoadFail} />);
    window.turnstile = { render: vi.fn(() => { throw new Error('bad sitekey'); }), remove: vi.fn() };
    act(() => vi.advanceTimersByTime(200));
    expect(onLoadFail).toHaveBeenCalledTimes(1);
  });

  it('renders nothing when the component unmounts in the same tick the script appears', () => {
    delete window.turnstile;
    vi.useFakeTimers();
    const api = { render: vi.fn(() => 'race'), remove: vi.fn() };
    const { unmount } = render(<TurnstileWidget onVerify={vi.fn()} onExpire={vi.fn()} />);
    let armed = true;
    Object.defineProperty(window, 'turnstile', {
      configurable: true,
      get() {
        if (armed) {
          armed = false;
          unmount();
        }
        return api;
      },
    });
    act(() => vi.advanceTimersByTime(200));
    expect(api.render).not.toHaveBeenCalled();
  });

  it('routes the error-callback to onExpire', () => {
    let opts;
    window.turnstile = { render: vi.fn((_c, o) => { opts = o; return 'w'; }), remove: vi.fn() };
    const onExpire = vi.fn();
    render(<TurnstileWidget onVerify={vi.fn()} onExpire={onExpire} />);
    opts['error-callback']();
    expect(onExpire).toHaveBeenCalledTimes(1);
  });

  it('uses the latest callbacks after a re-render without re-rendering the widget', () => {
    let opts;
    window.turnstile = { render: vi.fn((_c, o) => { opts = o; return 'w'; }), remove: vi.fn() };
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = render(<TurnstileWidget onVerify={first} onExpire={vi.fn()} />);
    rerender(<TurnstileWidget onVerify={second} onExpire={vi.fn()} />);
    opts.callback('t');
    expect(second).toHaveBeenCalledWith('t');
    expect(first).not.toHaveBeenCalled();
    expect(window.turnstile.render).toHaveBeenCalledTimes(1);
  });
});
