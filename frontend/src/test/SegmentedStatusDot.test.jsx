import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Segmented from '../components/data/Segmented.jsx';
import StatusDot from '../components/data/StatusDot.jsx';
import PanelShell from '../components/data/PanelShell.jsx';

const OPTIONS = [
  { value: 'TODAY', label: 'Today' },
  { value: 'WEEK', label: '7 days' },
  { value: 'MONTH', label: '30 days' },
];

describe('Segmented', () => {
  it('is a labelled group with one pressed button, the chosen one', () => {
    render(<Segmented label="Range" options={OPTIONS} value="WEEK" onChange={vi.fn()} testId="range" />);
    expect(screen.getByRole('group', { name: 'Range' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Today' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('calls back with the value of the pressed option', () => {
    const onChange = vi.fn();
    render(<Segmented label="Range" options={OPTIONS} value="WEEK" onChange={onChange} testId="range" />);
    fireEvent.click(screen.getByTestId('range-month'));
    expect(onChange).toHaveBeenCalledWith('MONTH');
  });

  it('lets the caller own the value: pressing does not move the lit button by itself', () => {
    render(<Segmented label="Range" options={OPTIONS} value="WEEK" onChange={vi.fn()} testId="range" />);
    fireEvent.click(screen.getByTestId('range-today'));
    expect(screen.getByTestId('range-week')).toHaveAttribute('aria-pressed', 'true');
  });

  it('is 44px tall on a phone', () => {
    render(<Segmented label="Range" options={OPTIONS} value="WEEK" onChange={vi.fn()} testId="range" />);
    expect(screen.getByTestId('range-today').className).toContain('max-[639px]:min-h-11');
  });
});

describe('StatusDot tone and halo', () => {
  it('draws a tide dot and tide-coloured words for the tide tone', () => {
    render(<StatusDot tone="tide" testId="dot">Running</StatusDot>);
    const dot = screen.getByTestId('dot');
    expect(dot.className).toContain('text-tide');
    expect(dot.querySelector('span').className).toContain('bg-tide');
  });

  it('adds the 3px ring only when asked', () => {
    const { rerender } = render(<StatusDot tone="tide" halo testId="dot">Running</StatusDot>);
    expect(screen.getByTestId('dot').querySelector('span').className).toContain('shadow-[0_0_0_3px');
    rerender(<StatusDot tone="tide" testId="dot">Running</StatusDot>);
    expect(screen.getByTestId('dot').querySelector('span').className).not.toContain('shadow-');
  });

  it('keeps the other tones as they were', () => {
    render(<StatusDot tone="go" testId="dot">Done</StatusDot>);
    expect(screen.getByTestId('dot').querySelector('span').className).toContain('bg-plex-ok');
    expect(screen.getByTestId('dot').className).toContain('text-plex-text');
  });

  it('always carries the words, with the dot hidden from assistive tech', () => {
    render(<StatusDot tone="coral" testId="dot">Failed</StatusDot>);
    expect(screen.getByTestId('dot')).toHaveTextContent('Failed');
    expect(screen.getByTestId('dot').querySelector('span')).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('StatusDot tintText', () => {
  it('tints amber and coral words with the dot when asked', () => {
    const { rerender } = render(<StatusDot tone="amber" tintText testId="dot">Partial</StatusDot>);
    expect(screen.getByTestId('dot').className).toContain('text-verdict-marginal');
    rerender(<StatusDot tone="coral" tintText testId="dot">Failed</StatusDot>);
    expect(screen.getByTestId('dot').className).toContain('text-plex-coral');
  });

  it('leaves the words plain without it, so the Data tabs are unchanged', () => {
    render(<StatusDot tone="amber" testId="dot">Partial</StatusDot>);
    expect(screen.getByTestId('dot').className).toContain('text-plex-text');
    expect(screen.getByTestId('dot').className).not.toContain('text-verdict-marginal');
  });

  it('keeps go plain even when tinting is on', () => {
    render(<StatusDot tone="go" tintText testId="dot">Done</StatusDot>);
    expect(screen.getByTestId('dot').className).toContain('text-plex-text');
  });
});

describe('Segmented on a phone', () => {
  it('does not stretch the bordered group across a column', () => {
    render(<Segmented label="Range" options={OPTIONS} value="WEEK" onChange={vi.fn()} testId="range" />);
    expect(screen.getByTestId('range').className).toContain('self-start');
  });
});

describe('PanelShell title look', () => {
  it('defaults to the serif title', () => {
    render(<PanelShell title="Start a run" onClose={vi.fn()} testIdPrefix="p" />);
    expect(screen.getByTestId('p-title').className).toContain('font-serif');
  });

  it('takes a replacement look (a batch id in mono) and keeps it focusable and unfocused-ringed', () => {
    render(<PanelShell title="msgbatch_01" titleClassName="font-mono text-[18px]" onClose={vi.fn()} testIdPrefix="p" />);
    const title = screen.getByTestId('p-title');
    expect(title.className).toContain('font-mono');
    expect(title.className).not.toContain('font-serif');
    expect(title).toHaveAttribute('tabindex', '-1');
  });
});
