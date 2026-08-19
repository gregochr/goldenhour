import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowSpotSheet from '../components/WindowSpotSheet.jsx';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 30,
    distanceMiles: 21,
    ...overrides,
  };
}

const COAST = spot({ key: '1', locationName: 'Bamburgh Beach', rating: 4, driveMinutes: 30 });
const FELL = spot({ key: '2', locationName: 'Simonside', rating: 3, driveMinutes: 40 });
const FALLS = spot({ key: '3', locationName: 'High Force', rating: 5, driveMinutes: 120 });
const UNRATED = spot({ key: '4', locationName: 'Cresswell', rating: null, driveMinutes: 20 });

const TYPES = new Map([
  ['Bamburgh Beach', ['SEASCAPE']],
  ['Simonside', ['LANDSCAPE']],
  ['High Force', ['WATERFALL']],
  ['Cresswell', ['SEASCAPE']],
]);

const CARD = {
  key: '2026-08-08:SUNSET',
  kicker: 'Tonight',
  when: 'Sunset',
  time: '20:41',
  allSpots: [COAST, FELL, FALLS, UNRATED],
  spots: [COAST, FELL],
};

const renderSheet = (props = {}) => render(
  <WindowSpotSheet
    card={CARD}
    barTierId="45"
    barFloorId="any"
    typesByName={TYPES}
    onClose={() => {}}
    {...props}
  />,
);

/** The three segments, queried through the role contract their group actually carries. */
const seg = (id) => screen.queryByTestId(id);
const chip = (id, name) => within(screen.getByTestId(id)).getByRole('button', { name });

