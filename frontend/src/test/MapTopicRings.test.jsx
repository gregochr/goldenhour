import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

/** Every renderer `L.svg` has handed out, and every one the map was asked to remove. */
const created = [];
const removed = [];
vi.mock('leaflet', () => {
  const svg = (options) => {
    const renderer = { options, id: created.length };
    created.push(renderer);
    return renderer;
  };
  return { default: { svg }, svg };
});

const map = { removeLayer: (layer) => removed.push(layer) };
vi.mock('react-leaflet', () => ({
  useMap: () => map,
  Pane: ({ name, style, className, children }) => (
    <div data-testid="pane" data-name={name} data-z={style?.zIndex} className={className}>{children}</div>
  ),
  CircleMarker: ({
    center, radius, interactive, fill, color, weight, opacity, renderer,
  }) => (
    <i
      data-testid="circle"
      data-center={center.join(',')}
      data-radius={radius}
      data-interactive={String(interactive)}
      data-fill={String(fill)}
      data-colour={color}
      data-weight={weight}
      data-opacity={opacity}
      data-renderer={renderer?.id}
    />
  ),
}));

import MapTopicRings, {
  HEAT_RING_RADIUS, TOPIC_RINGS_PANE,
} from '../components/map/MapTopicRings.jsx';

const PLACES = [
  { name: 'Bamburgh', lat: 55.6, lng: -1.7 },
  { name: 'Roker Pier', lat: 54.9, lon: -1.35 },
];

beforeEach(() => {
  created.length = 0;
  removed.length = 0;
});

/** The ring itself: the stroke at the base radius, one per place (the other two circles are halo and glow). */
const rings = (radius = HEAT_RING_RADIUS) => screen.getAllByTestId('circle')
  .filter((c) => c.dataset.radius === String(radius));

describe('MapTopicRings', () => {
  it('draws exactly one ring per place, at its coordinates, in the topic colour', () => {
    render(<MapTopicRings places={PLACES} colour="#6FA8B0" />);
    expect(rings()).toHaveLength(2);
    expect(rings().map((r) => r.dataset.center)).toEqual(['55.6,-1.7', '54.9,-1.35']);
    rings().forEach((r) => expect(r.dataset.colour).toBe('#6FA8B0'));
  });

  it('accepts a location\'s `lon` as well as a heat spot\'s `lng`', () => {
    render(<MapTopicRings places={[PLACES[1]]} colour="#fff" />);
    expect(rings()[0].dataset.center).toBe('54.9,-1.35');
  });

  it('is non-interactive throughout', () => {
    render(<MapTopicRings places={PLACES} colour="#6FA8B0" />);
    screen.getAllByTestId('circle').forEach((c) => expect(c.dataset.interactive).toBe('false'));
  });

  it('⚠️ draws the halo and the glow as STROKES with no fill, so the ring\'s inside stays clear', () => {
    render(<MapTopicRings places={[PLACES[0]]} colour="#6FA8B0" />);
    const circles = screen.getAllByTestId('circle');
    expect(circles).toHaveLength(3);
    circles.forEach((c) => expect(c.dataset.fill).toBe('false'));
    const byWeight = Object.fromEntries(circles.map((c) => [c.dataset.weight, c]));
    expect(byWeight['2'].dataset.radius).toBe(String(HEAT_RING_RADIUS));
    expect(byWeight['3'].dataset.opacity).toBe('0.22');
    expect(Number(byWeight['3'].dataset.radius)).toBeGreaterThan(HEAT_RING_RADIUS);
    expect(Number(byWeight['8'].dataset.radius)).toBeGreaterThan(Number(byWeight['3'].dataset.radius));
    expect(Number(byWeight['8'].dataset.opacity)).toBeLessThan(0.22);
  });

  it('sits in its own pane, above the marker pane (600) and below the labels (650)', () => {
    render(<MapTopicRings places={PLACES} colour="#6FA8B0" />);
    const pane = screen.getByTestId('pane');
    expect(pane.dataset.name).toBe(TOPIC_RINGS_PANE);
    expect(Number(pane.dataset.z)).toBeGreaterThan(600);
    expect(Number(pane.dataset.z)).toBeLessThan(650);
  });

  it('draws nothing for a whole-sky topic (no places), or for places without coordinates', () => {
    const { container, rerender } = render(<MapTopicRings places={null} colour="#8E86D6" />);
    expect(container).toBeEmptyDOMElement();
    rerender(<MapTopicRings places={[{ name: 'x', lat: null, lng: 1 }, { name: 'y' }]} colour="#8E86D6" />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('MapTopicRings: the pane renderer', () => {
  it('gives every circle ONE renderer, bound to the rings pane', () => {
    render(<MapTopicRings places={PLACES} colour="#6FA8B0" />);
    expect(created).toHaveLength(1);
    expect(created[0].options).toEqual({ pane: TOPIC_RINGS_PANE });
    const ids = new Set(screen.getAllByTestId('circle').map((c) => c.dataset.renderer));
    expect([...ids]).toEqual(['0']);
  });

  it('⚠️ removes its renderer from the map on unmount: N select/deselect cycles leak none', () => {
    const CYCLES = 5;
    for (let i = 0; i < CYCLES; i += 1) {
      const { unmount } = render(<MapTopicRings places={PLACES} colour="#6FA8B0" />);
      // While mounted: one live renderer (created minus removed).
      expect(created.length - removed.length).toBe(1);
      unmount();
      expect(created.length - removed.length).toBe(0);
    }
    expect(created).toHaveLength(CYCLES);
    expect(removed).toHaveLength(CYCLES);
    // Each removal took its own renderer, not another's.
    expect(removed).toEqual(created);
  });
});
