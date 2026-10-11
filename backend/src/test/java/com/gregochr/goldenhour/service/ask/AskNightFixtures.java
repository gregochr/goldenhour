package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.AskSnapshot.Night;
import com.gregochr.goldenhour.service.ask.AskSnapshot.NightSpot;
import com.gregochr.goldenhour.service.ask.AskSnapshot.Nights;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Builders for the night shapes the ask-night tests read: spots, nights and a snapshot carrying them. */
final class AskNightFixtures {

    /** Tonight in the fixtures: the date whose dusk opens the night (the same Monday as the solar ones). */
    static final LocalDate TONIGHT = AskFixtures.TODAY;

    /** Tomorrow night. */
    static final LocalDate TOMORROW_NIGHT = TONIGHT.plusDays(1);

    /** The astro run that wrote the fixtures' rows. */
    static final Instant GENERATION = Instant.parse("2026-10-05T14:00:00Z");

    /** The night's nautical dusk (UTC): 19:30 in London in October. */
    static final LocalDateTime DUSK = LocalDateTime.of(2026, 10, 5, 18, 30);

    /** The night's nautical dawn (UTC). */
    static final LocalDateTime DAWN = LocalDateTime.of(2026, 10, 6, 5, 0);

    private AskNightFixtures() {
    }

    /** A place with an astro figure only. */
    static NightSpot astro(long id, String name, String region, int stars, int bortle) {
        return new NightSpot(id, name, region, 54.9, -1.6, false, bortle, 21.4, stars, "Summary " + stars,
                "Clear skies", "Excellent visibility", "Moon below the horizon", "WAXING_GIBBOUS", 85.0, null, null,
                false, null, null, null);
    }

    /** A place with an aurora figure, and the astro figure beside it when {@code astroStars} is given. */
    static NightSpot aurora(long id, String name, String region, Integer astroStars, int auroraStars, int bortle) {
        return new NightSpot(id, name, region, 54.9, -1.6, false, bortle, 21.4, astroStars,
                astroStars == null ? null : "Summary " + astroStars, astroStars == null ? null : "Clear skies",
                astroStars == null ? null : "Excellent visibility",
                astroStars == null ? null : "Moon below the horizon", astroStars == null ? null : "WAXING_GIBBOUS",
                astroStars == null ? null : 85.0, auroraStars,
                "Aurora summary " + auroraStars, false, null, "MODERATE", 6.0);
    }

    /** The same place with its aurora row a weather-triage template. */
    static NightSpot triaged(NightSpot spot) {
        return new NightSpot(spot.locationId(), spot.name(), spot.regionName(), spot.lat(), spot.lon(),
                spot.canopy(), spot.bortleClass(), spot.skyBrightnessSqm(), spot.astroStars(), spot.astroSummary(),
                spot.cloudExplanation(), spot.visibilityExplanation(), spot.moonExplanation(), spot.moonPhase(),
                spot.moonIlluminationPct(), 1, "Cloud", true, "Overcast", "MODERATE", 6.0);
    }

    /** The same place flagged as a wood. */
    static NightSpot canopy(NightSpot spot) {
        return new NightSpot(spot.locationId(), spot.name(), spot.regionName(), spot.lat(), spot.lon(), true,
                spot.bortleClass(), spot.skyBrightnessSqm(), spot.astroStars(), spot.astroSummary(),
                spot.cloudExplanation(), spot.visibilityExplanation(), spot.moonExplanation(), spot.moonPhase(),
                spot.moonIlluminationPct(), spot.auroraStars(), spot.auroraSummary(), spot.triaged(),
                spot.triageReason(), spot.alertLevel(), spot.maxKp());
    }

    /** An ASTRO night of the fixtures' generation. */
    static Night astroNight(LocalDate date, NightSpot... spots) {
        return new Night(AskWindowId.format(date, AskWindowKind.ASTRO), date, AskWindowKind.ASTRO, DUSK, DAWN,
                GENERATION, List.of(spots));
    }

    /** An AURORA night carrying the date's astro window. */
    static Night auroraNight(LocalDate date, NightSpot... spots) {
        return new Night(AskWindowId.format(date, AskWindowKind.AURORA), date, AskWindowKind.AURORA, DUSK, DAWN,
                GENERATION, List.of(spots));
    }

    /** A snapshot of the solar briefing carrying these nights, with tonight {@link #TONIGHT}. */
    static AskSnapshot withNights(AskSnapshot base, AuroraState state, Night... nights) {
        return new AskSnapshot(base.generatedAt(), base.runLabel(), base.today(), base.windows(), base.hotTopics(),
                base.comingUp(), base.briefingStale(), new Nights(TONIGHT, List.of(nights), state));
    }

    /** A snapshot with no solar windows at all, carrying these nights. */
    static AskSnapshot nightsOnly(AuroraState state, Night... nights) {
        return withNights(AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of())), state, nights);
    }
}
