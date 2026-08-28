import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';

const TODAY = '2026-08-09';

/** Two entries in the shape the wire actually sends — note `meta` is OMITTED, not `{}`. */
const EVENTS = [
  {
    startDate: '2026-08-12',
    endDate: '2026-08-12',
    kind: 'ALMANAC',
    type: 'meteor',
    title: 'Perseids',
    detail: 'Perseids peaks, around 100 meteors an hour at best under a dark sky.',
    meta: { zhr: '100', radiant: 'NE', bestHours: '01:00–04:00', moonIllumination: '0%' },
  },
  {
    startDate: '2026-08-12',
    endDate: '2026-08-13',
    kind: 'ALMANAC',
    type: 'spring-tide',
    title: 'Spring tide run',
    detail: 'The moon’s alignment pulls the tide further out and further in than usual.',
  },
];

const renderPane = (props = {}) => {
  const onRetry = vi.fn();
  const onGoToPlan = vi.fn();
  const result = render(
    <WindowFirstComingUp
      id="window-first-panel-coming-up"
      labelledBy="window-first-tab-coming-up"
      status="ready"
      events={{ entries: EVENTS }}
      todayStr={TODAY}
      onRetry={onRetry}
      onGoToPlan={onGoToPlan}
      {...props}
    />,
  );
  return { ...result, onRetry, onGoToPlan };
};

describe('WindowFirstComingUp — the panel contract', () => {
  it('is a tab panel tied to the tab that controls it', () => {
    // The pairing is the whole reason the bar became a real tab widget at P13: without it a screen
    // reader is told there is a tab list and then given no way to know what either tab controls.
    renderPane();

    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('id', 'window-first-panel-coming-up');
    expect(panel).toHaveAttribute('aria-labelledby', 'window-first-tab-coming-up');
  });

  it('is focusable, because everything inside it is text', () => {
    // Without tabIndex there is nowhere for focus to go after the tab is chosen — the panel has no
    // focusable content of its own, so a keyboard user would be thrown straight past it to
    // whatever comes next after the panel.
    renderPane();
    expect(screen.getByRole('tabpanel')).toHaveAttribute('tabindex', '0');
  });

  it('names the horizon it covers', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-subtitle')).toHaveTextContent('next 90 days');
  });
});

