package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import java.util.List;

/**
 * How good a day's best region is, across the events of that day the Plan tab actually renders.
 *
 * <p>This is the day card's own answer, and it used to be derived in the browser — a roll-up over
 * every region of every rendered event, picking the best band each region reaches and then the best
 * band any region reaches. That made a third aggregator over one payload, alongside the window
 * verdict and the grid cell, with nothing keeping the three in step. See
 * {@code docs/engineering/plan-verdict-consolidation-plan.md} §1 D1 and §4 Phase 3.
 *
 * <p><b>Scoped to the rendered events, not to the day.</b> A day carries a sunrise and a sunset; the
 * render horizon can include one and not the other, and a tile that rolled up an event it does not
 * draw would describe a window the reader cannot see. The projector computes this from the same
 * rendered-event list it publishes and scopes the picks to, so all three agree by construction.
 *
 * <p><b>It names its peak-tier regions rather than copying them.</b> {@code regions} carries
 * identity only — region name, which event it reached its band on, and that band. Everything the
 * chip renders beside it (weather, gloss, summary) is looked up in the same response. Copying those
 * fields here would create a second source of truth for one region's prose inside one payload.
 *
 * @param verdict the day's best band across its rendered events: WORTH_IT if any region reaches it,
 *                else MAYBE if any does, else STAND_DOWN. Never AWAITING and never null — a day with
 *                no region at either band is STAND_DOWN, which is the "All poor" the tile has always
 *                shown for it
 * @param events  the rendered events on which a region reached {@code verdict}; empty when none did.
 *                One entry means the tile can name that event ("Worth it · sunset"), two means both.
 *
 *                <p><b>A known under-report, ported deliberately rather than fixed.</b> Because a
 *                region appears once (see below), a day whose ONLY peak-band region reaches that
 *                band at both its sunrise and its sunset yields one entry, and the tile reads
 *                "· sunrise" rather than "· both". The client roll-up this replaced did exactly the
 *                same, and the day card is specified as an ownership move with no semantic change —
 *                so it is recorded here rather than corrected. The reason not to correct it was
 *                that doing so would move this arm away from the v1 Plan tab while v1 was the
 *                pilot's frozen comparison control; v1 has since been retired, so correcting it
 *                is now an ordinary product call rather than a change to a control
 * @param regions the regions that reached {@code verdict}, in the payload's own region order; empty
 *                when {@code verdict} is STAND_DOWN. A region appearing on both of the day's events
 *                appears once, on the event where it reached the better band — the tile names a
 *                region's best showing that day, and two chips for one place would double the
 *                rail's densest element
 */
public record BriefingDayPeak(
        DisplayVerdict verdict,
        List<TargetType> events,
        List<PeakRegion> regions) {

    public BriefingDayPeak {
        events = events == null ? List.of() : List.copyOf(events);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /**
     * One region at the day's peak band, named so the client can find it in the same response.
     *
     * @param regionName     the region's display name — the key back into the event summary
     * @param targetType     the event this region reached {@code displayVerdict} on
     * @param displayVerdict that region's own verdict, equal to the day's peak by construction
     */
    public record PeakRegion(
            String regionName,
            TargetType targetType,
            DisplayVerdict displayVerdict) {
    }
}
