import React, { useEffect, useState } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import PropTypes from 'prop-types';

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));

import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';
import { AuroraViewlineProvider } from '../context/AuroraViewlineContext.jsx';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

const LIVE = {
  points: [{ longitude: -5, latitude: 49 }],
  summary: 'Visible across the whole of the UK',
  southernmostLatitude: 49,
  active: true,
  isForecast: false,
  kp: null,
  asOf: '2026-10-10T21:40:00Z',
};

const POLL_MS = 5 * 60 * 1000;

/** A stand-in consumer: one flag is its own predicate (banner's or map's), and it shows what it was handed. */
function Consumer({ id, wants }) {
  const { viewline } = useAuroraViewline(wants);
  return <div data-testid={`consumer-${id}`}>{viewline ? 'line' : 'none'}</div>;
}
Consumer.propTypes = { id: PropTypes.string.isRequired, wants: PropTypes.bool.isRequired };

let setHarness = () => {};

/** Mounts the two consumers (the banner and the map) with independently switchable predicates and mount. */
function Harness({ initial = {} }) {
  const [state, setState] = useState({ banner: false, map: false, bannerMounted: true, mapMounted: true, ...initial });
  useEffect(() => {
    setHarness = (patch) => setState((s) => ({ ...s, ...patch }));
  }, []);
  return (
    <AuroraViewlineProvider>
      {state.bannerMounted && <Consumer id="banner" wants={state.banner} />}
      {state.mapMounted && <Consumer id="map" wants={state.map} />}
    </AuroraViewlineProvider>
  );
}
Harness.propTypes = { initial: PropTypes.object };

async function settle() {
  await act(async () => { await Promise.resolve(); });
}
async function tick(ms) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}
async function change(patch) {
  await act(async () => { setHarness(patch); });
  await settle();
}

describe('one shared viewline poll (P3d)', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mockRole = 'PRO_USER';
    vi.useFakeTimers({ shouldAdvanceTime: false });
    getAuroraViewline.mockResolvedValue(LIVE);
    getAuroraForecastViewline.mockResolvedValue(LIVE);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it.each([['banner'], ['map']])("the %s's predicate alone enables the poll, and only it is handed the line", async (who) => {
    const other = who === 'banner' ? 'map' : 'banner';
    render(<Harness initial={{ [who]: true }} />);
    await settle();
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId(`consumer-${who}`)).toHaveTextContent('line');
    // The other consumer's predicate is false, so it gets no line however much the provider holds.
    expect(screen.getByTestId(`consumer-${other}`)).toHaveTextContent('none');
  });

  it('never requests anything when the role is null', async () => {
    mockRole = null;
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    await tick(POLL_MS);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
  });

  it('under StrictMode, one consumer makes one request and the count settles', async () => {
    render(<React.StrictMode><Harness initial={{ map: true }} /></React.StrictMode>);
    await settle();
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    await change({ map: false });
    getAuroraViewline.mockClear();
    await tick(POLL_MS * 2);
    expect(getAuroraViewline).not.toHaveBeenCalled();
  });

  it('with includeForecast false under the provider, a forecast-only line is not offered to that caller', async () => {
    getAuroraViewline.mockResolvedValue({ points: [], active: false });
    getAuroraForecastViewline.mockResolvedValue(LIVE);
    function NoForecast() {
      const { viewline, source } = useAuroraViewline(true, { includeForecast: false });
      return <div data-testid="nf">{viewline ? `line:${source}` : 'none'}</div>;
    }
    render(<AuroraViewlineProvider><NoForecast /><Consumer id="map" wants /></AuroraViewlineProvider>);
    await settle();
    expect(screen.getByTestId('nf')).toHaveTextContent('none');
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('line');
  });

  it('makes no request when neither consumer wants it', async () => {
    render(<Harness />);
    await settle();
    await tick(POLL_MS * 2);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
  });

  it('never requests anything for a LITE reader, even if a consumer asks', async () => {
    mockRole = 'LITE_USER';
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    await tick(POLL_MS * 2);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('none');
  });

  it('makes ONE request per endpoint per interval with both consumers wanting it', async () => {
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    await tick(POLL_MS);
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('consumer-banner')).toHaveTextContent('line');
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('line');
  });

  it('stops polling when neither consumer wants it any more, and clears the line', async () => {
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    await change({ banner: false });
    await change({ map: false });
    getAuroraViewline.mockClear();
    getAuroraForecastViewline.mockClear();
    await tick(POLL_MS * 2);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('none');
  });

  it('keeps polling for the other consumer when one changes its predicate', async () => {
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    await change({ banner: false });
    await tick(POLL_MS);
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('line');
    expect(screen.getByTestId('consumer-banner')).toHaveTextContent('none');
  });

  it('keeps polling for the other consumer when one unmounts', async () => {
    render(<Harness initial={{ banner: true, map: true }} />);
    await settle();
    await change({ bannerMounted: false });
    await tick(POLL_MS);
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('consumer-map')).toHaveTextContent('line');
    await change({ mapMounted: false });
    getAuroraViewline.mockClear();
    await tick(POLL_MS * 2);
    expect(getAuroraViewline).not.toHaveBeenCalled();
  });

  it('a consumer that starts wanting it later is handed the line already held, with no extra request', async () => {
    render(<Harness initial={{ map: true }} />);
    await settle();
    await change({ banner: true });
    expect(screen.getByTestId('consumer-banner')).toHaveTextContent('line');
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
  });
});
