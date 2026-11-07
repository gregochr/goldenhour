import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import VerdictWord from '../components/shared/VerdictWord.jsx';

describe('VerdictWord', () => {
  it('renders a tier with no short form as its plain word, with no wrapper spans', () => {
    render(<p data-testid="cell"><VerdictWord tier="WORTH_IT" /></p>);
    expect(screen.getByTestId('cell').textContent).toBe('Worth it');
    expect(screen.queryByTestId('wf-vw-short')).toBeNull();
    expect(screen.queryByTestId('wf-vw-full')).toBeNull();
  });

  it('reads an unknown tier as Not scored', () => {
    render(<p data-testid="cell"><VerdictWord tier="SOMETHING_NEW" /></p>);
    expect(screen.getByTestId('cell').textContent).toBe('Not scored');
  });

  it('short mode draws the short word and hides the full one visually only', () => {
    render(<VerdictWord tier="UNRATED" />);
    const short = screen.getByTestId('wf-vw-short');
    const full = screen.getByTestId('wf-vw-full');
    expect(short).toHaveTextContent(/^Too few$/);
    expect(short).toHaveAttribute('aria-hidden', 'true');
    expect(full).toHaveTextContent(/^Too few rated$/);
    expect(full).toHaveClass('sr-only');
    expect(full).not.toHaveAttribute('aria-hidden');
  });

  it('swap mode renders both words with the swap classes and no sr-only', () => {
    render(<VerdictWord tier="UNRATED" mode="swap" />);
    const short = screen.getByTestId('wf-vw-short');
    const full = screen.getByTestId('wf-vw-full');
    expect(short).toHaveClass('wf-vw-short');
    expect(short).toHaveAttribute('aria-hidden', 'true');
    expect(full).toHaveClass('wf-vw-full');
    expect(full).not.toHaveClass('sr-only');
    expect(full).toHaveTextContent(/^Too few rated$/);
  });

  it('ignores the mode for a tier with no short form', () => {
    render(<p data-testid="cell"><VerdictWord tier="MAYBE" mode="swap" /></p>);
    expect(screen.getByTestId('cell').textContent).toBe('Maybe');
    expect(screen.queryByTestId('wf-vw-short')).toBeNull();
  });
});
