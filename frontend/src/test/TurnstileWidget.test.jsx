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
