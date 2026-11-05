import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusDot from '../components/data/StatusDot.jsx';

describe('StatusDot with a tone it does not know', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('falls back to the quiet "off" colours for the dot and the words', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<StatusDot tone="bogus" testId="dot">Unknown</StatusDot>);

    const wrapper = screen.getByTestId('dot');
    expect(wrapper).toHaveClass('text-plex-text-muted');
    // The dot is a decorative, aria-hidden span with no role or test id: reached as the wrapper's first child.
    expect(wrapper.firstElementChild).toHaveClass('bg-plex-text-faint');
  });

  it('falls back to the dashed "not yet" ring when hollow', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<StatusDot tone="bogus" hollow testId="dot">Unknown</StatusDot>);

    expect(screen.getByTestId('dot').firstElementChild).toHaveClass('border-dashed');
  });
});
