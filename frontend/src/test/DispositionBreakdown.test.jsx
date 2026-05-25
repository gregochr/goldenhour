import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import DispositionBreakdown from '../components/DispositionBreakdown.jsx';

vi.mock('../api/metricsApi', () => ({
  getDispositionBreakdown: vi.fn(),
}));

import { getDispositionBreakdown } from '../api/metricsApi';

const POPULATED_RESPONSE = {
  jobRunId: 348,
  totalCount: 255,
  countsByDisposition: {
    EVALUATED: 163,
    SKIPPED_HARD_CONSTRAINT: 48,
    SKIPPED_TRIAGED: 41,
    SKIPPED_CACHED: 2,
    SKIPPED_PAST_DATE: 1,
  },
  entries: [
    {
      locationId: 42, locationName: 'Durham UK',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'EVALUATED', detail: null,
    },
    {
      locationId: 43, locationName: 'Newcastle',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'SKIPPED_TRIAGED',
      detail: 'Solar horizon low cloud 94% — sun blocked',
    },
    {
      locationId: 44, locationName: 'Whitby',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'SKIPPED_TRIAGED',
      detail: 'Heavy cloud',
    },
    {
      locationId: 45, locationName: 'Bamburgh',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'SKIPPED_HARD_CONSTRAINT',
      detail: 'Tide mismatch',
    },
    {
      locationId: null, locationName: 'Cached Region Loc',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'SKIPPED_CACHED',
      detail: 'Fresh cached evaluation within 6h (SETTLED)',
    },
    {
      locationId: null, locationName: 'Cached Region Loc 2',
      evaluationDate: '2026-05-26', eventType: 'SUNRISE',
      daysAhead: 1, disposition: 'SKIPPED_CACHED',
      detail: 'Fresh cached evaluation within 6h (SETTLED)',
    },
    {
      locationId: null, locationName: 'Past Loc',
      evaluationDate: '2026-05-23', eventType: 'SUNRISE',
      daysAhead: -2, disposition: 'SKIPPED_PAST_DATE',
      detail: 'Date in past',
    },
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('DispositionBreakdown — visibility', () => {
  it('renders nothing while the API call is pending', () => {
    getDispositionBreakdown.mockReturnValue(new Promise(() => {}));
    const { container } = render(<DispositionBreakdown jobRunId={1} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when totalCount is zero', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 1, totalCount: 0, countsByDisposition: {}, entries: [],
    }});
    const { container } = render(<DispositionBreakdown jobRunId={1} />);
    await waitFor(() => {
      expect(getDispositionBreakdown).toHaveBeenCalledWith(1);
    });
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when the API call fails (silent failure)', async () => {
    getDispositionBreakdown.mockRejectedValue(new Error('500'));
    const { container } = render(<DispositionBreakdown jobRunId={1} />);
    await waitFor(() => {
      expect(getDispositionBreakdown).toHaveBeenCalledWith(1);
    });
    expect(container.firstChild).toBeNull();
  });

  it('renders the section when totalCount > 0', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-breakdown')).toBeInTheDocument();
    });
  });
});

describe('DispositionBreakdown — summary reconciliation', () => {
  it('shows the total candidate count', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-summary')).toHaveTextContent(
        '255 candidates considered'
      );
    });
  });

  it('uses singular form when totalCount is 1', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 1, totalCount: 1,
      countsByDisposition: { SKIPPED_PAST_DATE: 1 },
      entries: [{
        locationId: null, locationName: 'Loc',
        evaluationDate: '2026-05-23', eventType: 'SUNRISE',
        daysAhead: -2, disposition: 'SKIPPED_PAST_DATE', detail: 'Date in past',
      }],
    }});
    render(<DispositionBreakdown jobRunId={1} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-summary')).toHaveTextContent(
        '1 candidate considered'
      );
    });
  });
});

describe('DispositionBreakdown — per-category rows', () => {
  it('shows each disposition category with its count', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-EVALUATED')).toHaveTextContent('163');
    });
    expect(screen.getByTestId('disposition-row-SKIPPED_HARD_CONSTRAINT'))
      .toHaveTextContent('48');
    expect(screen.getByTestId('disposition-row-SKIPPED_TRIAGED'))
      .toHaveTextContent('41');
    expect(screen.getByTestId('disposition-row-SKIPPED_CACHED'))
      .toHaveTextContent('2');
    expect(screen.getByTestId('disposition-row-SKIPPED_PAST_DATE'))
      .toHaveTextContent('1');
  });

  it('shows human-readable labels, not raw enum names, for each category', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByText('Evaluated')).toBeInTheDocument();
    });
    expect(screen.getByText('Hard constraint')).toBeInTheDocument();
    expect(screen.getByText('Triaged')).toBeInTheDocument();
    expect(screen.getByText('Cached')).toBeInTheDocument();
    expect(screen.getByText('Past date')).toBeInTheDocument();
  });

  it('hides categories with zero count', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 1, totalCount: 10,
      countsByDisposition: { EVALUATED: 10 },
      entries: Array.from({ length: 10 }, (_, i) => ({
        locationId: i, locationName: `Loc${i}`,
        evaluationDate: '2026-05-26', eventType: 'SUNRISE',
        daysAhead: 1, disposition: 'EVALUATED', detail: null,
      })),
    }});
    render(<DispositionBreakdown jobRunId={1} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-EVALUATED')).toBeInTheDocument();
    });
    // Other categories with 0 count must NOT render
    expect(screen.queryByTestId('disposition-row-SKIPPED_TRIAGED')).not.toBeInTheDocument();
    expect(screen.queryByTestId('disposition-row-SKIPPED_HARD_CONSTRAINT')).not.toBeInTheDocument();
    expect(screen.queryByText('Triaged')).not.toBeInTheDocument();
  });
});

