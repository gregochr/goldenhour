package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SnowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnowFreshHotTopicStrategy}: SNOW_FRESH and SNOW_MIST from each place's own
 * {@code snow_state} SUNRISE row (snow-topics-truth-plan §3.3 rule 1), the valley-mist-band rule that decides
 * SNOW_MIST, the per-day exact-place topics and the 3.0 mm lying boundary.
 */
@ExtendWith(MockitoExtension.class)
class SnowFreshHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-15T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FRESH_AT = LocalDateTime.of(2024, 1, 15, 4, 0);

    private static final String LAKES = "The Lake District";
    private static final String MOORS = "The North York Moors";

    @Mock
    private SlotSignalReader slotSignalReader;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private SnowStateRepository snowStateRepository;

    private SnowFreshHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SnowFreshHotTopicStrategy(slotSignalReader, locationRepository,
                new SnowStateReader(snowStateRepository, CLOCK));
    }

    private static LocationEntity place(long id, String name, String regionName) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        location.setRegion(region);
        return location;
    }

    private static SnowStateEntity row(LocationEntity place, LocalDate date, TargetType event, Double waterMm,
            Double freshCm) {
        return SnowStateEntity.builder().pointKey(SnowStateEntity.locationKey(place.getId()))
                .evaluationDate(date).eventType(event).snowWaterMm(waterMm).snowfall24hCm(freshCm)
                .computedAt(FRESH_AT).build();
    }

    private static SnowStateEntity lying(LocationEntity place, LocalDate date, double waterMm, Double freshCm) {
        return row(place, date, TargetType.SUNRISE, waterMm, freshCm);
    }

    /** A slot composite whose valley mist band is {@code band} and whose 2 m temperature is given. */
    private static SlotSignals slot(LocationEntity place, LocalDate date, TargetType event, MistPotential band,
            Double temperatureCelsius) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, 90, null, null, null, temperatureCelsius, null, false,
                null, true, band);
        return new SlotSignals(place, date, event, SlotSignals.Scores.EMPTY, readings);
    }

    private static SlotSignals likely(LocationEntity place, LocalDate date, Double temperatureCelsius) {
        return slot(place, date, TargetType.SUNRISE, MistPotential.LIKELY, temperatureCelsius);
    }

    private List<HotTopic> detect(List<LocationEntity> roster, List<SnowStateEntity> rows,
            List<SlotSignals> signals) {
        return strategy.detect(FROM, TO, HotTopicInputs.ofSnow(() -> signals, () -> roster, () -> rows));
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    // ── SNOW_FRESH from the place's own row ──

    @Test
    @DisplayName("a place's own lying snow fires SNOW_FRESH at priority 2, ringing exactly that place")
    void detect_ownSnowLying_firesFreshForThatPlaceOnly() {
        LocationEntity rydal = place(1, "Rydal Water", LAKES);
        LocationEntity mallerstang = place(2, "Mallerstang", LAKES);

        List<HotTopic> topics = detect(List.of(rydal, mallerstang),
                List.of(lying(mallerstang, FROM, 8.0, 0.0)), List.of());

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_FRESH");
        assertThat(topic.label()).isEqualTo("Fresh snow");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.regions()).containsExactly(LAKES);
        assertThat(topic.locationNames()).containsExactly("Mallerstang");
        assertThat(topic.note()).isEqualTo("clean at first light, before wind and footprints");
    }

    @Test
    @DisplayName("nothing fell in the last 24 h: 'Snow lying' detail and a 'lying' fact")
    void detect_nothingFellRecently_snowLyingCopy() {
        LocationEntity mallerstang = place(2, "Mallerstang", LAKES);

        HotTopic topic = detect(List.of(mallerstang),
                List.of(lying(mallerstang, FROM, 8.0, 0.9)), List.of()).get(0);

        assertThat(topic.detail()).isEqualTo("Snow lying");
        assertThat(topic.facts()).extracting(HotTopicFact::value).containsExactly("lying");
    }

    @Test
    @DisplayName("snow that fell in the last 24 h (1 cm or more): 'Fresh snow overnight' and 'fresh ~N cm'")
    void detect_snowFellRecently_freshOvernightCopy() {
        LocationEntity mallerstang = place(2, "Mallerstang", LAKES);
        LocationEntity rydal = place(1, "Rydal Water", LAKES);

        HotTopic topic = detect(List.of(mallerstang, rydal),
                List.of(lying(mallerstang, FROM, 8.0, 5.4), lying(rydal, FROM, 4.0, 1.0)), List.of()).get(0);

        assertThat(topic.detail()).isEqualTo("Fresh snow overnight");
        assertThat(topic.facts()).extracting(HotTopicFact::value).containsExactly("fresh ~5 cm");
    }

    @Test
    @DisplayName("exactly 1 cm is fresh; a row with no snowfall figure reads as lying")
    void detect_freshBoundaryAndNullSnowfall() {
        LocationEntity a = place(1, "A", LAKES);
        LocationEntity b = place(2, "B", MOORS);

        List<HotTopic> topics = detect(List.of(a, b),
                List.of(lying(a, FROM, 4.0, 1.0), lying(b, FROM, 4.0, null)), List.of());

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).facts()).extracting(HotTopicFact::value).containsExactly("fresh ~1 cm");
        assertThat(topics.get(0).regions()).containsExactly(LAKES, MOORS);

        assertThat(detect(List.of(b), List.of(lying(b, FROM, 4.0, null)), List.of()).get(0).facts())
                .extracting(HotTopicFact::value).containsExactly("lying");
    }

    @Test
    @DisplayName("3.0 mm of modelled snow water is lying (boundary); just under, and a null, are not")
    void detect_lyingBoundary() {
        LocationEntity a = place(1, "A", LAKES);

        assertThat(detect(List.of(a), List.of(lying(a, FROM, 3.0, 0.0)), List.of())).hasSize(1);
        assertThat(detect(List.of(a), List.of(lying(a, FROM, 2.99, 0.0)), List.of())).isEmpty();
        assertThat(detect(List.of(a), List.of(row(a, FROM, TargetType.SUNRISE, null, 5.0)), List.of())).isEmpty();
    }

    @Test
    @DisplayName("snow lying counts at any height: a high place with a null elevation still fires")
    void detect_anyHeight() {
        LocationEntity summitViewpoint = place(1, "Haystacks", LAKES);
        summitViewpoint.setElevationMetres(580);
        LocationEntity noElevation = place(2, "Mystery", LAKES);

        List<HotTopic> topics = detect(List.of(summitViewpoint, noElevation),
                List.of(lying(summitViewpoint, FROM, 20.0, 0.0), lying(noElevation, FROM, 20.0, 0.0)), List.of());

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).locationNames()).containsExactly("Haystacks", "Mystery");
    }

    @Test
    @DisplayName("a place with no row (missing or stale) gets no topic: unknown is never snow")
    void detect_noRow_noTopic() {
        LocationEntity unknown = place(1, "Rydal Water", LAKES);
        LocationEntity snowy = place(2, "Mallerstang", LAKES);

        List<HotTopic> topics = detect(List.of(unknown, snowy),
                List.of(lying(snowy, FROM, 8.0, 0.0)), List.of());

        assertThat(topics).singleElement().satisfies(
                t -> assertThat(t.locationNames()).containsExactly("Mallerstang"));
    }

    @Test
    @DisplayName("a sunset-only row, a summit row, a disabled place and an out-of-window date ring nothing")
    void detect_rowsThatDoNotCount() {
        LocationEntity enabled = place(1, "Rydal Water", LAKES);
        LocationEntity disabled = place(2, "Closed", LAKES);
        LocationEntity noId = place(3, "No id", LAKES);
        noId.setId(null);
        SnowStateEntity summit = SnowStateEntity.builder().pointKey("summit:helvellyn").evaluationDate(FROM)
                .eventType(TargetType.SUNRISE).snowWaterMm(40.0).snowfall24hCm(0.0).computedAt(FRESH_AT).build();

        List<HotTopic> topics = detect(List.of(enabled, noId),
                List.of(row(enabled, FROM, TargetType.SUNSET, 8.0, 0.0), summit,
                        lying(disabled, FROM, 8.0, 0.0), lying(enabled, TO.plusDays(1), 8.0, 0.0),
                        lying(enabled, FROM.minusDays(1), 8.0, 0.0)),
                List.of());

        assertThat(topics).isEmpty();
    }

    // ── SNOW_MIST ──

    @Test
    @DisplayName("a lying place whose sunrise valley mist band is LIKELY is SNOW_MIST at priority 1")
    void detect_misty_firesSnowMist() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        List<HotTopic> topics = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                List.of(likely(place, FROM, 2.0)));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.label()).isEqualTo("Fresh snow with mist");
        assertThat(topic.detail()).isEqualTo("Snow lying with mist — exceptional conditions");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.locationNames()).containsExactly("Rydal Water");
        assertThat(topic.note()).isEqualTo("mist over lying snow — soft, flat light");
        assertThat(factWithKey(topic, "mist").value()).isEqualTo("mist likely");
        assertThat(topic.facts().get(0).value()).isEqualTo("lying");
    }

    @Test
    @DisplayName("misty snow that fell overnight says 'Fresh snow overnight with mist'")
    void detect_mistyFreshSnow_detail() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        HotTopic topic = detect(List.of(place), List.of(lying(place, FROM, 8.0, 6.0)),
                List.of(likely(place, FROM, 2.0))).get(0);

        assertThat(topic.detail()).isEqualTo("Fresh snow overnight with mist — exceptional conditions");
        assertThat(topic.facts().get(0).value()).isEqualTo("fresh ~6 cm");
    }

    @ParameterizedTest(name = "band {0} leaves SNOW_FRESH")
    @NullSource
    @EnumSource(value = MistPotential.class, names = "LIKELY", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("any band short of LIKELY (or no band) leaves SNOW_FRESH only")
    void detect_otherBands_freshOnly(MistPotential band) {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        List<HotTopic> topics = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                List.of(slot(place, FROM, TargetType.SUNRISE, band, null)));

        assertThat(topics).extracting(HotTopic::type).containsExactly("SNOW_FRESH");
    }

    @Test
    @DisplayName("a place with no slot row is not misty: SNOW_FRESH. A LIKELY sunset slot does not make it misty")
    void detect_noSlotRowOrSunsetSlot_isFresh() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        assertThat(detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)), List.of()))
                .extracting(HotTopic::type).containsExactly("SNOW_FRESH");
        assertThat(detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                List.of(slot(place, FROM, TargetType.SUNSET, MistPotential.LIKELY, null))))
                .extracting(HotTopic::type).containsExactly("SNOW_FRESH");
    }

    @Test
    @DisplayName("a LIKELY slot for a place with no snow adds nothing; a slot row without an id is skipped")
    void detect_mistWithoutSnow_nothing() {
        LocationEntity snowy = place(1, "Rydal Water", LAKES);
        LocationEntity dry = place(2, "Dry", LAKES);
        LocationEntity anonymous = place(3, "Anon", LAKES);
        anonymous.setId(null);

        List<HotTopic> topics = detect(List.of(snowy, dry), List.of(lying(snowy, FROM, 8.0, 0.0)),
                List.of(likely(dry, FROM, 2.0), likely(anonymous, FROM, 2.0)));

        assertThat(topics).extracting(HotTopic::type).containsExactly("SNOW_FRESH");
    }

    @Test
    @DisplayName("misty and plain places on one day give two topics, SNOW_MIST first, each ringing its own places")
    void detect_mistyAndPlainSameDay_twoTopicsMistFirst() {
        LocationEntity misty = place(1, "Misty Tarn", LAKES);
        LocationEntity plain = place(2, "Plain Fell", LAKES);

        List<HotTopic> topics = detect(List.of(misty, plain),
                List.of(lying(misty, FROM, 8.0, 0.0), lying(plain, FROM, 8.0, 0.0)),
                List.of(likely(misty, FROM, 2.0)));

        assertThat(topics).extracting(HotTopic::type, HotTopic::locationNames)
                .containsExactly(tuple("SNOW_MIST", List.of("Misty Tarn")),
                        tuple("SNOW_FRESH", List.of("Plain Fell")));
    }

    @Test
    @DisplayName("the same place misty on day 0 and plain on day 1 gives one topic per day, each its own kind")
    void detect_mistyThenPlain_oneTopicPerDay() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        List<HotTopic> topics = detect(List.of(place),
                List.of(lying(place, FROM, 8.0, 0.0), lying(place, FROM.plusDays(1), 8.0, 0.0)),
                List.of(likely(place, FROM, 2.0)));

        assertThat(topics).extracting(HotTopic::type, HotTopic::date)
                .containsExactly(tuple("SNOW_MIST", FROM), tuple("SNOW_FRESH", FROM.plusDays(1)));
    }

    // ── frost wording (unchanged) ──

    @Test
    @DisplayName("sub-zero misty snow becomes 'Snow, mist & frost' with the freezing-air fact")
    void detect_subZeroMist_frost() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        HotTopic topic = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                List.of(likely(place, FROM, -3.4))).get(0);

        assertThat(topic.type()).isEqualTo("SNOW_MIST");
        assertThat(topic.label()).isEqualTo("Snow, mist & frost");
        assertThat(topic.detail()).isEqualTo("Freezing mist and frost over lying snow");
        assertThat(topic.description()).contains("hoar frost");
        assertThat(topic.note()).isEqualTo("densest before dawn — frost on every branch");
        assertThat(factWithKey(topic, "air").value()).isEqualTo("-3 °C · frost likely");
    }

    @Test
    @DisplayName("air exactly 0 °C, or unknown, is not sub-zero: stays plain 'Fresh snow with mist'")
    void detect_zeroOrUnknownAir_notFrost() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        for (Double temperature : new Double[] {0.0, 1.5, null}) {
            HotTopic topic = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                    List.of(likely(place, FROM, temperature))).get(0);

            assertThat(topic.label()).isEqualTo("Fresh snow with mist");
            assertThat(topic.facts()).extracting(HotTopicFact::key).doesNotContain("air");
        }
    }

    @Test
    @DisplayName("a barely sub-zero reading shows '-1 °C', never a self-contradictory '0 °C · frost'")
    void detect_barelySubZero_showsMinusOne() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        HotTopic topic = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)),
                List.of(likely(place, FROM, -0.2))).get(0);

        assertThat(factWithKey(topic, "air").value()).isEqualTo("-1 °C · frost likely");
    }

    @Test
    @DisplayName("frost is read from the day's snowiest misty place, per day")
    void detect_frostFromSnowiestPlacePerDay() {
        LocationEntity cold = place(1, "Cold Tarn", LAKES);
        LocationEntity mild = place(2, "Mild Beck", LAKES);

        List<HotTopic> topics = detect(List.of(cold, mild),
                List.of(lying(cold, FROM, 30.0, 0.0), lying(mild, FROM, 5.0, 0.0),
                        lying(cold, FROM.plusDays(1), 5.0, 0.0), lying(mild, FROM.plusDays(1), 30.0, 0.0)),
                List.of(likely(cold, FROM, -4.0), likely(mild, FROM, 3.0),
                        likely(cold, FROM.plusDays(1), -4.0), likely(mild, FROM.plusDays(1), 3.0)));

        assertThat(topics).extracting(HotTopic::date, HotTopic::label)
                .containsExactly(tuple(FROM, "Snow, mist & frost"),
                        tuple(FROM.plusDays(1), "Fresh snow with mist"));
    }

    // ── per day ──

    @Test
    @DisplayName("a multi-day spell is one topic per day, each with its own regions and places")
    void detect_multipleDays_oneTopicEach() {
        LocationEntity lakes = place(1, "Rydal Water", LAKES);
        LocationEntity moors = place(2, "Rosedale", MOORS);

        List<HotTopic> topics = detect(List.of(lakes, moors),
                List.of(lying(moors, FROM.plusDays(1), 8.0, 0.0), lying(lakes, FROM, 8.0, 0.0)), List.of());

        assertThat(topics).extracting(HotTopic::date).containsExactly(FROM, FROM.plusDays(1));
        assertThat(topics.get(0).regions()).containsExactly(LAKES);
        assertThat(topics.get(0).locationNames()).containsExactly("Rydal Water");
        assertThat(topics.get(1).regions()).containsExactly(MOORS);
        assertThat(topics.get(1).locationNames()).containsExactly("Rosedale");
        assertThat(topics).extracting(HotTopic::detail).containsOnly("Snow lying");
    }

    @Test
    @DisplayName("today's topic survives dawn: no solar-event cut-off is consulted at all")
    void detect_todayAfterDawn_stillEmitted() {
        LocationEntity place = place(1, "Rydal Water", LAKES);

        List<HotTopic> topics = detect(List.of(place), List.of(lying(place, FROM, 8.0, 0.0)), List.of());

        assertThat(topics).extracting(HotTopic::type, HotTopic::date).containsExactly(tuple("SNOW_FRESH", FROM));
    }

    @Test
    @DisplayName("a place with no region or no name still rings (the region and name are simply absent)")
    void detect_placeWithoutRegionOrName() {
        LocationEntity noRegion = place(1, "Nowhere", LAKES);
        noRegion.setRegion(null);
        LocationEntity noName = place(2, "x", LAKES);
        noName.setName(null);
        LocationEntity unnamedRegion = place(3, "Y", null);

        List<HotTopic> topics = detect(List.of(noRegion, noName, unnamedRegion),
                List.of(lying(noRegion, FROM, 8.0, 0.0), lying(noName, FROM, 8.0, 0.0),
                        lying(unnamedRegion, FROM, 8.0, 0.0)), List.of());

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly(LAKES);
        assertThat(topics.get(0).locationNames()).containsExactly("Nowhere", "Y");
    }

    // ── the two-argument detect reads for itself ──

    @Test
    @DisplayName("the two-argument detect reads the roster, rows and slots itself, and drops a stale row")
    void detect_twoArgument_readsItselfAndDropsStaleRow() {
        LocationEntity fresh = place(1, "Rydal Water", LAKES);
        LocationEntity stale = place(2, "Stale Tarn", LAKES);
        SnowStateEntity staleRow = lying(stale, FROM, 8.0, 0.0);
        staleRow.setComputedAt(LocalDateTime.of(2024, 1, 13, 4, 0));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(fresh, stale));
        when(snowStateRepository.findByEvaluationDateBetween(FROM, TO))
                .thenReturn(List.of(lying(fresh, FROM, 8.0, 0.0), staleRow));
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(likely(fresh, FROM, 2.0)));

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).extracting(HotTopic::type, HotTopic::locationNames)
                .containsExactly(tuple("SNOW_MIST", List.of("Rydal Water")));
    }

    @Test
    @DisplayName("with no snow lying anywhere the slot signals are never read")
    void detect_noSnow_slotSignalsUntouched() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());
        when(snowStateRepository.findByEvaluationDateBetween(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
        verifyNoInteractions(slotSignalReader);
    }

    // ── isMisty: a SUNRISE row whose valley mist band is LIKELY ──

    @Test
    @DisplayName("a LIKELY sunrise is misty")
    void isMisty_likelySunrise_true() {
        assertThat(SnowFreshHotTopicStrategy.isMisty(likely(place(1, "A", LAKES), FROM, null))).isTrue();
    }

    @Test
    @DisplayName("a LIKELY sunset is not misty")
    void isMisty_likelySunset_false() {
        assertThat(SnowFreshHotTopicStrategy.isMisty(
                slot(place(1, "A", LAKES), FROM, TargetType.SUNSET, MistPotential.LIKELY, null))).isFalse();
    }

    @ParameterizedTest(name = "band {0} is not misty")
    @NullSource
    @EnumSource(value = MistPotential.class, names = "LIKELY", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("POSSIBLE, NONE, FOGBOUND and an absent band are not misty")
    void isMisty_otherBands_false(MistPotential band) {
        assertThat(SnowFreshHotTopicStrategy.isMisty(
                slot(place(1, "A", LAKES), FROM, TargetType.SUNRISE, band, null))).isFalse();
    }
}
