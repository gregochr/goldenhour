import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MapOverlay from '../components/MapOverlay.jsx';

function renderOverlay(overrides = {}) {
  const onClose = vi.fn();
  const onOpenFullMap = vi.fn();
  render(
    <MapOverlay
      title="Tyne and Wear"
      subLine="Today sunset · 21:49"
      narrative="A high-cloud canvas catches the last of the light."
      narrativeHead="◎ Worth it sunset · Tyne and Wear"
      narrativeTone="go"
      onClose={onClose}
      onOpenFullMap={onOpenFullMap}
      {...overrides}
    >
      <div data-testid="map-body">map</div>
    </MapOverlay>,
  );
  return { onClose, onOpenFullMap };
}

describe('MapOverlay', () => {
  it('renders the header, the map child, and the narrative band', () => {
    renderOverlay();
    expect(screen.getByTestId('map-overlay-panel')).toBeInTheDocument();
    expect(screen.getByText('Tyne and Wear')).toBeInTheDocument();
    expect(screen.getByText(/Today sunset · 21:49/)).toBeInTheDocument();
    expect(screen.getByTestId('map-body')).toBeInTheDocument();
    expect(screen.getByTestId('map-overlay-narrative').textContent).toContain('high-cloud canvas');
  });

  it('closes on the ✕ button', () => {
    const { onClose } = renderOverlay();
    fireEvent.click(screen.getByTestId('map-overlay-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape', () => {
    const { onClose } = renderOverlay();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on a backdrop click but not a panel click', () => {
    const { onClose } = renderOverlay();
    fireEvent.click(screen.getByTestId('map-overlay-panel'));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('map-overlay-scrim'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('the escape hatch calls onOpenFullMap', () => {
    const { onOpenFullMap } = renderOverlay();
    fireEvent.click(screen.getByTestId('map-overlay-open-full'));
    expect(onOpenFullMap).toHaveBeenCalledTimes(1);
  });

  it('renders a caption when provided', () => {
    renderOverlay({ caption: '◍ 7 regions — tap a pin to open its locations', narrative: null });
    expect(screen.getByTestId('map-overlay-caption').textContent).toContain('7 regions');
    expect(screen.queryByTestId('map-overlay-narrative')).not.toBeInTheDocument();
  });
});