describe('DispositionBreakdown — drill-down expansion', () => {
  it('does not show entries by default (collapsed)', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-SKIPPED_TRIAGED')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('disposition-entries-SKIPPED_TRIAGED')).not.toBeInTheDocument();
  });

  it('expands the category on click, showing each entry with detail', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-SKIPPED_TRIAGED')).toBeInTheDocument();
    });
    // Click the TRIAGED row's expand button
    const triagedRow = screen.getByTestId('disposition-row-SKIPPED_TRIAGED');
    fireEvent.click(triagedRow.querySelector('button'));

    const entriesPanel = screen.getByTestId('disposition-entries-SKIPPED_TRIAGED');
    expect(entriesPanel).toHaveTextContent('Newcastle');
    expect(entriesPanel).toHaveTextContent('Solar horizon low cloud 94% — sun blocked');
    expect(entriesPanel).toHaveTextContent('Whitby');
    expect(entriesPanel).toHaveTextContent('Heavy cloud');
  });

  it('collapses an expanded category on second click', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-EVALUATED')).toBeInTheDocument();
    });
    const button = screen.getByTestId('disposition-row-EVALUATED').querySelector('button');
    fireEvent.click(button);
    expect(screen.getByTestId('disposition-entries-EVALUATED')).toBeInTheDocument();
    fireEvent.click(button);
    expect(screen.queryByTestId('disposition-entries-EVALUATED')).not.toBeInTheDocument();
  });

  it('only one category expanded at a time — expanding a second collapses the first', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-EVALUATED')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('disposition-row-EVALUATED').querySelector('button'));
    expect(screen.getByTestId('disposition-entries-EVALUATED')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('disposition-row-SKIPPED_TRIAGED').querySelector('button'));
    expect(screen.queryByTestId('disposition-entries-EVALUATED')).not.toBeInTheDocument();
    expect(screen.getByTestId('disposition-entries-SKIPPED_TRIAGED')).toBeInTheDocument();
  });

  it('omits the detail line when the entry has no detail (EVALUATED case)', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: POPULATED_RESPONSE });
    render(<DispositionBreakdown jobRunId={348} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-EVALUATED')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('disposition-row-EVALUATED').querySelector('button'));
    const panel = screen.getByTestId('disposition-entries-EVALUATED');
    expect(panel).toHaveTextContent('Durham UK');
    expect(panel).toHaveTextContent('2026-05-26');
    // No detail rendered because EVALUATED entries have detail=null
    const innerDivs = panel.querySelectorAll('div.pl-2');
    expect(innerDivs.length).toBe(0);
  });
});

describe('DispositionBreakdown — forward compatibility', () => {
  it('renders an unknown future disposition with its raw key as a label', async () => {
    // The bar from the spec: "the enum/column accepts an unknown future value
    // (NO_REFRESH_NEEDED) without breaking read or display."
    // The frontend KNOWS about SKIPPED_NO_REFRESH_NEEDED (it's in the
    // CATEGORY_DISPLAY list as "No refresh needed"), so to test true
    // forward-compat we use a value not present in CATEGORY_DISPLAY at all.
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 500, totalCount: 1,
      countsByDisposition: { SKIPPED_FUTURE_CATEGORY_X: 1 },
      entries: [{
        locationId: 42, locationName: 'Durham UK',
        evaluationDate: '2026-05-26', eventType: 'SUNRISE',
        daysAhead: 0, disposition: 'SKIPPED_FUTURE_CATEGORY_X',
        detail: 'A future skip reason',
      }],
    }});
    render(<DispositionBreakdown jobRunId={500} />);
    await waitFor(() => {
      expect(screen.getByTestId('disposition-row-SKIPPED_FUTURE_CATEGORY_X'))
        .toBeInTheDocument();
    });
    // Unknown key falls back to displaying the raw key as the label
    expect(screen.getByText('SKIPPED_FUTURE_CATEGORY_X')).toBeInTheDocument();
  });
});