describe('WindowSpotSheet', () => {
  beforeEach(() => localStorage.clear());
  // The net the standards ask for: an assertion that throws before an inline `mockRestore` would
  // otherwise leave `Storage.prototype.setItem` stubbed for every test after it in this file.
  afterEach(() => vi.restoreAllMocks());

  describe('the dialog itself', () => {
    it('is announced as a dialog, named for the window it is about', () => {
      renderSheet();
      expect(screen.getByRole('dialog', { name: 'All spots — Tonight Sunset' })).toBeInTheDocument();
    });

    it('states the window and its time in the header', () => {
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-title')).toHaveTextContent('Sunset · 20:41');
    });

    it('states the window alone when the payload carried no event time', () => {
      renderSheet({ card: { ...CARD, time: '' } });
      expect(screen.getByTestId('window-spot-sheet-title')).toHaveTextContent('Sunset');
      expect(screen.getByTestId('window-spot-sheet-title')).not.toHaveTextContent('·');
    });

    it('carries the kicker, so a lead card\'s dialog still names a day', () => {
      // Found in the browser: on a lead card `when` is the bare event, because the day lives in the
      // kicker — so without it the header of a dialog opened from a six-window page read
      // "Sunset · 22:41" and named no day at all.
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-kicker')).toHaveTextContent('Tonight');
    });

    it('carries no kicker on a card that has none, because the day is already in its title', () => {
      renderSheet({ card: { ...CARD, kicker: null, when: 'Tomorrow sunset' } });
      expect(screen.queryByTestId('window-spot-sheet-kicker')).toBeNull();
      expect(screen.getByRole('dialog', { name: 'All spots — Tomorrow sunset' })).toBeInTheDocument();
    });

    it('names each control group, so the chips are not three rows of loose buttons', () => {
      // The kicker beside each segment is wired to it by `aria-labelledby`, and that wiring is the
      // whole of the name — without it a screen reader reads twelve unrelated toggle buttons. The
      // lens bar's own suite asserts its group the same way.
      renderSheet({ barTierId: 'any' });
      expect(screen.getByRole('group', { name: 'How far' })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: 'At least' })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: 'Type' })).toBeInTheDocument();
    });

    it('closes on the close control', () => {
      const onClose = vi.fn();
      renderSheet({ onClose });
      fireEvent.click(screen.getByRole('button', { name: 'Close · Esc' }));
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes on Escape — nothing here is lost by dismissing', () => {
      const onClose = vi.fn();
      renderSheet({ onClose });
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('carries NO best-rating star — the top card is the best and badges itself', () => {
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-title').textContent).not.toMatch(/★/);
    });

    it('renders the confidence channel nowhere — §2.7 gives it to the window card badge alone', () => {
      // The channel is the fill decay plus `ProvisionalMark`, not the ◎ (which is the
      // recommendation glyph). §6 asks specifically that it be checked absent from the drilldown.
      renderSheet();
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).queryByTestId('provisional-mark')).toBeNull();
      expect(dialog.querySelector('[data-confidence]')).toBeNull();
    });
  });

  describe('the reach control inherits from the lens bar', () => {
    it('opens on the bar\'s tier', () => {
      renderSheet({ barTierId: '90' });
      expect(chip('window-spot-sheet-reach', '1h 30min')).toHaveAttribute('aria-pressed', 'true');
    });

    it('says nothing about widening while it still matches the bar', () => {
      renderSheet({ barTierId: '45' });
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });

    it('marks itself widened once a looser tier is chosen', () => {
      renderSheet({ barTierId: '45' });
      fireEvent.click(chip('window-spot-sheet-reach', 'Any'));
      expect(screen.getByTestId('window-spot-sheet-widened'))
        .toHaveTextContent('widened for browsing');
    });

    it('does NOT say widened when the reader has TIGHTENED below the bar', () => {
      // Keyed on direction, not on difference: "widened for browsing" over a list shorter than the
      // strip behind it would simply be false.
      renderSheet({ barTierId: '150' });
      fireEvent.click(chip('window-spot-sheet-reach', '45 min'));
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });

    it('shows spots the strip could not, which is what the widening is for', () => {
      renderSheet({ barTierId: '45' });
      expect(screen.queryByText('High Force')).toBeNull();
      fireEvent.click(chip('window-spot-sheet-reach', 'Any'));
      expect(screen.getByText('High Force')).toBeInTheDocument();
    });

    describe('for a LITE user, who cannot move the bar either', () => {
      it('disables every tier, so pointer-events is not the only guard', () => {
        renderSheet({ reachLocked: true, barTierId: 'any' });
        const tiers = within(screen.getByTestId('window-spot-sheet-reach')).getAllByRole('button');
        expect(tiers).toHaveLength(4);
        tiers.forEach((tier) => expect(tier).toBeDisabled());
      });

      it('offers the upgrade OUTSIDE the greyed box, where the contrast floor still applies', () => {
        renderSheet({ reachLocked: true, barTierId: 'any' });
        const upsell = screen.getByTestId('window-spot-sheet-upsell');
        expect(upsell).toHaveTextContent('Pro');
        expect(screen.getByTestId('window-spot-sheet-reach-wrap')).not.toContainElement(upsell);
      });

      it('leaves the rating floor and the type control ungated — they withhold nothing', () => {
        renderSheet({ reachLocked: true, barTierId: 'any' });
        within(screen.getByTestId('window-spot-sheet-rating')).getAllByRole('button')
          .forEach((b) => expect(b).not.toBeDisabled());
        within(screen.getByTestId('window-spot-sheet-type')).getAllByRole('button')
          .forEach((b) => expect(b).not.toBeDisabled());
      });

      it('greys only the control, never the whole bar', () => {
        renderSheet({ reachLocked: true, barTierId: 'any' });
        expect(screen.getByTestId('window-spot-sheet-reach-wrap').className)
          .toContain('wf-lens-locked');
      });
    });
  });

  describe('the rating floor', () => {
    it('is offered when something in the window is rated', () => {
      renderSheet();
      expect(chip('window-spot-sheet-rating', 'Any rating')).toHaveAttribute('aria-pressed', 'true');
    });

    it('is NOT offered when nothing in the window is rated', () => {
      renderSheet({ card: { ...CARD, allSpots: [UNRATED] } });
      expect(seg('window-spot-sheet-rating')).toBeNull();
    });

    it('does not gate when it is not offered, however the bar is set', () => {
      // The pairing that keeps an INHERITED floor safe, and the reason the bar itself needs no such
      // predicate: there the control is sticky and always on screen, here it is conditional, and a
      // floor running with no chip in the dialog is a filter the reader cannot see or undo.
      renderSheet({ card: { ...CARD, allSpots: [UNRATED] }, barFloorId: '4' });
      expect(screen.getByText('Cresswell')).toBeInTheDocument();
    });

    it('trims the list to the floor, dropping the unrated with it', () => {
      renderSheet({ barTierId: 'any' });
      fireEvent.click(chip('window-spot-sheet-rating', '4★+'));
      const names = screen.getAllByTestId('window-spot').map((c) => c.textContent);
      expect(names.some((n) => n.includes('High Force'))).toBe(true);
      expect(names.some((n) => n.includes('Bamburgh Beach'))).toBe(true);
      expect(names.some((n) => n.includes('Simonside'))).toBe(false);
      expect(names.some((n) => n.includes('Cresswell'))).toBe(false);
    });

    it('takes the same axis colour the bar\'s floor does, because it is the same setting', () => {
      // A floor that is green on the bar and amber a click away is two settings to a reader. The
      // class is what jsdom can see; the colour is in the stylesheet beside its measurement.
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-rating').className).toContain('wf-seg-rating');
      expect(screen.getByTestId('window-spot-sheet-reach').className).not.toContain('wf-seg-rating');
    });

    it('opens on the bar\'s floor, exactly as the reach control opens on its tier', () => {
      renderSheet({ barFloorId: '4' });
      expect(chip('window-spot-sheet-rating', '4★+')).toHaveAttribute('aria-pressed', 'true');
    });

    it('keeps a 5★ step the bar does not offer, because one window is where it is a real question', () => {
      // The bar's list is three long and this one is four. A page-wide 5★ floor usually empties six
      // windows at once; over one window's whole list "only the best" is worth asking, and it is a
      // browsing choice that dies with the dialog.
      renderSheet({ barTierId: 'any' });
      const chips = within(screen.getByTestId('window-spot-sheet-rating')).getAllByRole('button');
      expect(chips.map((c) => c.textContent)).toEqual(['Any rating', '3★+', '4★+', '5★']);
    });

    it('forgets a floor chosen here, because the one the reader keeps is the bar\'s', () => {
      // The change from P11, and the whole of it: this control no longer writes storage. What it
      // holds is a deviation from the page, and a deviation that outlived its dialog would be a
      // page-wide filter set from a surface that never said it was setting one.
      const { unmount } = renderSheet();
      fireEvent.click(chip('window-spot-sheet-rating', '4★+'));
      expect(localStorage.length).toBe(0);
      unmount();

      renderSheet();
      expect(chip('window-spot-sheet-rating', 'Any rating')).toHaveAttribute('aria-pressed', 'true');
    });

    it('marks a loosened floor as widened for browsing, as a loosened tier already is', () => {
      // Both axes are inherited, so both can show the reader spots the page behind the dialog is
      // hiding. Marking one and not the other would leave the same statement half-made.
      renderSheet({ barFloorId: '4' });
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();

      fireEvent.click(chip('window-spot-sheet-rating', 'Any rating'));
      expect(screen.getByTestId('window-spot-sheet-widened')).toBeInTheDocument();
    });

    it('does not call a TIGHTENED floor a widening', () => {
      // The direction rule the reach control already holds: "widened for browsing" over a list
      // shorter than the strip behind it is simply false.
      renderSheet();
      fireEvent.click(chip('window-spot-sheet-rating', '4★+'));
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });
  });

  describe('the type control', () => {
    it('offers one chip per type present, and Any', () => {
      renderSheet({ barTierId: 'any' });
      const chips = within(screen.getByTestId('window-spot-sheet-type')).getAllByRole('button');
      expect(chips.map((c) => c.textContent))
        .toEqual(['Any type', 'Landscape', 'Seascape', 'Waterfall']);
    });

    it('is NOT offered when every spot is one type — that is a label, not a control', () => {
      renderSheet({ card: { ...CARD, allSpots: [COAST, UNRATED] } });
      expect(seg('window-spot-sheet-type')).toBeNull();
    });

    it('is NOT offered when the roster never arrived', () => {
      renderSheet({ typesByName: new Map() });
      expect(seg('window-spot-sheet-type')).toBeNull();
    });

    it('filters the list to that type', () => {
      renderSheet({ barTierId: 'any' });
      fireEvent.click(chip('window-spot-sheet-type', 'Seascape'));
      expect(screen.getAllByTestId('window-spot')).toHaveLength(2);
      expect(screen.queryByText('Simonside')).toBeNull();
    });

    it('names the type on each card, in the same words as the chip', () => {
      renderSheet({ barTierId: 'any' });
      const bamburgh = screen.getAllByTestId('window-spot')
        .find((c) => c.textContent.includes('Bamburgh Beach'));
      expect(within(bamburgh).getByTestId('window-spot-region'))
        .toHaveTextContent('Northumberland & Tyneside · Seascape');
    });

    it('names no type on a card when no type control was drawn', () => {
      renderSheet({ card: { ...CARD, allSpots: [COAST, UNRATED] }, barTierId: 'any' });
      const bamburgh = screen.getAllByTestId('window-spot')
        .find((c) => c.textContent.includes('Bamburgh Beach'));
      expect(within(bamburgh).getByTestId('window-spot-region'))
        .toHaveTextContent('Northumberland & Tyneside');
      expect(within(bamburgh).getByTestId('window-spot-region')).not.toHaveTextContent('Seascape');
    });

    it('starts loose on every visit, unlike the floor', () => {
      const { unmount } = renderSheet({ barTierId: 'any' });
      fireEvent.click(chip('window-spot-sheet-type', 'Waterfall'));
      unmount();
      renderSheet({ barTierId: 'any' });
      expect(chip('window-spot-sheet-type', 'Any type')).toHaveAttribute('aria-pressed', 'true');
    });
  });

  describe('the list', () => {
    it('prints each card\'s leave-by line from the SPOT\'s event time, not the header\'s', () => {
      // The sheet renders `WindowSpotCard` rather than a copy of it, which is the whole reason the
      // component was extracted (P11) — so the leave-by line arrives here without this file's
      // subject knowing about it. Pinned anyway: the drill-down is where a reader compares a long
      // drive against a short one, so it is the surface the line matters most on.
      //
      // The card header says `20:41` and this spot's own sunset is 21:41 BST, an hour apart on
      // purpose: this dialog is the one surface that has a window time in hand and could pass it
      // down. 21:41 − a 30-minute drive − 20 minutes of setup = 20:51, so a sheet that used its
      // own header would print 19:51 and fail here.
      renderSheet({
        card: { ...CARD, allSpots: [{ ...COAST, solarEventTime: '2026-08-08T20:41:00' }] },
        barTierId: 'any',
      });
      expect(screen.getByTestId('window-spot-leave')).toHaveTextContent('↰ leave 20:51');
    });

    it('ranks by rating then drive, the strip\'s own order', () => {
      renderSheet({ barTierId: 'any' });
      const cards = screen.getAllByTestId('window-spot');
      // Rating descending, then drive ascending, with the unrated last — an unknown is not a good.
      expect(cards.map((c) => c.getAttribute('data-rating'))).toEqual(['5', '4', '3', null]);
      expect(cards[0]).toHaveTextContent('High Force');
      expect(cards[3]).toHaveTextContent('Cresswell');
    });

    it('opens the map on the spot that was clicked', () => {
      const onOpenSpot = vi.fn();
      renderSheet({ onOpenSpot, barTierId: 'any' });
      const simonside = screen.getAllByTestId('window-spot')
        .find((c) => c.textContent.includes('Simonside'));
      fireEvent.click(simonside);
      expect(onOpenSpot).toHaveBeenCalledWith(expect.objectContaining({ locationName: 'Simonside' }));
    });
  });

  describe('the empty state names what the reader can relax', () => {
    it('names the reach alone when only the reach is narrowing', () => {
      renderSheet({ card: { ...CARD, allSpots: [FALLS] }, barTierId: '45' });
      expect(screen.getByTestId('window-spot-sheet-empty'))
        .toHaveTextContent('Nothing matches — widen the reach.');
    });

    it('names both once a floor is on as well', () => {
      renderSheet({ card: { ...CARD, allSpots: [FALLS, FELL] }, barTierId: '45' });
      fireEvent.click(chip('window-spot-sheet-rating', '5★'));
      expect(screen.getByTestId('window-spot-sheet-empty'))
        .toHaveTextContent('Nothing matches — widen the reach or drop the rating floor.');
    });

    it('keeps the scroll container mounted, with the message inside it', () => {
      // The list is the card's ONLY scroll container. Swapping it for a sibling paragraph left a
      // short viewport — a landscape phone, a 400% zoom — with a header, three rows of controls and
      // a footer inside `overflow: hidden` and nothing able to scroll.
      renderSheet({ card: { ...CARD, allSpots: [FALLS] }, barTierId: '45' });
      const list = screen.getByTestId('window-spot-sheet-list');
      expect(list).toContainElement(screen.getByTestId('window-spot-sheet-empty'));
      expect(within(list).queryAllByTestId('window-spot')).toHaveLength(0);
    });

    it('claims no sort over an empty list', () => {
      // `spotOrderStatement` falls through to "Listed alphabetically" when no spot carries a key,
      // which over nothing at all is a claim about an ordering that never happened.
      renderSheet({ card: { ...CARD, allSpots: [FALLS] }, barTierId: '45' });
      const foot = screen.getByTestId('window-spot-sheet-count');
      expect(foot).toHaveTextContent('0 of 1');
      expect(foot).not.toHaveTextContent('Listed alphabetically');
      expect(foot).not.toHaveTextContent('Ranked by');
    });
  });

  describe('the footer', () => {
    it('announces the count as a live region, so a filter press is not silent', () => {
      // Every other element in the footer is prose. This one is the only thing that changes when a
      // chip is pressed, and it is the only one mounted on every state — a live region added to the
      // DOM in the same commit as its content is unreliably announced.
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveAttribute('role', 'status');
    });

    it('states the drawn count against what the controls could bring back', () => {
      // High Force is a 2-hour drive, so the 45-minute tier withholds exactly one of the four.
      renderSheet({ barTierId: '45' });
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('3 of 4');
    });

    it('drops the second number once nothing is withheld', () => {
      renderSheet({ barTierId: 'any' });
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('4 spots');
    });

    it('states the sort it actually applied', () => {
      renderSheet({ barTierId: 'any' });
      expect(screen.getByTestId('window-spot-sheet-count'))
        .toHaveTextContent('Ranked by rating, then drive time.');
    });

    it('claims no rating in the sort when no spot carries one', () => {
      renderSheet({ card: { ...CARD, allSpots: [UNRATED] }, barTierId: 'any' });
      expect(screen.getByTestId('window-spot-sheet-count'))
        .toHaveTextContent('Ranked by drive time.');
    });

    it('states the one policy that now covers all three controls', () => {
      // It used to read "Rating floor is remembered · reach and type reset each visit". The floor
      // is the bar's now and this control only borrows it, so claiming the DIALOG remembers one
      // would describe a setting while pointing at a control that no longer keeps it.
      renderSheet();
      expect(screen.getByTestId('window-spot-sheet-note'))
        .toHaveTextContent('Reach, rating and type reset each visit');
    });

    it('names only the controls that are on screen', () => {
      renderSheet({ card: { ...CARD, allSpots: [UNRATED] } });
      const note = screen.getByTestId('window-spot-sheet-note');
      expect(note).not.toHaveTextContent('rating');
      // Sentence case on whichever clause leads — without it the footer opens lowercase beside a
      // sentence-case count.
      expect(note).toHaveTextContent('Reach resets each visit');
    });

    it('claims nothing at all when the only control is one the user cannot move', () => {
      renderSheet({ card: { ...CARD, allSpots: [UNRATED] }, reachLocked: true, barTierId: 'any' });
      expect(screen.queryByTestId('window-spot-sheet-note')).toBeNull();
    });
  });
});
