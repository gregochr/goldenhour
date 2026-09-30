package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InversionHotTopicStrategy}.
 *
 * <p>Round 3 of Phase 2 "record conditions for every place" (owner decision 2026-09-30, a Codex P1
 * against PR #948's second cut) moved this detector onto
 * {@link SurvivorSignals#effectiveInversionScore()} — the ONE shared rule: the calculator's
 * {@link SurvivorSignals.Readings#inversionScore()} when {@link SurvivorSignals.Readings#inversionScored()}
 * is true, else Claude's {@link SurvivorSignals.Scores#inversion()} echo.
 *
 * <p>⚠️ <b>Round 4 (a further Codex P1) REVERSES round 3's own flip a second time, for a narrower
 * case.</b> Round 3 made a null reading with a strong echo fire — correct for a genuinely UNSCORED
 * row (one written before the {@code inversion_scored} column existed), but round 3's fixtures
 * could not distinguish that from a FRESH row whose calculator legitimately found nothing (an
 * ineligible location, or missing weather inputs) and correctly wrote a null. This class's
 * fixtures now set {@code scored} explicitly and independently of the reading value:
 * {@code detect_freshNullWithStrongEcho_silent} is the new, narrower case (scored=true, reading
 * null, echo strong → silent, reversing round 3's own
 * {@code detect_nullReadingWithStrongEcho_fires}), and
 * {@code detect_preColumnRowWithStrongEcho_fires} keeps round 3's original intent alive under an
 * accurate name (scored=false, reading null, echo strong → fires). The reading always wins
 * whenever {@code scored} is true, whatever its value including null; the band label is DERIVED
 * from the effective score via {@code PromptBuilder.InversionPotential.fromScore} rather than read
 * off a stored classification, since the calculator produces no such string. Expired mornings are
 * dropped via {@link SolarEventFreshness} (mocked here), and every remaining strong-inversion
 * morning is enumerated in the pill.
 */
@ExtendWith(MockitoExtension.class)
class InversionHotTopicStrategyTest {

    // 2026-06-17 is a Wednesday; +2 = Friday.
    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SurvivorSignalReader survivorSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private InversionHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new InversionHotTopicStrategy(survivorSignalReader, freshness);
    }

    /** Keeps each given morning ahead of the freshness cutoff. Stubbed per-test (only the tests that
     * reach the freshness filter need it) rather than leniently; the date + SUNRISE event are pinned,
     * the location varies per row and is not the discriminator (the filter is per-morning). */
    private void stubAhead(LocalDate... mornings) {
        for (LocalDate morning : mornings) {
            when(freshness.isAhead(any(LocationEntity.class), eq(morning), eq(TargetType.SUNRISE)))
                    .thenReturn(true);
        }
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    /**
     * A SUNRISE survivor composite carrying an inversion score in {@link SurvivorSignals.Readings}
     * — the shape the writer now produces for any row this detector admits.
     */
    private static SurvivorSignals signal(LocalDate date, String regionName, double inversionScore) {
        return signalAt(date, regionName, inversionScore, TargetType.SUNRISE);
    }

    /** A survivor composite for a specific solar event, carrying a SCORED inversion reading —
     * the shape a fresh write produces when the calculator found something to report. */
    private static SurvivorSignals signalAt(LocalDate date, String regionName,
            double inversionScore, TargetType eventType) {
        LocationEntity location = new LocationEntity();
        if (regionName != null) {
            RegionEntity region = new RegionEntity();
            region.setName(regionName);
            location.setRegion(region);
        }
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                inversionScore, true);
        return new SurvivorSignals(location, date, eventType, SurvivorSignals.Scores.EMPTY, readings);
    }

    /** A SUNRISE row from BEFORE the {@code inversion_scored} column existed — {@code scored} is
     * false, so its only inversion evidence is Claude's {@code Scores} echo. Exercises
     * {@code effectiveInversionScore()}'s fallback arm. */
    private static SurvivorSignals signalScoresOnly(LocalDate date, String regionName,
            int scoresInversion) {
        return signalBoth(date, regionName, false, null, scoresInversion);
    }

    /**
     * A SUNRISE row carrying a calculator reading and Claude's echo, independently, with
     * {@code scored} set explicitly rather than inferred from the reading — the reading and
     * "the calculator looked at this slot" are two separate facts since round 4 (a fresh row can
     * legitimately carry a null reading and still be {@code scored}).
     *
     * @param scored       {@link SurvivorSignals.Readings#inversionScored()}
     * @param readingScore the calculator's {@code Readings.inversionScore()}, or null
     * @param echoScore    Claude's {@code Scores.inversion()} echo, or null
     */
    private static SurvivorSignals signalBoth(LocalDate date, String regionName,
            boolean scored, Double readingScore, Integer echoScore) {
        LocationEntity location = new LocationEntity();
        if (regionName != null) {
            RegionEntity region = new RegionEntity();
            region.setName(regionName);
            location.setRegion(region);
        }
        SurvivorSignals.Scores scores = echoScore == null
                ? SurvivorSignals.Scores.EMPTY
                : new SurvivorSignals.Scores(echoScore, "STRONG", null, null);
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                readingScore, scored);
        return new SurvivorSignals(location, date, TargetType.SUNRISE, scores, readings);
    }

    @Test
    @DisplayName("strong inversion survivor fires with priority 2 off the readings surface")
    void detect_strongInversion_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9.0)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("INVERSION");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.detail()).isEqualTo("Strong inversion likely at elevated locations");
        assertThat(topic.regions()).containsExactly("The North York Moors");
    }

    @Test
    @DisplayName("fact line shows the derived strength band; no fabricated inversion-top altitude")
    void detect_strongInversion_factLine() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "inversion").value()).isEqualTo("9/10 · strong");
        assertThat(topic.facts()).noneMatch(f -> f.value() != null && f.value().contains(" m"));
        assertThat(topic.note())
                .isEqualTo("climb above it — the valleys fill with cloud, burning off after sunrise");
    }

    @Test
    @DisplayName("the strongest of a day's rows drives the score fact")
    void detect_representative_highestScore() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of(
                signal(FROM, "The Lake District", 9.0),
                signal(FROM, "The Lake District", 10.0)));
        stubAhead(FROM);

        HotTopic topic = strategy.detect(FROM, TO).get(0);

        assertThat(factWithKey(topic, "inversion").value()).isEqualTo("10/10 · strong");
    }

    @Test
    @DisplayName("the band label is derived from the score, matching PromptBuilder.InversionPotential "
            + "at 8 (moderate, never fires), 9 and 10 (strong)")
    void bandLabel_matchesInversionPotentialFromScore() {
        assertThat(InversionHotTopicStrategy.bandLabel(8)).isEqualTo("moderate");
        assertThat(InversionHotTopicStrategy.bandLabel(9)).isEqualTo("strong");
        assertThat(InversionHotTopicStrategy.bandLabel(10)).isEqualTo("strong");
    }

    @Test
    @DisplayName("no survivor rows does not fire")
    void detect_noRows_doesNotFire() {
        when(survivorSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("boundary: score 9 fires (STRONG), score 8 does not (MODERATE)")
    void detect_strongBandBoundary() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 8.0)));
        assertThat(strategy.detect(FROM, TO)).isEmpty();

        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The Lake District", 9.0)));
        stubAhead(FROM);
        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("a PRE-COLUMN row (scored=false) with a null reading and a strong Claude echo "
            + "(10) FIRES — this row predates the inversion_scored column, so its null reading "
            + "carries no information and the echo is read as a stand-in for a slot the "
            + "calculator has never reached (round 3's original intent, kept under an accurate "
            + "name in round 4)")
    void detect_preColumnRowWithStrongEcho_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalScoresOnly(FROM, "The Lake District", 10)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(factWithKey(topics.get(0), "inversion").value()).isEqualTo("10/10 · strong");
    }

    @Test
    @DisplayName("REVERSES round 3's detect_nullReadingWithStrongEcho_fires: a FRESH row "
            + "(scored=true) with a null reading and a strong Claude echo (10) is SILENT — the "
            + "null is authoritative (an ineligible location, or the calculator found no weather "
            + "inputs to score), so it must not fall back to a stale echo a later evaluation "
            + "never overwrote (round 4, a Codex P1 against round 3's own fix)")
    void detect_freshNullWithStrongEcho_silent() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalBoth(FROM, "The Lake District", true, null, 10)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a LOWER calculator reading (8, MODERATE) beats a HIGHER Claude echo (10) — the "
            + "reading wins whenever the calculator has scored the slot, even when disagreeing "
            + "downward, so this stays silent")
    void detect_readingBelowThreshold_beatsStrongEcho_silent() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalBoth(FROM, "The Lake District", true, 8.0, 10)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a calculator reading of 10 with no echo at all still fires — the ordinary, "
            + "already-covered case, named explicitly for the effective-score contract")
    void detect_readingPresentEchoNull_fires() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalBoth(FROM, "The Lake District", true, 10.0, null)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(factWithKey(topics.get(0), "inversion").value()).isEqualTo("10/10 · strong");
    }

    @Test
    @DisplayName("an unscored row with no echo either (no inversion evidence at all) is silent")
    void detect_bothNull_silent() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalBoth(FROM, "The Lake District", false, null, null)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("today's inversion is suppressed once its sunrise has passed")
    void detect_todayPastSunrise_suppressed() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signal(FROM, "The North York Moors", 9.0)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("past today's sunrise, the topic rolls forward to the next strong morning")
    void detect_todayPastSunrise_rollsForwardToFutureDay() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, "Today Region", 9.0),
                        signal(FROM.plusDays(1), "Tomorrow Region", 9.0)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);
        stubAhead(FROM.plusDays(1));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM.plusDays(1));
        assertThat(topics.get(0).detail()).isEqualTo("Strong inversion likely at elevated locations");
        assertThat(topics.get(0).regions()).containsExactly("Tomorrow Region");
    }

    @Test
    @DisplayName("emits one card per non-expired morning, each with that day's regions")
    void detect_multipleDays_onePerDate() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM.plusDays(2), "Northumberland", 9.0),
                        signal(FROM, "The North York Moors", 9.0),
                        signal(FROM, "The Lake District", 10.0)));
        stubAhead(FROM, FROM.plusDays(2));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(HotTopic::date)
                .containsExactly(FROM, FROM.plusDays(2));
        assertThat(topics.get(0).regions())
                .containsExactly("The North York Moors", "The Lake District");
        assertThat(topics.get(1).regions()).containsExactly("Northumberland");
        // No spanning day phrase — the day is carried by each card's own date.
        assertThat(topics).noneMatch(t -> t.detail().contains("and Friday"));
    }

    @Test
    @DisplayName("sunset inversion rows are ignored — inversion is a dawn-only topic")
    void detect_sunsetRow_ignored() {
        // A SUNSET inversion row would pass the (stubbed-ahead) freshness check, but must be
        // dropped up front: a sea of clouds is a dawn event, so this-morning's inversion must not
        // linger into the evening on the strength of the still-future sunset.
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(signalAt(FROM, "The North York Moors", 9.0, TargetType.SUNSET)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("mixed sunrise + sunset rows: only the sunrise row drives the card")
    void detect_mixedRows_onlySunriseCounts() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signalAt(FROM, "Sunset Region", 9.0, TargetType.SUNSET),
                        signalAt(FROM, "Sunrise Region", 9.0, TargetType.SUNRISE)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).regions()).containsExactly("Sunrise Region");
    }

    @Test
    @DisplayName("null region in a strong row is skipped")
    void detect_nullRegion_skipped() {
        when(survivorSignalReader.read(FROM, TO))
                .thenReturn(List.of(
                        signal(FROM, null, 9.0),
                        signal(FROM, "The North York Moors", 9.0)));
        stubAhead(FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics.get(0).regions()).containsExactly("The North York Moors");
    }
}
