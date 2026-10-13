import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import VerdictPill from '../../components/shared/VerdictPill.jsx';
import { VERDICT_LABEL } from '../../utils/windowFirstCards.js';

describe('VerdictPill', () => {
  it.each([
    ['GO', 'Worth it'],
    ['MARGINAL', 'Maybe'],
    ['STANDDOWN', 'Poor'],
  ])('maps legacy verdict %s to "%s"', (verdict, text) => {
    render(<VerdictPill verdict={verdict} />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent(text);
  });

  it('prefers displayVerdict over the legacy verdict', () => {
    render(<VerdictPill displayVerdict="WORTH_IT" verdict="STANDDOWN" />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent('Worth it');
    expect(screen.getByTestId('verdict-pill').className).toContain('bg-green-600');
  });

  it('words UNRATED "Too few rated" on the neutral chip — the region-level word, not the signal name (U2)', () => {
    // Without its own arm the label lookup would print the raw enum ("UNRATED") and the colour map
    // would fall to its generic default.
    render(<VerdictPill displayVerdict="UNRATED" verdict="GO" />);
    const pill = screen.getByTestId('verdict-pill');
    expect(pill).toHaveTextContent(/^Too few rated$/);
    expect(pill.className).toContain('bg-plex-surface');
    expect(pill.className).not.toMatch(/bg-green-600|bg-amber-600|bg-red-900/);
  });

  it.each(Object.entries(VERDICT_LABEL))('has the shared word for %s — the pill never prints a raw enum name', (signal, word) => {
    render(<VerdictPill displayVerdict={signal} />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent(new RegExp(`^${word}$`));
  });

  it('falls back to Not scored when neither signal is recognisable', () => {
    render(<VerdictPill />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent('Not scored');
  });

  it('label prop overrides the default text without changing the colour', () => {
    render(<VerdictPill displayVerdict="STAND_DOWN" label="Too unsettled to forecast" />);
    const pill = screen.getByTestId('verdict-pill');
    expect(pill).toHaveTextContent('Too unsettled to forecast');
    expect(pill.className).toContain('bg-red-900/60');
  });

  describe('confidence channel', () => {
    it('appends a provisional marker to a low-confidence rated verdict', () => {
      render(<VerdictPill displayVerdict="WORTH_IT" confidence="low" />);
      expect(screen.getByTestId('verdict-pill')).toHaveTextContent('Worth it');
      expect(screen.getByTestId('provisional-mark')).toBeInTheDocument();
    });

    it('marks a low-confidence MAYBE too', () => {
      render(<VerdictPill displayVerdict="MAYBE" confidence="low" />);
      expect(screen.getByTestId('provisional-mark')).toBeInTheDocument();
    });

    it.each(['high', 'medium', null, undefined])(
      'does not mark a %s-confidence verdict', (confidence) => {
        render(<VerdictPill displayVerdict="WORTH_IT" confidence={confidence} />);
        expect(screen.queryByTestId('provisional-mark')).toBeNull();
      },
    );

    it('never marks an UNRATED verdict either — "Too few rated" already says provisional (U2)', () => {
      render(<VerdictPill displayVerdict="UNRATED" confidence="low" />);
      expect(screen.queryByTestId('provisional-mark')).toBeNull();
    });

    it('never marks a poor verdict, even at low confidence (poor is its own signal)', () => {
      render(<VerdictPill displayVerdict="STAND_DOWN" confidence="low" />);
      expect(screen.queryByTestId('provisional-mark')).toBeNull();
    });

    it('renders a bare pill (no wrapper) when there is no marker to show', () => {
      // Non-provisional pills must stay structurally unchanged — the marker path is the only
      // time the pill is wrapped.
      const { container } = render(<VerdictPill displayVerdict="WORTH_IT" confidence="high" />);
      expect(container.firstChild).toBe(screen.getByTestId('verdict-pill'));
    });
  });
});
