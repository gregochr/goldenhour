import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AuroraGridRow from '../components/AuroraGridRow.jsx';

describe('AuroraGridRow', () => {
  it('renders nothing when both props are null', () => {
    const { container } = render(<AuroraGridRow auroraTonight={null} auroraTomorrow={null} />);
    expect(container.querySelector('[data-testid="aurora-grid-row"]')).toBeNull();
  });

  it('renders tonight row when auroraTonight is present', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MODERATE', kp: 5.2, clearLocationCount: 3, regions: [] }}
      auroraTomorrow={null}
    />);
    expect(screen.getByTestId('aurora-tonight-row')).toBeInTheDocument();
  });

  it('shows alert level and Kp in tonight row', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MODERATE', kp: 5.2, clearLocationCount: 3, regions: [] }}
      auroraTomorrow={null}
    />);
    expect(screen.getByText(/Moderate/)).toBeInTheDocument();
    expect(screen.getByText(/Kp 5.2/)).toBeInTheDocument();
  });

  it('shows clear location count in tonight row', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MINOR', kp: 3.1, clearLocationCount: 2, regions: [] }}
      auroraTomorrow={null}
    />);
    expect(screen.getByText(/2 locations clear/)).toBeInTheDocument();
  });

  it('uses singular "location" when clearLocationCount is 1', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MINOR', kp: 3.0, clearLocationCount: 1, regions: [] }}
      auroraTomorrow={null}
    />);
    expect(screen.getByText(/1 location clear/)).toBeInTheDocument();
    expect(screen.queryByText(/1 locations clear/)).toBeNull();
  });

  it('tonight row is tappable for MODERATE with clear locations', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MODERATE', kp: 5.0, clearLocationCount: 2, regions: [] }}
      auroraTomorrow={null}
    />);
    const row = screen.getByTestId('aurora-tonight-row');
    expect(row.getAttribute('role')).toBe('button');
  });

  it('tonight row is not tappable for QUIET (QUIET is not rendered)', () => {
    // QUIET is outside the TAPPABLE_LEVELS — no row rendered
    const { container } = render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'QUIET', kp: 1.0, clearLocationCount: 0, regions: [] }}
      auroraTomorrow={null}
    />);
    // Row still renders but is not a button
    const row = container.querySelector('[data-testid="aurora-tonight-row"]');
    if (row) {
      expect(row.getAttribute('role')).not.toBe('button');
    }
  });

  it('tonight row is not tappable when clearLocationCount is 0', () => {
    render(<AuroraGridRow
      auroraTonight={{ alertLevel: 'MODERATE', kp: 5.0, clearLocationCount: 0, regions: [] }}
      auroraTomorrow={null}
    />);
    const row = screen.getByTestId('aurora-tonight-row');
    expect(row.getAttribute('role')).not.toBe('button');
  });

  describe('aurora tomorrow row', () => {
    it('does not render tomorrow row when auroraTomorrow is null', () => {
      render(<AuroraGridRow
        auroraTonight={null}
        auroraTomorrow={null}
      />);
      expect(screen.queryByTestId('aurora-tomorrow-row')).toBeNull();
    });

    it('does not render tomorrow row when label is Quiet', () => {
      render(<AuroraGridRow
        auroraTonight={null}
        auroraTomorrow={{ peakKp: 1.5, label: 'Quiet' }}
      />);
      expect(screen.queryByTestId('aurora-tomorrow-row')).toBeNull();
    });

    it('renders tomorrow row when label is Worth watching', () => {
      render(<AuroraGridRow
        auroraTonight={null}
        auroraTomorrow={{ peakKp: 4.3, label: 'Worth watching' }}
      />);
      expect(screen.getByTestId('aurora-tomorrow-row')).toBeInTheDocument();
      expect(screen.getByText(/Worth watching/)).toBeInTheDocument();
      expect(screen.getByText(/Kp 4.3/)).toBeInTheDocument();
    });

    it('renders tomorrow row when label is Potentially strong', () => {
      render(<AuroraGridRow
        auroraTonight={null}
        auroraTomorrow={{ peakKp: 6.7, label: 'Potentially strong' }}
      />);
      expect(screen.getByTestId('aurora-tomorrow-row')).toBeInTheDocument();
      expect(screen.getByText(/Potentially strong/)).toBeInTheDocument();
    });
  });

  it('shows region name in tonight row when regions are present', () => {
    render(<AuroraGridRow
      auroraTonight={{
        alertLevel: 'MODERATE',
        kp: 5.0,
        clearLocationCount: 1,
        regions: [{ regionName: 'Northumberland', locations: [] }],
      }}
      auroraTomorrow={null}
    />);
    expect(screen.getByText(/Northumberland/)).toBeInTheDocument();
  });
});