describe('WindowFirstComingUp — the four states', () => {
  it('renders one row per entry, in the payload’s order', () => {
    renderPane();

    const rows = screen.getAllByTestId('coming-up-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Perseids');
    expect(rows[1]).toHaveTextContent('Spring tide run');
  });

  it('says it is looking while the request is in flight', () => {
    renderPane({ status: 'loading', events: null });
    expect(screen.getByTestId('coming-up-loading')).toBeInTheDocument();
  });

  it('never claims there is nothing coming up while the request is still in flight', () => {
    // The defect this exists to prevent: an empty state gated on `rows.length` rather than on the
    // status renders "Nothing dated in the next 90 days" for the whole of the first round-trip —
    // a false claim about the sky, shown at exactly the moment the reader is looking.
    renderPane({ status: 'loading', events: null });
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-row')).toBeNull();
  });

  it('says nothing is coming up only once the feed has actually answered with nothing', () => {
    // Reachable: `AlmanacService` returns `[]` rather than a 500 when every source throws.
    renderPane({ status: 'ready', events: { entries: [] } });
    expect(screen.getByTestId('coming-up-empty'))
      .toHaveTextContent("Nothing dated beyond Plan's four days in the next 90 days.");
  });

  it('renders nothing but its frame before the tab has ever been opened', () => {
    // `idle` is the state the pane is never actually mounted in today, since the shell only mounts
    // it once the tab is selected. Pinned so a future eager fetch cannot make `idle` mean "empty".
    renderPane({ status: 'idle', events: null });
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
    expect(screen.queryByTestId('coming-up-loading')).toBeNull();
    expect(screen.queryByTestId('coming-up-row')).toBeNull();
    expect(screen.getByTestId('coming-up-footer')).toBeInTheDocument();
  });

  it('says the load failed, and does not pretend the sky is empty', () => {
    renderPane({ status: 'error', events: null });

    expect(screen.getByTestId('coming-up-error'))
      .toHaveTextContent('Could not load what is coming up.');
    expect(screen.queryByTestId('coming-up-empty')).toBeNull();
  });

  it('announces the load and the failure through one always-mounted live region', () => {
    // ALWAYS mounted, and that is the load-bearing half: a live region inserted in the same commit
    // as its content is unreliably announced, which is why WindowSpotSheet puts role="status" on
    // the element that is there whatever happens. Selection follows focus on this tab bar, so a
    // reader arriving by arrow key has no other signal that the pane is loading or that it failed.
    const { unmount } = renderPane({ status: 'loading', events: null });
    const live = screen.getByTestId('coming-up-status');
    expect(live).toHaveAttribute('role', 'status');
    expect(live).toHaveTextContent('Looking ahead…');
    unmount();

    renderPane({ status: 'error', events: null });
    expect(screen.getByTestId('coming-up-status'))
      .toHaveTextContent('Could not load what is coming up.');
  });

  it('keeps the live region on screen while the rows are showing, so the next state is heard', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-status')).toBeInTheDocument();
    expect(screen.getByTestId('coming-up-status')).toBeEmptyDOMElement();
  });

  it('groups the entries as a list, so a screen reader gets a boundary and a count', () => {
    // Eleven sibling divs give a screen-reader user nothing to navigate by.
    renderPane();
    const list = screen.getByRole('list');
    expect(list).toBe(screen.getByTestId('coming-up-list'));
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('draws no empty list container when there is nothing to list', () => {
    renderPane({ status: 'ready', events: { entries: [] } });
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('puts focus on the panel when a retry succeeds, rather than dropping it on the body', () => {
    // Pressing the button unmounts it, and focus on a removed element falls to <body> — a keyboard
    // reader ends up at the top of the document with no idea whether anything happened.
    const { rerender, onRetry, onGoToPlan } = renderPane({ status: 'error', events: null });
    const button = screen.getByRole('button', { name: 'Try again' });
    button.focus();
    fireEvent.click(button);
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(
      <WindowFirstComingUp
        id="window-first-panel-coming-up"
        labelledBy="window-first-tab-coming-up"
        status="ready"
        events={{ entries: EVENTS }}
        todayStr={TODAY}
        onRetry={onRetry}
        onGoToPlan={onGoToPlan}
      />,
    );

    expect(screen.getByRole('tabpanel')).toHaveFocus();
  });

  it('offers a retry that actually retries', () => {
    // §6 bans a control that cannot act, and the standards ask that a failed fetch leave an escape
    // hatch on screen. This is both.
    const { onRetry } = renderPane({ status: 'error', events: null });

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps its heading and footer in every state, so the pane never looks broken', () => {
    for (const status of ['idle', 'loading', 'error', 'ready']) {
      const { unmount } = renderPane({
        status, events: status === 'ready' ? { entries: [] } : null,
      });
      expect(screen.getByTestId('coming-up-subtitle')).toBeInTheDocument();
      expect(screen.getByTestId('coming-up-footer')).toBeInTheDocument();
      unmount();
    }
  });
});

describe('WindowFirstComingUp — the certainty vocabulary', () => {
  it('states that the dates are fixed, once, at the foot', () => {
    renderPane();
    expect(screen.getByTestId('coming-up-footer'))
      .toHaveTextContent('Every date here is fixed in advance');
  });

  it('does not claim the dates come from orbital mechanics, because two sources compute none', () => {
    // `NlcSeasonAlmanacSource` reads a hard-coded 25 May – 10 Aug calendar window and
    // `SolarAlignmentAlmanacSource` uses fixed MonthDay anchors rather than a solved equinox
    // instant — §5g records those anchors as approximate and their accuracy as unverified. "Fixed
    // in advance" is true of all seven types; "fixed by orbital mechanics" is true of some.
    renderPane();
    expect(screen.getByTestId('coming-up-footer').textContent).not.toMatch(/orbital/i);
  });

  it('marks no row when every entry is almanac', () => {
    // Which is every entry the six sources emit. A chip on all of them would be a word that never
    // varies, and the footer above already says it.
    renderPane();
    expect(screen.queryAllByTestId('coming-up-kind')).toHaveLength(0);
  });

  it('stops claiming every date is fixed as soon as one row is not', () => {
    // The reason the sentence asks the rendered set rather than asserting what the plan expects.
    // A clause bolted onto the end would leave the false half still printed above it.
    renderPane({ events: { entries: [{ ...EVENTS[0], kind: 'FORECAST' }] } });

    expect(screen.getByTestId('coming-up-kind')).toHaveTextContent('Forecast');
    const footer = screen.getByTestId('coming-up-footer');
    expect(footer).toHaveTextContent('Dates marked Forecast are weather-driven');
    expect(footer.textContent).not.toContain('Every date here is fixed');
  });
});

describe('WindowFirstComingUp — the handoff row (plan D14)', () => {
  it('renders above the chronology, stating the boundary with Plan', () => {
    renderPane({ hotTopics: [{ type: 'DUST', label: 'Saharan dust', date: TODAY }] });

    const handoff = screen.getByTestId('coming-up-handoff');
    expect(handoff).toHaveTextContent('Now —');
    expect(handoff).toHaveTextContent('One topic lives on those four days');
    expect(handoff).toHaveTextContent('Saharan dust');
    expect(handoff).toHaveTextContent('On Plan →');
  });

  it('gives each phrase its own word boundary in the accessible name, rather than gluing them '
      + 'into one run-on string', () => {
    // JSX drops whitespace-only text between sibling tags — it does not collapse it to a space —
    // so without an explicit `{' '}` between every span the accessible name (the button's whole
    // text content) reads "...four daysSaharan dustAurora possibleOn Plan" with no boundaries.
    // Asserting via the ACCESSIBLE NAME (not toHaveTextContent, which matches a substring inside
    // the already-glued string and would not catch this) is the point of this test.
    renderPane({
      hotTopics: [
        { type: 'DUST', label: 'Saharan dust', date: TODAY },
        { type: 'AURORA', label: 'Aurora possible', date: TODAY },
      ],
    });

    const handoff = screen.getByRole('button', {
      name: 'Now — Wed 12 Two topics live on those four days Saharan dust Aurora possible '
        + 'On Plan →',
    });
    expect(handoff).toBeInTheDocument();
  });

  it('calls onGoToPlan when clicked', () => {
    const { onGoToPlan } = renderPane();
    fireEvent.click(screen.getByTestId('coming-up-handoff'));
    expect(onGoToPlan).toHaveBeenCalledTimes(1);
  });

  it('degrades to the label-only row when the briefing has not supplied hotTopics yet', () => {
    renderPane({ hotTopics: undefined });

    const handoff = screen.getByTestId('coming-up-handoff');
    expect(handoff).toHaveTextContent('Now —');
    expect(handoff).toHaveTextContent('On Plan →');
    expect(screen.queryByTestId('coming-up-handoff-summary')).toBeNull();
  });

  it('says explicitly that nothing is live once hotTopics has arrived empty', () => {
    renderPane({ hotTopics: [] });
    expect(screen.getByTestId('coming-up-handoff-summary'))
      .toHaveTextContent('No topics live on those four days');
  });
});

describe('WindowFirstComingUp — the degrade path', () => {
  it('marks a tide run with no figures, and leaves the meteor row alone', () => {
    renderPane();

    const missing = screen.getAllByTestId('coming-up-figures-missing');
    expect(missing).toHaveLength(1);
    expect(screen.getAllByTestId('coming-up-row')[1]).toContainElement(missing[0]);
  });

  it('treats an empty meta object exactly as it treats an absent one', () => {
    // Three inputs mean one thing — the key absent (what the backend sends), null, and `{}` (what a
    // hand-written fixture writes). A predicate that only caught the first would drop the caveat
    // from any payload or fixture that chose one of the others.
    const shapes = [
      { ...EVENTS[1] },
      { ...EVENTS[1], meta: undefined },
      { ...EVENTS[1], meta: null },
      { ...EVENTS[1], meta: {} },
    ];
    for (const event of shapes) {
      const { unmount } = renderPane({ events: { entries: [event] } });
      expect(screen.getByTestId('coming-up-figures-missing')).toBeInTheDocument();
      unmount();
    }
  });
});
