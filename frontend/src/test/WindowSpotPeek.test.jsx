import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowSpotPeek from '../components/WindowSpotPeek.jsx';

const BELOW = { left: 400, top: 388 };
const ABOVE = { left: 400, bottom: 500 };

function renderPeek(props = {}) {
  return render(
    <WindowSpotPeek
      position={BELOW}
      onOpen={() => {}}
      onPointerEnter={() => {}}
      onPointerLeave={() => {}}
      {...props}
    />,
  );
}

describe('WindowSpotPeek', () => {
  describe('where it renders', () => {
    it('portals to the body rather than into the caller\'s subtree', () => {
      // The whole point. `.wf-spots` is `overflow-x: auto` and therefore clips on both axes, the
      // window card sets `overflow: hidden` as an INLINE style no stylesheet can outrank, and
      // `.wf-spot:hover` applies a transform that re-bases a fixed descendant onto the very button
      // the panel hangs off. Portalling removes all three at once.
      const { container } = renderPeek({ clause: 'Clear to the west all evening.' });
      expect(container).toBeEmptyDOMElement();
      const panel = screen.getByTestId('wf-peek');
      expect(panel.parentElement).toBe(document.body);
    });

    it('is aria-hidden, because the card behind it stays the real route', () => {
      // `frontend-test-standards.md:156` names this component's v1 sibling by name. The peek is a
      // shortcut; every destination it offers is on the card, and the content it shows is in the
      // map overlay the click opens.
      renderPeek({ clause: 'Clear to the west all evening.' });
      expect(screen.getByTestId('wf-peek')).toHaveAttribute('aria-hidden', 'true');
    });

    it('is not a control, so it puts no duplicate action in the accessibility tree', () => {
      renderPeek({ clause: 'Clear to the west all evening.' });
      expect(screen.getByTestId('wf-peek').tagName).toBe('DIV');
    });

    it('uses wf- test ids, because the v1 suite asserts the cth- ones are absent', () => {
      // `CloseToHome.test.jsx:497,509` asserts `cth-hover-scores` and `cth-hover-upsell` do not
      // render. Reusing those names here would fail a test on a component this phase never touched.
      renderPeek({ fierySky: 68, goldenHour: 74, clause: 'Clear to the west all evening.' });
      expect(document.querySelectorAll('[data-testid^="cth-"]')).toHaveLength(0);
    });
  });

  describe('placement', () => {
    it('anchors from the top when it sits below the card', () => {
      renderPeek({ clause: 'Clear.' });
      const panel = screen.getByTestId('wf-peek');
      expect(panel).toHaveAttribute('data-placement', 'below');
      expect(panel.style.top).toBe('388px');
      expect(panel.style.bottom).toBe('');
    });

    it('anchors from the bottom when it flips above', () => {
      // Anchoring from the bottom is what makes a two-line body and a four-line one sit the same
      // distance off the card instead of the taller one drifting down over it.
      renderPeek({ position: ABOVE, placement: 'above', clause: 'Clear.' });
      const panel = screen.getByTestId('wf-peek');
      expect(panel).toHaveAttribute('data-placement', 'above');
      expect(panel.style.bottom).toBe('500px');
      expect(panel.style.top).toBe('');
    });

    it('publishes the arrow offset as the custom property the CSS reads', () => {
      renderPeek({ arrowLeft: 132, clause: 'Clear.' });
      expect(screen.getByTestId('wf-peek').style.getPropertyValue('--wf-peek-arrow-left'))
        .toBe('132px');
    });
  });

  describe('the rating line', () => {
    it('fills one glyph per star and hollows the rest', () => {
      renderPeek({ rating: 4, clause: 'Clear.' });
      expect(screen.getByTestId('wf-peek-stars')).toHaveTextContent('★★★★☆');
    });

    it('draws no star row at all for an unrated spot', () => {
      // `☆☆☆☆☆` is the glyph for ZERO, and zero is a claim. An unrated spot is one nothing has
      // looked at, which is a different statement from a poor one — the card 10px above says so by
      // omitting its badge, and every other field on this panel follows the same rule. The state is
      // reachable: the score index is fetched once while the briefing polls, so a slot can carry a
      // score with no usable rating.
      renderPeek({ rating: null, fierySky: 40 });
      expect(screen.queryByTestId('wf-peek-stars')).toBeNull();
      expect(screen.getByTestId('wf-peek')).not.toHaveTextContent('/5');
      // Still opens, because the score is something the card does not carry.
      expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('40');
    });

    it('prints the drive chip when the reader has a home postcode', () => {
      renderPeek({ driveMinutes: 66, clause: 'Clear.' });
      expect(screen.getByTestId('wf-peek-drive')).toHaveTextContent('🚗 1h 6min');
    });

    it('omits the drive chip entirely when the drive time is unknown', () => {
      // Absent, never zeroed — plan §2.5. This is the normal state for a user with no home.
      renderPeek({ driveMinutes: null, clause: 'Clear.' });
      expect(screen.queryByTestId('wf-peek-drive')).toBeNull();
    });
  });

  describe('the score bars — the part the card cannot show', () => {
    it('draws both bars with their values', () => {
      renderPeek({ fierySky: 68, goldenHour: 74 });
      expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('Fiery Sky');
      expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('68');
      expect(screen.getByTestId('wf-peek-golden')).toHaveTextContent('Golden Hour');
      expect(screen.getByTestId('wf-peek-golden')).toHaveTextContent('74');
    });

    it('names the measurements exactly as the map overlay does', () => {
      // A reader who follows the prompt must recognise the two bars they just looked at. §6 also
      // bans inventing vocabulary, and these two names are the product's own.
      renderPeek({ fierySky: 68, goldenHour: 74 });
      const panel = screen.getByTestId('wf-peek');
      expect(panel).toHaveTextContent('Fiery Sky');
      expect(panel).toHaveTextContent('Golden Hour');
    });

    it('draws one bar when only one axis was scored, never a bar and a zero', () => {
      renderPeek({ fierySky: 41, goldenHour: null });
      expect(screen.getByTestId('wf-peek-fiery')).toBeInTheDocument();
      expect(screen.queryByTestId('wf-peek-golden')).toBeNull();
    });

    it('draws no score block at all when neither axis was scored', () => {
      renderPeek({ clause: 'A thick marine layer sits on the coast.' });
      expect(screen.queryByTestId('wf-peek-scores')).toBeNull();
    });

    it('masks the unfilled tail so the colour at a given score never moves', () => {
      // The gradient is the whole bar and a panel-coloured span covers the remainder. Scaling the
      // gradient to the value instead would print the same hue at 40 and at 90.
      renderPeek({ fierySky: 68 });
      const rest = screen.getByTestId('wf-peek-fiery').querySelector('.wf-peek-bar-rest');
      expect(rest.style.width).toBe('32%');
    });

    it('clamps a value outside 0–100 rather than overflowing its track', () => {
      // Asserted on the TRACK, not on the text. `toHaveTextContent` is a substring match against the
      // whole bar including its label, so `"Golden Hour-20"` contains `"0"` — deleting the lower
      // clamp left this green, which mutation testing caught. The width is the only assertion the
      // negative cannot satisfy: -20 would render `width: 120%`.
      renderPeek({ fierySky: 140, goldenHour: -20 });
      expect(screen.getByTestId('wf-peek-fiery').querySelector('.wf-peek-bar-rest').style.width)
        .toBe('0%');
      expect(screen.getByTestId('wf-peek-golden').querySelector('.wf-peek-bar-rest').style.width)
        .toBe('100%');
      expect(screen.getByTestId('wf-peek-fiery')).toHaveTextContent('Fiery Sky100');
      expect(screen.getByTestId('wf-peek-golden')).toHaveTextContent('Golden Hour0');
    });

    it('draws a full track at 100 and an empty one at 0', () => {
      renderPeek({ fierySky: 100, goldenHour: 0 });
      expect(screen.getByTestId('wf-peek-fiery').querySelector('.wf-peek-bar-rest').style.width)
        .toBe('0%');
      expect(screen.getByTestId('wf-peek-golden').querySelector('.wf-peek-bar-rest').style.width)
        .toBe('100%');
    });
  });

  describe('the clause and the prompt', () => {
    it('shows the clause it was handed, already truncated upstream', () => {
      // Truncation lives in `resolveSpotPeek` rather than here, so the "is there anything to show"
      // gate is provably the thing rendered.
      renderPeek({ clause: 'Mid-level cloud should catch the last light.' });
      expect(screen.getByTestId('wf-peek-summary'))
        .toHaveTextContent('Mid-level cloud should catch the last light.');
    });

    it('draws no paragraph when the slot has no sentence', () => {
      renderPeek({ fierySky: 41 });
      expect(screen.queryByTestId('wf-peek-summary')).toBeNull();
    });

    it('always states what the click adds over the panel being read', () => {
      // The card underneath says `◍ Open on map →`, which names the destination; this names the
      // gain — the whole generated paragraph rather than one clause.
      renderPeek({ fierySky: 41 });
      expect(screen.getByTestId('wf-peek-prompt'))
        .toHaveTextContent('Click for the full read + map →');
    });

    it('states neither a forecast age nor a region, both of which are already on the page', () => {
      // The rail footer states the forecast's age once for the whole screen and the spot card
      // prints its own region — §2.7's rule against marking one fact twice.
      renderPeek({ fierySky: 68, goldenHour: 74, clause: 'Clear to the west.' });
      const panel = screen.getByTestId('wf-peek');
      expect(panel).not.toHaveTextContent(/ago/);
      expect(panel).not.toHaveTextContent(/Northumberland/);
    });

    it('carries no control at all, on a panel nothing can reach one in', () => {
      // The fullest panel the component can draw, deliberately: a fixture with no scores cannot
      // fail on anything the score block contains, and mutation testing caught exactly that — a
      // label turned into a <button> survived a fixture that rendered no bars.
      renderPeek({
        rating: 4,
        driveMinutes: 66,
        fierySky: 68,
        goldenHour: 74,
        clause: 'Mid-level cloud should catch the last light.',
      });
      expect(screen.queryByRole('button')).toBeNull();
      expect(screen.getByTestId('wf-peek').querySelectorAll('button, a, input')).toHaveLength(0);
    });
  });

  describe('what the pointer can do to it', () => {
    it('opens the map on click, duplicating the card\'s own activation', () => {
      const onOpen = vi.fn();
      renderPeek({ onOpen, clause: 'Clear.' });
      fireEvent.click(screen.getByTestId('wf-peek'));
      expect(onOpen).toHaveBeenCalledTimes(1);
    });

    it('holds itself open while the pointer rests on it', () => {
      // The panel sits 10px below the card, so the card's own mouseleave has already started a
      // grace by the time the pointer arrives. This is what cancels it.
      const onPointerEnter = vi.fn();
      renderPeek({ onPointerEnter, clause: 'Clear.' });
      fireEvent.mouseEnter(screen.getByTestId('wf-peek'));
      expect(onPointerEnter).toHaveBeenCalledTimes(1);
    });

    it('starts the dismissal grace when the pointer leaves it', () => {
      const onPointerLeave = vi.fn();
      renderPeek({ onPointerLeave, clause: 'Clear.' });
      fireEvent.mouseLeave(screen.getByTestId('wf-peek'));
      expect(onPointerLeave).toHaveBeenCalledTimes(1);
    });
  });
});
