import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowAttributeRow from '../components/WindowAttributeRow.jsx';

describe('WindowAttributeRow with a fact that has no segments', () => {
  it('renders the empty fact as an empty entry beside the others', () => {
    const row = {
      channel: 'tide',
      kicker: '≈ Tide',
      facts: [
        { segments: [] },
        { segments: [{ text: 'HW 19:28', tone: 'strong' }], optional: true },
      ],
    };

    render(<WindowAttributeRow row={row} />);

    const facts = screen.getAllByTestId('window-attribute-fact');
    expect(facts).toHaveLength(2);
    expect(facts[0]).toBeEmptyDOMElement();
    expect(facts[1]).toHaveTextContent('HW 19:28');
    expect(facts[1]).toHaveClass('opt');
  });
});
