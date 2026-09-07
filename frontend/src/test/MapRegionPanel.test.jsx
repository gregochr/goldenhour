/**
 * `components/map/MapRegionPanel.jsx` — the drilldown's second and last level, as rendered
 * (`docs/engineering/map-landing-plan.md` §3 L6, `docs/design/map-landing/README.md` §5).
 *
 * <p>Which locations appear and in what order is decided by `utils/mapDrilldown.js` and pinned in
 * `mapRegionDrilldown.test.js`. This file asserts what that model becomes on screen, and the four
 * things the component itself owns: back, close, the two actions, and the full-name rule.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MapRegionPanel from '../components/map/MapRegionPanel.jsx';
import { rampHex } from '../utils/scoreRamp.js';

const ROW = {
  id: 'solar:2026-01-15:SUNSET',
  kind: 'solar',
  eventType: 'SUNSET',
  label: 'Tonight sunset',
  time: '20:28',
  pickKind: 'best',
  // The region the pick NAMES. The fixture matches `REGION.name` so the medallion renders; the
  // mismatch and absent cases are their own tests below.
  pickRegion: 'The Lakes',
};

const REGION = {
  name: 'The Lakes', tier: 'WORTH_IT', verdictLabel: 'Worth it', meanRating: 4.2,
  bestRating: 5, driveLabel: '1h 35min', placeCount: 9, atFourPlus: 4,
};

const LOCATIONS = [
  {
    id: 1, name: 'Ashness Bridge', rating: 5, driveLabel: '1h 35min',
    leaveTime: '14:25', leaveDayWord: null, tideOnLight: false,
  },
  {
    id: 2, name: 'Buttermere', rating: 4, driveLabel: '40 min',
    leaveTime: '15:21', leaveDayWord: null, tideOnLight: true,
  },
];

function renderPanel(props = {}) {
  const handlers = {
    onBack: vi.fn(), onClose: vi.fn(), onZoomToRegion: vi.fn(), onOpenLocationSheet: vi.fn(),
  };
  const result = render(
    <MapRegionPanel
      row={ROW}
      region={REGION}
      locations={LOCATIONS}
      {...handlers}
      {...props}
    />,
  );
  return { ...result, ...handlers };
}

let foreignModal = null;
afterEach(() => {
  // Torn down here, not on the last line of a test: an assertion that threw first would leave an
  // `[aria-modal]` in the body and stand every later Escape case in this file down silently.
  foreignModal?.remove();
  foreignModal = null;
});

const plantForeignModal = () => {
  foreignModal = document.createElement('div');
  foreignModal.setAttribute('role', 'dialog');
  foreignModal.setAttribute('aria-modal', 'true');
  document.body.appendChild(foreignModal);
};

describe('MapRegionPanel — the header', () => {
  it('names the region, the window it is about and its pick', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel-region')).toHaveTextContent('The Lakes');
    const meta = screen.getByTestId('wf-reg-panel-meta');
    expect(meta).toHaveTextContent('Tonight sunset');
    expect(meta).toHaveTextContent('20:28');
    expect(within(meta).getByTestId('wf-reg-panel-pick')).toHaveAttribute('data-pick', 'best');
  });

  /**
   * ⚠️ The verdict on this panel is the REGION's, not the window's. The window panel's chip names
   * its strongest region — so a reader who drilled into the third-best one must not be shown that
   * region's word over this one's locations. The fixture makes them differ.
   */
  it('shows THIS region\'s verdict, not the window\'s', () => {
    renderPanel({ region: { ...REGION, tier: 'MAYBE', verdictLabel: 'Maybe' } });

    const chip = screen.getByTestId('wf-reg-panel-verdict');
    expect(chip).toHaveTextContent('Maybe');
    expect(chip).toHaveAttribute('data-tier', 'MAYBE');
  });

  /**
   * ⚠️ **The medallion is a claim about a WINDOW, and this header's subject is a REGION.** The
   * served pick names its own region; `windowFirstStrip` used to drop that, on a decision (§4 #10)
   * taken for the pill and the window rows, whose subject IS the window. Ungated, `◎ Best bet`
   * printed beside a region the forecast did not pick — three columns from a `Poor` chip. The
   * prototype gates it the same way.
   */
  it('withholds the medallion when the pick names ANOTHER region', () => {
    renderPanel({ row: { ...ROW, pickRegion: 'North East' } });

    expect(screen.queryByTestId('wf-reg-panel-pick')).toBeNull();
    expect(screen.getByTestId('wf-reg-panel-meta')).not.toHaveTextContent('Best bet');
  });

  it('withholds it on a window with no pick at all — the ordinary case', () => {
    renderPanel({ row: { ...ROW, pickKind: null, pickRegion: null } });

    expect(screen.queryByTestId('wf-reg-panel-pick')).toBeNull();
  });

  it('...and on a payload that carries the kind but no region to test it against', () => {
    renderPanel({ row: { ...ROW, pickRegion: null } });

    expect(screen.queryByTestId('wf-reg-panel-pick')).toBeNull();
  });

  it('prints the stats line the design asks for, in its own order', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel-stats'))
      .toHaveTextContent('4 of 9 at 4★+ stars or better · nearest 1h 35min · average 4.2★ stars');
  });
});

