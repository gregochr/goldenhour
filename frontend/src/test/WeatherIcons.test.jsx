import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import {
  ThermometerIcon, WindIcon, RainIcon, DropletIcon,
} from '../components/WeatherIcons.jsx';

// The glyphs sit beside the figure they name, so each must be hidden from assistive technology and drawn with its
// own stroke colour (the one thing that tells the four apart in the popup).
const ICONS = [
  ['ThermometerIcon', ThermometerIcon, '#ef4444', 1],
  ['WindIcon', WindIcon, '#60a5fa', 3],
  ['RainIcon', RainIcon, '#38bdf8', 4],
  ['DropletIcon', DropletIcon, '#38bdf8', 1],
];

describe('WeatherIcons', () => {
  it.each(ICONS)('%s is a decorative 24px svg with its own stroke and path count', (_name, Icon, stroke, paths) => {
    const { container } = render(<Icon />);
    const svg = container.firstChild;
    expect(svg.tagName.toLowerCase()).toBe('svg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveClass('wf-wx-icon');
    expect(svg).toHaveAttribute('viewBox', '0 0 24 24');
    expect(svg).toHaveAttribute('stroke', stroke);
    expect(svg.getElementsByTagName('path')).toHaveLength(paths);
  });
});
