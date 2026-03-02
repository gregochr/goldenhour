import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ScoreBar from '../components/ScoreBar.jsx';

describe('ScoreBar', () => {
  it('renders the label text', () => {
    render(<ScoreBar label="Fiery Sky" score={60} testId="score-bar" />);
    expect(screen.getByText('Fiery Sky')).toBeInTheDocument();
  });

  it('shows the numeric score value', () => {
    render(<ScoreBar label="Golden Hour" score={72} testId="score-bar" />);
    expect(screen.getByText('72')).toBeInTheDocument();
  });

  it('shows a dash when score is null', () => {
    render(<ScoreBar label="Fiery Sky" score={null} testId="score-bar" />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('clamps score above 100 to 100', () => {
    render(<ScoreBar label="Fiery Sky" score={150} testId="score-bar" />);
    expect(screen.getByText('100')).toBeInTheDocument();
  });

  it('clamps score below 0 to 0', () => {
    render(<ScoreBar label="Golden Hour" score={-10} testId="score-bar" />);
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('uses the data-testid prop', () => {
    render(<ScoreBar label="Fiery Sky" score={50} testId="my-bar" />);
    expect(screen.getByTestId('my-bar')).toBeInTheDocument();
  });

  it('applies gold colour class for score >= 75', () => {
    render(<ScoreBar label="Fiery Sky" score={80} testId="score-bar" />);
    const container = screen.getByTestId('score-bar');
    const bar = container.querySelector('.bg-plex-gold');
    expect(bar).not.toBeNull();
  });

  it('applies muted colour class for score < 25', () => {
    render(<ScoreBar label="Fiery Sky" score={10} testId="score-bar" />);
    const container = screen.getByTestId('score-bar');
    const bar = container.querySelector('.bg-plex-text-muted');
    expect(bar).not.toBeNull();
  });

  it('sets bar width to 0% when score is null', () => {
    render(<ScoreBar label="Fiery Sky" score={null} testId="score-bar" />);
    const container = screen.getByTestId('score-bar');
    const inner = container.querySelector('.h-full');
    expect(inner.style.width).toBe('0%');
  });

  it('sets bar width to the score percentage', () => {
    render(<ScoreBar label="Fiery Sky" score={65} testId="score-bar" />);
    const container = screen.getByTestId('score-bar');
    const inner = container.querySelector('.h-full');
    expect(inner.style.width).toBe('65%');
  });
});