describe('MapRegionPanel — the location rows', () => {
  it('draws the name, the drive, the departure and the stars', () => {
    renderPanel();

    const rows = screen.getAllByTestId('wf-reg-panel-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveAttribute('data-location', 'Ashness Bridge');
    expect(within(rows[0]).getByTestId('wf-reg-panel-when')).toHaveTextContent('1h 35min · leave 14:25');
    expect(within(rows[0]).getByTestId('wf-reg-panel-stars')).toHaveTextContent('5★');
  });

  it('draws the tide glyph only where this window\'s water lands on the light', () => {
    renderPanel();

    const rows = screen.getAllByTestId('wf-reg-panel-row');
    expect(within(rows[0]).queryByTestId('wf-reg-panel-tide')).toBeNull();
    expect(within(rows[1]).getByTestId('wf-reg-panel-tide')).toBeInTheDocument();
  });

  /** A bare wave announces as nothing at all — the glyph is hidden and the fact is spoken. */
  it('speaks the tide fact in words beside the hidden glyph', () => {
    renderPanel();

    const row = screen.getAllByTestId('wf-reg-panel-row')[1];
    expect(within(row).getByTestId('wf-reg-panel-tide')).toHaveAttribute('aria-hidden', 'true');
    expect(row).toHaveTextContent('the tide lands on the light here');
  });

  it('⚠️ omits the drive and the departure together where no drive is measured — never a dash', () => {
    renderPanel({
      locations: [{
        id: 3, name: 'Wastwater', rating: 4, driveLabel: null, leaveTime: null,
        leaveDayWord: null, tideOnLight: false,
      }],
    });

    // ⚠️ `toHaveTextContent('')` would pass against ANY content — the empty string is a substring
    // of every string. The cell must be genuinely empty, so the assertion is on `textContent`.
    const when = screen.getByTestId('wf-reg-panel-when');
    expect(when.textContent).toBe('');
  });

  /**
   * A long drive to an early sunrise leaves the evening before, and the callout's own `Leave by`
   * fact marks that rather than wrapping silently. This row reads the same function's answer.
   */
  it('marks a departure that lands on a different day', () => {
    renderPanel({
      locations: [{
        id: 4, name: 'Bamburgh', rating: 5, driveLabel: '4h 20min',
        leaveTime: '23:35', leaveDayWord: 'Thu', tideOnLight: false,
      }],
    });

    expect(screen.getByTestId('wf-reg-panel-when')).toHaveTextContent('leave 23:35 (Thu)');
  });

  it('says why it is empty rather than rendering a blank panel', () => {
    renderPanel({ locations: [] });

    // ⚠️ Names the WINDOW, never the event side: `eventWord` has no night arm, so an ASTRO window
    // would have read "this sunset" the moment O-16 makes night rows reachable.
    expect(screen.getByTestId('wf-reg-panel-empty'))
      .toHaveTextContent('Nothing in The Lakes is rated for this window yet.');
    expect(screen.getByTestId('wf-reg-panel-empty').textContent).not.toMatch(/sunset|sunrise/);
  });
});

describe('MapRegionPanel — the narrative', () => {
  it('prints both halves of the served gloss when both exist', () => {
    renderPanel({ gloss: { headline: 'A clean western horizon.', detail: 'High cloud thins by 19:00.' } });

    expect(screen.getByTestId('wf-reg-panel-gloss'))
      .toHaveTextContent('A clean western horizon. High cloud thins by 19:00.');
  });

  it('prints the half it has, and nothing at all when it has neither', () => {
    const { rerender } = renderPanel({ gloss: { headline: null, detail: 'High cloud thins.' } });
    expect(screen.getByTestId('wf-reg-panel-gloss')).toHaveTextContent('High cloud thins.');

    rerender(
      <MapRegionPanel
        row={ROW}
        region={REGION}
        locations={LOCATIONS}
        gloss={null}
        onBack={vi.fn()}
        onClose={vi.fn()}
        onZoomToRegion={vi.fn()}
        onOpenLocationSheet={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('wf-reg-panel-gloss')).toBeNull();
  });
});

describe('MapRegionPanel — the actions', () => {
  it('zooms to the region it is about', () => {
    const { onZoomToRegion } = renderPanel();

    fireEvent.click(screen.getByTestId('wf-reg-panel-zoom'));

    expect(onZoomToRegion).toHaveBeenCalledWith('The Lakes');
  });

  /**
   * ⚠️ The FULL name, and the design says it twice: splitting on whitespace produced "Four days at
   * Infinity" and "Four days at Scott's", which are not places. Both shapes are pinned.
   */
  it.each([
    ['Infinity Bridge', 'Four days at Infinity Bridge'],
    ["Scott's View", "Four days at Scott's View"],
  ])('names %s in full', (name, expected) => {
    renderPanel({ locations: [{ ...LOCATIONS[0], name }] });

    const action = screen.getByTestId('wf-reg-panel-four-days');
    expect(action).toHaveTextContent(expected);
    // ⚠️ The whole name survives the CSS ellipsis, which truncates paint and not text — so the
    // accessible name is complete without a `title`, which was measured to be a duplicate
    // description on every render rather than a backstop.
    expect(action).toHaveAccessibleName(expected);
    expect(action).not.toHaveAttribute('title');
  });

  it('names the panel\'s own top location, which is the first row', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel-four-days')).toHaveTextContent('Four days at Ashness Bridge');
  });

  it('opens the sheet for that location', () => {
    const { onOpenLocationSheet } = renderPanel();

    fireEvent.click(screen.getByTestId('wf-reg-panel-four-days'));

    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({ name: 'Ashness Bridge' }));
  });

  it('opens the sheet for a row that is pressed directly, not only for the top one', () => {
    const { onOpenLocationSheet } = renderPanel();

    fireEvent.click(screen.getAllByTestId('wf-reg-panel-row')[1]);

    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({ name: 'Buttermere' }));
  });

  it('withholds the four-day action entirely when the region has no rated location to name', () => {
    renderPanel({ locations: [] });

    expect(screen.queryByTestId('wf-reg-panel-four-days')).toBeNull();
    expect(screen.getByTestId('wf-reg-panel-zoom')).toBeInTheDocument();
  });
});

