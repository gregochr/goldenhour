import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusDot from '../components/data/StatusDot.jsx';

// A hollow StatusDot keeps its tone: a coral hollow dot is a coral ring; only the quiet "off" tone is the grey dashed ring.

const ring = (testId) => screen.getByTestId(testId).querySelector('span[aria-hidden="true"]');

describe('StatusDot hollow', () => {
  it('keeps the tone\'s colour as a solid ring', () => {
    render(
      <>
        <StatusDot tone="coral" hollow testId="c">Missed</StatusDot>
        <StatusDot tone="go" hollow testId="g">OK</StatusDot>
        <StatusDot tone="amber" hollow testId="a">Partial</StatusDot>
        <StatusDot tone="tide" hollow testId="t">Running</StatusDot>
      </>,
    );
    expect(ring('c')).toHaveClass('border', 'border-plex-coral');
    expect(ring('c')).not.toHaveClass('border-dashed');
    expect(ring('g')).toHaveClass('border-plex-ok');
    expect(ring('a')).toHaveClass('border-verdict-marginal');
    expect(ring('t')).toHaveClass('border-tide');
  });

  it('keeps the dashed grey ring for the quiet off tone (a run still to come)', () => {
    render(<StatusDot tone="off" hollow testId="o">To run</StatusDot>);
    expect(ring('o')).toHaveClass('border', 'border-dashed', 'border-plex-text-muted');
  });

  it('is still a filled dot without hollow', () => {
    render(<StatusDot tone="coral" testId="f">Failed</StatusDot>);
    expect(ring('f')).toHaveClass('bg-plex-coral');
    expect(ring('f')).not.toHaveClass('border');
  });
});
