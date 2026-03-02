import { render, screen, cleanup } from '@testing-library/react';
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
});