describe('MapRegionPanel — back, close and the outside press', () => {
  it('the back control steps up a level and does NOT close the drilldown', () => {
    const { onBack, onClose } = renderPanel();

    fireEvent.click(screen.getByTestId('wf-reg-panel-back'));

    expect(onBack).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('names what the back control goes back to', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel-back'))
      .toHaveAccessibleName('Back to every region on this window');
  });

  /** ⚠️ §4 #28: one Escape must step back one level, never collapse the whole drilldown. */
  it('Escape steps back rather than closing', () => {
    const { onBack, onClose } = renderPanel();

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    expect(onBack).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  /**
   * ⚠️ The defect `map-landing-plan.md` §4 #37 recorded and O-20 carried: with the four-day sheet
   * open over the map, one `Escape` reaching this panel operated it BEHIND the sheet.
   *
   * <p>Both handlers run on one press — neither calls {@code stopPropagation} — so `MapView`'s
   * pane-level rule stood down correctly while this one did not, and this one acted. The fix is not
   * a new guard: it reads the same {@code foreignModalOver} predicate, against the same pane root,
   * that the pane handler and the landing card's document listener already read.
   */
  it('⚠️ Escape stands down while a dialog from outside the pane is over it', () => {
    const handlers = renderPanel();
    plantForeignModal();

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    expect(handlers.onBack, 'the panel must not act behind a modal').not.toHaveBeenCalled();
    expect(handlers.onClose).not.toHaveBeenCalled();
  });

  it('and leaves the press for the layer above rather than consuming it', () => {
    // The stand-down returns BEFORE `preventDefault`. If it did not, the sheet over this panel
    // would get a press already marked handled and the reader would need a second Escape to close
    // the thing they are actually looking at.
    renderPanel();
    plantForeignModal();

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    screen.getByTestId('wf-reg-panel').dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
  });

  it('and acts as before once nothing is over the pane', () => {
    const handlers = renderPanel();

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key: 'Escape' });

    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it('the close control closes the whole drilldown, and says so', () => {
    const { onClose, onBack } = renderPanel();

    const close = screen.getByTestId('wf-reg-panel-close');
    expect(close).toHaveAccessibleName('Close the drilldown');
    fireEvent.click(close);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onBack).not.toHaveBeenCalled();
  });

  it('a press outside dismisses it — L3\'s panel rule, the same as the level above', () => {
    const { onClose } = renderPanel();

    fireEvent.mouseDown(document.body);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('names itself for the region AND the window it is about', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel'))
      .toHaveAccessibleName('The Lakes, Tonight sunset');
  });

  /**
   * ⚠️ **Focus on mount, and it is the whole reason `Escape` works here at all.** The control that
   * opens this panel is a row inside the panel this one REPLACES, so the press destroys its own
   * button and focus falls to `<body>` — outside both this component's subtree handler and
   * `MapView`'s pane-level one. Measured in Chromium, WebKit and Firefox, and universal for a
   * keyboard reader, who must focus the row to press it. It also makes a screen reader announce the
   * panel, where before the press read as a no-op.
   */
  it('takes focus on mount, so the keyboard can reach it', () => {
    renderPanel();

    expect(document.activeElement).toBe(screen.getByTestId('wf-reg-panel'));
  });

  it('is focusable programmatically but is not a tab stop', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel')).toHaveAttribute('tabindex', '-1');
  });

  /** ⚠️ The negative case. Without the key guard every press inside would step back a level. */
  it.each(['Enter', ' ', 'Tab', 'ArrowDown'])('ignores %s — only Escape steps back', (key) => {
    const { onBack, onClose } = renderPanel();

    fireEvent.keyDown(screen.getByTestId('wf-reg-panel'), { key });

    expect(onBack).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});

/**
 * What assistive technology actually announces. ⚠️ Queried by ROLE and NAME rather than by test-id,
 * because an `aria-label` REPLACES an element's rendered text in its accessible name — a review lens
 * measured that adding one to the row button deleted the tide fact, the drive, the departure and the
 * stars from what a screen reader hears, and survived every test-id assertion in this file.
 */
describe('MapRegionPanel — what it announces', () => {
  it('names a location row from its own content — every fact spoken, none replaced', () => {
    renderPanel();

    // ⚠️ **`5stars`, not `5 stars`, and that is a jsdom artefact — do NOT "fix" the component.**
    // `dom-accessibility-api` GLUES adjacent sibling contributions where Chromium, WebKit and
    // Firefox all space them; a review lens ran the discriminating control in all three engines and
    // every one returned `5 stars`. This project has already lost a whole build to that difference,
    // which is why the expectation is written against the harness rather than against the browser.
    const row = screen.getAllByTestId('wf-reg-panel-row')[0];
    expect(row).toHaveAccessibleName('Ashness Bridge 1h 35min · leave 14:25 5stars');
  });

  it('speaks the tide fact in the row\'s own name, beside the hidden glyph', () => {
    renderPanel();

    expect(screen.getAllByTestId('wf-reg-panel-row')[1])
      .toHaveAccessibleName(/the tide lands on the light here/);
  });

  it('tells a screen reader that a location row opens something', () => {
    renderPanel();

    for (const row of screen.getAllByTestId('wf-reg-panel-row')) {
      expect(row).toHaveAttribute('aria-haspopup', 'dialog');
    }
    expect(screen.getByTestId('wf-reg-panel-four-days')).toHaveAttribute('aria-haspopup', 'dialog');
  });

  it('names both actions by their words', () => {
    renderPanel();

    expect(screen.getByRole('button', { name: 'Zoom to region' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Four days at Ashness Bridge' })).toBeInTheDocument();
  });

  /** ⚠️ The ✕ discards BOTH levels from here; the back arrow discards one. Saying the same thing
   *  on both levels left a reader who had drilled in with no way to hear the difference. */
  it('says what the ✕ actually does from the second level', () => {
    renderPanel();

    expect(screen.getByTestId('wf-reg-panel-close'))
      .toHaveAccessibleName('Close the drilldown');
  });

  it('paints each row\'s swatch from that row\'s OWN rating', () => {
    renderPanel();

    // ⚠️ jsdom normalises a hex `background` to `rgb(...)`, so the expectation is normalised the
    // same way rather than compared against the raw token.
    const asRgb = (hex) => {
      const probe = document.createElement('i');
      probe.style.background = hex;
      return probe.style.background;
    };
    const swatches = screen.getAllByTestId('wf-reg-panel-swatch').map((i) => i.style.background);
    expect(swatches[0]).toBe(asRgb(rampHex(5)));
    expect(swatches[1]).toBe(asRgb(rampHex(4)));
    expect(swatches[0]).not.toBe(swatches[1]);
  });
});
