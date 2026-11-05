import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import LocationAddForm from '../components/locations/LocationAddForm.jsx';

const makeAdd = (formOverrides = {}) => ({
  form: {
    manualEntry: false,
    placeName: 'Bamburgh',
    geocodeResult: null,
    geocodeError: '',
    enrichmentData: null,
    manualName: '',
    manualLat: '',
    manualLon: '',
    locationTypes: ['SEASCAPE'],
    solarEventTypes: ['SUNRISE', 'SUNSET'],
    tideTypes: ['HIGH', 'MID'],
    overlooksWater: false,
    coastalTidal: false,
    mistProne: false,
    regionId: '',
    subRegionId: '',
    ...formOverrides,
  },
  error: '',
  errorField: null,
  saving: false,
  canSubmit: true,
  geocoding: false,
  enriching: false,
  set: vi.fn(),
  geocode: vi.fn(),
  enterManually: vi.fn(),
  backToLookup: vi.fn(),
  changeTypes: vi.fn(),
  changeRegion: vi.fn(),
});

const renderForm = (add) => render(
  <LocationAddForm add={add} regions={[]} subRegions={[]} onSubmit={vi.fn()} onCancel={vi.fn()} />,
);

describe('LocationAddForm chip groups', () => {
  it('pressing a Light chip writes the new set of solar events', () => {
    const add = makeAdd();
    renderForm(add);

    fireEvent.click(screen.getByTestId('solar-chip-SUNSET'));

    expect(add.set).toHaveBeenCalledWith('solarEventTypes', ['SUNRISE']);
  });

  it('pressing a tide chip writes the new set of tides', () => {
    const add = makeAdd();
    renderForm(add);

    fireEvent.click(screen.getByTestId('tide-chip-LOW'));

    expect(add.set).toHaveBeenCalledWith('tideTypes', expect.arrayContaining(['HIGH', 'MID', 'LOW']));
  });

  it('keeps at least one Light chip on: the last cannot be switched off', () => {
    const add = makeAdd({ solarEventTypes: ['SUNRISE'] });
    renderForm(add);

    fireEvent.click(screen.getByTestId('solar-chip-SUNRISE'));

    expect(add.set).not.toHaveBeenCalled();
  });
});

describe('LocationAddForm sub-region', () => {
  it('choosing a sub-region writes its id', () => {
    const add = makeAdd({ regionId: '1' });
    render(
      <LocationAddForm
        add={add}
        regions={[{ id: 1, name: 'North East', enabled: true }]}
        subRegions={[{ id: 7, name: 'Northumberland Coast', regionId: 1 }]}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByTestId('add-sub-region'), { target: { value: '7' } });

    expect(add.set).toHaveBeenCalledWith('subRegionId', '7');
  });
});

describe('LocationAddForm geocode result', () => {
  it('shows a dash for the save name when the place name is blank', () => {
    const add = makeAdd({
      placeName: '   ',
      geocodeResult: { lat: 55.6, lon: -1.7, displayName: 'Somewhere, England' },
    });
    renderForm(add);

    expect(screen.getByTestId('location-add-save-name')).toHaveTextContent('Added as —');
  });
});
