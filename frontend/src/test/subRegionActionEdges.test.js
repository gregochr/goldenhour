import { describe, it, expect } from 'vitest';
import { regionExamplePlaces } from '../utils/subRegionAction.js';

const stateOf = (subRegions) => ({ options: [{ regionName: 'Lakes', subRegions }] });

describe('subRegionAction edges', () => {
  it('names the first example of each of the first two sub-regions', () => {
    const state = stateOf([
      { exampleLocations: ['Ullswater', 'x'] },
      { exampleLocations: ['Buttermere'] },
      { exampleLocations: ['Third'] },
    ]);
    expect(regionExamplePlaces(state, 'Lakes')).toEqual(['Ullswater', 'Buttermere']);
  });

  it('leaves out a sub-region that has no example, whether missing, empty or null', () => {
    expect(regionExamplePlaces(stateOf([{ name: 'bare' }, { exampleLocations: ['B'] }]), 'Lakes')).toEqual(['B']);
    expect(regionExamplePlaces(stateOf([{ exampleLocations: [] }, { exampleLocations: ['B'] }]), 'Lakes'))
      .toEqual(['B']);
    expect(regionExamplePlaces(stateOf([null, { exampleLocations: ['A'] }]), 'Lakes')).toEqual(['A']);
  });

  it('is empty for options that are not loaded, an unknown region or a region with no sub-regions', () => {
    expect(regionExamplePlaces(null, 'Lakes')).toEqual([]);
    expect(regionExamplePlaces({ options: null }, 'Lakes')).toEqual([]);
    expect(regionExamplePlaces(stateOf([{ exampleLocations: ['A'] }]), 'Nowhere')).toEqual([]);
    expect(regionExamplePlaces({ options: [{ regionName: 'Lakes' }] }, 'Lakes')).toEqual([]);
  });
});
