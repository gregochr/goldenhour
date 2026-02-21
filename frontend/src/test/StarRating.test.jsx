import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StarRating from '../components/StarRating.jsx';

describe('StarRating', () => {
  it('renders 5 filled stars for rating 5', () => {
    render(<StarRating rating={5} label="Test" testId="test-rating" />);
    const container = screen.getByTestId('test-rating');
    const filled = container.querySelectorAll('.text-amber-400');
    expect(filled).toHaveLength(5);
  });

  it('renders 3 filled and 2 empty stars for rating 3', () => {
    render(<StarRating rating={3} label="Test" testId="test-rating" />);
    const container = screen.getByTestId('test-rating');
    const filled = container.querySelectorAll('.text-amber-400');
    const empty = container.querySelectorAll('.text-gray-600');
    expect(filled).toHaveLength(3);
    expect(empty).toHaveLength(2);
  });

  it('renders no filled stars when rating is null', () => {
    render(<StarRating rating={null} label="Test" testId="test-rating" />);
    const container = screen.getByTestId('test-rating');
    const filled = container.querySelectorAll('.text-amber-400');
    expect(filled).toHaveLength(0);
  });

  it('has accessible aria-label with rating value', () => {
    render(<StarRating rating={4} label="Sunset" testId="test-rating" />);
    expect(screen.getByRole('img', { name: /sunset.*4 out of 5/i })).toBeInTheDocument();
  });

  it('clamps ratings above 5 to 5', () => {
    render(<StarRating rating={7} label="Test" testId="test-rating" />);
    const container = screen.getByTestId('test-rating');
    const filled = container.querySelectorAll('.text-amber-400');
    expect(filled).toHaveLength(5);
  });

  it('clamps ratings below 1 to 1', () => {
    render(<StarRating rating={0} label="Test" testId="test-rating" />);
    const container = screen.getByTestId('test-rating');
    const filled = container.querySelectorAll('.text-amber-400');
    expect(filled).toHaveLength(1);
  });
});
