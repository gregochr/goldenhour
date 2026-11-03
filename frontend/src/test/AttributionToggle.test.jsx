/**
 * `AttributionToggle` — the phone's one-line attribution, tap to expand (map-mobile-compact-plan.md C2 step 5,
 * S9). jsdom has no Leaflet: the control is a div in a corner div, handed through `useMap()` the way Leaflet's
 * `attributionControl.getContainer()` hands it.
 */
import React from 'react';
import {
  describe, it, expect, beforeEach, afterEach, vi,
} from 'vitest';
import { fireEvent, render } from '@testing-library/react';

let fakeMap;
vi.mock('react-leaflet', () => ({ useMap: () => fakeMap }));

import AttributionToggle from '../components/map/AttributionToggle.jsx';

let corner;
let control;
let link;

beforeEach(() => {
  corner = document.createElement('div');
  corner.className = 'leaflet-bottom leaflet-right';
  control = document.createElement('div');
  control.className = 'leaflet-control-attribution leaflet-control';
  link = document.createElement('a');
  link.href = '#esri';
  link.textContent = 'Esri';
  control.append('Leaflet | Tiles © ', link, ' — HERE, Garmin');
  corner.appendChild(control);
  document.body.appendChild(corner);
  fakeMap = { attributionControl: { getContainer: () => control } };
});

afterEach(() => {
  corner.remove();
});

describe('AttributionToggle', () => {
  it('a tap on the control toggles the full text on it and the raised corner on its container', () => {
    render(<AttributionToggle enabled />);

    fireEvent.click(control);
    expect(control).toHaveClass('wf-attr-full');
    expect(corner).toHaveClass('wf-attr-open');

    fireEvent.click(control);
    expect(control).not.toHaveClass('wf-attr-full');
    expect(corner).not.toHaveClass('wf-attr-open');
  });

  it('a tap on a link inside it follows the link and toggles nothing (the event is not cancelled)', () => {
    render(<AttributionToggle enabled />);

    const notCancelled = fireEvent.click(link);

    expect(notCancelled).toBe(true);
    expect(control).not.toHaveClass('wf-attr-full');
    expect(corner).not.toHaveClass('wf-attr-open');
  });

  it('a tap on the text the control holds beside the link still toggles', () => {
    render(<AttributionToggle enabled />);

    fireEvent.click(control.firstChild); // a text node: no closest()

    expect(control).toHaveClass('wf-attr-full');
  });

  it('adds nothing off the phone: no listener, so a tap does nothing', () => {
    render(<AttributionToggle enabled={false} />);

    fireEvent.click(control);

    expect(control).not.toHaveClass('wf-attr-full');
    expect(corner).not.toHaveClass('wf-attr-open');
  });

  it('leaving the phone removes the listener AND the classes, so the desktop control is as Leaflet drew it', () => {
    const view = render(<AttributionToggle enabled />);
    fireEvent.click(control);
    expect(control).toHaveClass('wf-attr-full');

    view.rerender(<AttributionToggle enabled={false} />);

    expect(control).not.toHaveClass('wf-attr-full');
    expect(corner).not.toHaveClass('wf-attr-open');
    fireEvent.click(control);
    expect(control).not.toHaveClass('wf-attr-full');
  });

  it('unmounting removes the listener and the classes', () => {
    const view = render(<AttributionToggle enabled />);
    fireEvent.click(control);

    view.unmount();

    expect(control).not.toHaveClass('wf-attr-full');
    expect(corner).not.toHaveClass('wf-attr-open');
    fireEvent.click(control);
    expect(control).not.toHaveClass('wf-attr-full');
  });

  it('adds the listener once: re-rendering while enabled does not double it (two toggles would cancel out)', () => {
    const view = render(<AttributionToggle enabled />);
    view.rerender(<AttributionToggle enabled />);
    view.rerender(<AttributionToggle enabled />);

    fireEvent.click(control);

    expect(control).toHaveClass('wf-attr-full');
  });

  it('tolerates a map with no attribution control (a test harness\' useMap stub) without throwing', () => {
    fakeMap = {};

    expect(() => render(<AttributionToggle enabled />)).not.toThrow();
  });
});
