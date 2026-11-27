package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SnowStateRepository;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.SnowSummits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnowTopsHotTopicStrategy}: a white summit within 12 km of a place with a clear sunrise
 * (snow-topics-truth-plan §3.3 rule 2), the three variants from the place's own row, the majority variant per day,
 * and its independence from SNOW_FRESH.
 */
@ExtendWith(MockitoExtension.class)
class SnowTopsHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-15T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime FRESH_AT = LocalDateTime.of(2024, 1, 15, 4, 0);

    private static final String LAKES = "The Lake District";
    private static final String MOORS = "The North York Moors";

    private static final double HELVELLYN_LAT = 54.5269;
    private static final double HELVELLYN_LON = -3.0182;
    /** Kilometres of latitude per degree on the sphere GeoUtils measures on. */
    private static final double KM_PER_DEGREE = 111.195;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private SnowStateRepository snowStateRepository;

    private SnowTopsHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SnowTopsHotTopicStrategy(locationRepository, new SnowStateReader(snowStateRepository, CLOCK));
    }

    private static LocationEntity place(long id, String name, String regionName, double lat, double lon) {
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        location.setRegion(region);
        location.setLat(lat);
        location.setLon(lon);
        return location;
    }

    /** A place about 7 km from Helvellyn (Grasmere side). */
    private static LocationEntity nearHelvellyn(long id, String name) {
        return place(id, name, LAKES, 54.46, -3.02);
    }

    /** A place about 6 km from Skiddaw, and about 11 km from Helvellyn (Keswick side). */
    private static LocationEntity nearSkiddaw(long id, String name) {
        return place(id, name, LAKES, 54.60, -3.13);
    }

    private static SnowStateEntity own(LocationEntity place, LocalDate date, Double waterMm, Integer lowCloud) {
        return SnowStateEntity.builder().pointKey(SnowStateEntity.locationKey(place.getId()))
                .evaluationDate(date).eventType(TargetType.SUNRISE).snowWaterMm(waterMm).snowfall24hCm(0.0)
                .lowCloudPct(lowCloud).computedAt(FRESH_AT).build();
    }

    private static SnowStateEntity summit(String key, LocalDate date, Double waterMm) {
        return SnowStateEntity.builder().pointKey("summit:" + key).evaluationDate(date)
                .eventType(TargetType.SUNRISE).snowWaterMm(waterMm).snowfall24hCm(0.0).lowCloudPct(null)
                .computedAt(FRESH_AT).build();
    }

    private List<HotTopic> detect(List<LocationEntity> roster, List<SnowStateEntity> rows) {
        return strategy.detect(FROM, TO, HotTopicInputs.ofSnow(null, () -> roster, () -> rows));
    }

    // ── the rule ──

    @Test
    @DisplayName("a green-below place within 12 km of a white summit and a clear sunrise fires SNOW_TOPS")
    void detect_greenBelow_fires() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        List<HotTopic> topics = detect(List.of(grasmere),
                List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 0.5, 30)));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SNOW_TOPS");
        assertThat(topic.label()).isEqualTo("Snow on the fells");
        assertThat(topic.detail()).isEqualTo("Tops white above green valleys");
        assertThat(topic.description()).contains("valleys stay green");
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.regions()).containsExactly(LAKES);
        assertThat(topic.locationNames()).containsExactly("Grasmere");
        assertThat(topic.facts()).extracting(f -> f.value()).containsExactly("Helvellyn white");
        assertThat(topic.note()).isEqualTo("shoot from low ground looking up, before cloud builds");
    }

    @Test
    @DisplayName("an all-white place is 'white throughout' and also carries SNOW_FRESH: two photographs")
    void detect_allWhite_carriesBothTopics() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        List<SnowStateEntity> rows = List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 8.0, 30));
        HotTopicInputs inputs = HotTopicInputs.ofSnow(() -> List.of(), () -> List.of(grasmere), () -> rows);

        List<HotTopic> tops = strategy.detect(FROM, TO, inputs);
        List<HotTopic> fresh = new SnowFreshHotTopicStrategy(null, locationRepository,
                new SnowStateReader(snowStateRepository, CLOCK)).detect(FROM, TO, inputs);

        assertThat(tops).extracting(HotTopic::type, HotTopic::detail)
                .containsExactly(tuple("SNOW_TOPS", "White from valley floor to summit"));
        assertThat(tops.get(0).description()).contains("Snow from the valley floor to the summits");
        assertThat(fresh).extracting(HotTopic::type).containsExactly("SNOW_FRESH");
    }

    @Test
    @DisplayName("a place whose own snow water is null is 'unknown below': tops white, never green")
    void detect_ownSnowWaterNull_unknownBelow() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        HotTopic topic = detect(List.of(grasmere),
                List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, null, 30))).get(0);

        assertThat(topic.detail()).isEqualTo("Tops white");
        assertThat(topic.description()).isEqualTo("Fell tops are capped with snow.");
    }

    @Test
    @DisplayName("the lying boundary picks the variant: 3.0 mm is white throughout, just under is green below")
    void detect_variantBoundary() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 3.0, 30)))
                .get(0).detail()).isEqualTo("White from valley floor to summit");
        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 2.99, 30)))
                .get(0).detail()).isEqualTo("Tops white above green valleys");
    }

    @Test
    @DisplayName("a place with no row of its own gets no SNOW_TOPS: the cloud gate has nothing to read")
    void detect_missingOwnRow_noTopic() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 40.0)))).isEmpty();
    }

    @Test
    @DisplayName("a stale own row is dropped by the reader: no SNOW_TOPS, and never 'green below'")
    void detect_staleOwnRow_noTopic() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        SnowStateEntity stale = own(grasmere, FROM, 0.0, 30);
        stale.setComputedAt(LocalDateTime.of(2024, 1, 13, 4, 0));
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(grasmere));
        when(snowStateRepository.findByEvaluationDateBetween(FROM, TO))
                .thenReturn(List.of(summit("helvellyn", FROM, 40.0), stale));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("the two-argument detect reads the roster and rows itself")
    void detect_twoArgument_readsItself() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(grasmere));
        when(snowStateRepository.findByEvaluationDateBetween(FROM, TO))
                .thenReturn(List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 0.0, 30)));

        assertThat(strategy.detect(FROM, TO)).extracting(HotTopic::type).containsExactly("SNOW_TOPS");
    }

    // ── gates ──

    @Test
    @DisplayName("low cloud above 60 % hides the tops; exactly 60 % does not; missing cloud fails the gate")
    void detect_lowCloudGate() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        SnowStateEntity helvellyn = summit("helvellyn", FROM, 40.0);

        assertThat(detect(List.of(grasmere), List.of(helvellyn, own(grasmere, FROM, 0.0, 60)))).hasSize(1);
        assertThat(detect(List.of(grasmere), List.of(helvellyn, own(grasmere, FROM, 0.0, 61)))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of(helvellyn, own(grasmere, FROM, 0.0, null)))).isEmpty();
    }

    @Test
    @DisplayName("a summit is white from 5.0 mm (boundary); just under, or null, is not")
    void detect_summitWhiteBoundary() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        SnowStateEntity ownRow = own(grasmere, FROM, 0.0, 30);

        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 5.0), ownRow))).hasSize(1);
        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 4.99), ownRow))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, null), ownRow))).isEmpty();
    }

    @Test
    @DisplayName("12 km boundary: a place just inside rings, a place just outside does not")
    void detect_viewRadiusBoundary() {
        double insideKm = 11.99;
        double outsideKm = 12.01;
        LocationEntity inside = place(1, "Inside", LAKES, HELVELLYN_LAT - insideKm / KM_PER_DEGREE, HELVELLYN_LON);
        LocationEntity outside = place(2, "Outside", LAKES, HELVELLYN_LAT - outsideKm / KM_PER_DEGREE,
                HELVELLYN_LON);
        assertThat(GeoUtils.distanceKm(inside.getLat(), inside.getLon(), HELVELLYN_LAT, HELVELLYN_LON))
                .isLessThan(SnowSummits.VIEW_RADIUS_KM);
        assertThat(GeoUtils.distanceKm(outside.getLat(), outside.getLon(), HELVELLYN_LAT, HELVELLYN_LON))
                .isGreaterThan(SnowSummits.VIEW_RADIUS_KM);

        List<HotTopic> topics = detect(List.of(inside, outside),
                List.of(summit("helvellyn", FROM, 40.0), own(inside, FROM, 0.0, 30), own(outside, FROM, 0.0, 30)));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).locationNames()).containsExactly("Inside");
    }

    @Test
    @DisplayName("only white summits count: with the nearer summit bare, the next white one in range is the view")
    void detect_nearestWhiteSummitOnly() {
        LocationEntity keswick = nearSkiddaw(1, "Keswick");

        // Skiddaw (nearest) is bare; Helvellyn (about 11 km) is white.
        HotTopic topic = detect(List.of(keswick),
                List.of(summit("skiddaw", FROM, 1.0), summit("helvellyn", FROM, 40.0),
                        own(keswick, FROM, 0.0, 30))).get(0);

        assertThat(topic.facts()).extracting(f -> f.value()).containsExactly("Helvellyn white");
    }

    @Test
    @DisplayName("no white summit anywhere gives nothing, whatever the places are doing")
    void detect_noWhiteSummit_nothing() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        assertThat(detect(List.of(grasmere), List.of(own(grasmere, FROM, 20.0, 30)))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a place's elevation is never read: a null elevation and a valley floor both ring")
    void detect_elevationIrrelevant() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        grasmere.setElevationMetres(null);

        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 0.0, 30))))
                .hasSize(1);
    }

    @Test
    @DisplayName("a roster place without an id is skipped and a located one still fires")
    void detect_placeWithoutId_skipped() {
        LocationEntity orphan = nearHelvellyn(1, "Orphan");
        orphan.setId(null);
        LocationEntity grasmere = nearHelvellyn(2, "Grasmere");

        List<HotTopic> topics = detect(List.of(orphan, grasmere),
                List.of(summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 0.0, 30)));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).locationNames()).containsExactly("Grasmere");
    }

    @Test
    @DisplayName("sunset rows, other points and dates outside the window are ignored")
    void detect_rowsThatDoNotCount() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");
        SnowStateEntity sunsetSummit = summit("helvellyn", FROM, 40.0);
        sunsetSummit.setEventType(TargetType.SUNSET);
        SnowStateEntity sunsetOwn = own(grasmere, FROM, 0.0, 30);
        sunsetOwn.setEventType(TargetType.SUNSET);

        assertThat(detect(List.of(grasmere), List.of(sunsetSummit, own(grasmere, FROM, 0.0, 30)))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", FROM, 40.0), sunsetOwn))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of(summit("not-a-listed-summit", FROM, 40.0),
                own(grasmere, FROM, 0.0, 30)))).isEmpty();
        assertThat(detect(List.of(grasmere), List.of(summit("helvellyn", TO.plusDays(1), 40.0),
                own(grasmere, TO.plusDays(1), 0.0, 30), summit("helvellyn", FROM.minusDays(1), 40.0),
                own(grasmere, FROM.minusDays(1), 0.0, 30)))).isEmpty();
    }

    // ── one topic per day, majority variant ──

    @Test
    @DisplayName("a spell is one topic per day, each ringing that day's places")
    void detect_perDay() {
        LocationEntity grasmere = nearHelvellyn(1, "Grasmere");

        List<HotTopic> topics = detect(List.of(grasmere),
                List.of(summit("helvellyn", FROM.plusDays(1), 40.0), own(grasmere, FROM.plusDays(1), 0.0, 30),
                        summit("helvellyn", FROM, 40.0), own(grasmere, FROM, 0.0, 30)));

        assertThat(topics).extracting(HotTopic::date).containsExactly(FROM, FROM.plusDays(1));
    }

    @Test
    @DisplayName("a mixed day takes the majority variant, and lists every ringed place")
    void detect_mixedDay_majorityVariant() {
        LocationEntity a = nearHelvellyn(1, "A");
        LocationEntity b = nearHelvellyn(2, "B");
        LocationEntity c = nearHelvellyn(3, "C");
        SnowStateEntity helvellyn = summit("helvellyn", FROM, 40.0);

        HotTopic greenMajority = detect(List.of(a, b, c),
                List.of(helvellyn, own(a, FROM, 0.0, 30), own(b, FROM, 0.0, 30), own(c, FROM, 9.0, 30))).get(0);
        HotTopic whiteMajority = detect(List.of(a, b, c),
                List.of(helvellyn, own(a, FROM, 9.0, 30), own(b, FROM, 9.0, 30), own(c, FROM, 0.0, 30))).get(0);

        assertThat(greenMajority.detail()).isEqualTo("Tops white above green valleys");
        assertThat(greenMajority.locationNames()).containsExactly("A", "B", "C");
        assertThat(whiteMajority.detail()).isEqualTo("White from valley floor to summit");
        assertThat(whiteMajority.locationNames()).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("a tie goes to green below, then white throughout, then unknown below")
    void detect_tieBreak() {
        LocationEntity a = nearHelvellyn(1, "A");
        LocationEntity b = nearHelvellyn(2, "B");
        SnowStateEntity helvellyn = summit("helvellyn", FROM, 40.0);

        String greenWhite = detect(List.of(a, b),
                List.of(helvellyn, own(a, FROM, 0.0, 30), own(b, FROM, 9.0, 30))).get(0).detail();
        String whiteUnknown = detect(List.of(a, b),
                List.of(helvellyn, own(a, FROM, 9.0, 30), own(b, FROM, null, 30))).get(0).detail();
        String greenUnknown = detect(List.of(a, b),
                List.of(helvellyn, own(a, FROM, 0.0, 30), own(b, FROM, null, 30))).get(0).detail();

        assertThat(greenWhite).isEqualTo("Tops white above green valleys");
        assertThat(whiteUnknown).isEqualTo("White from valley floor to summit");
        assertThat(greenUnknown).isEqualTo("Tops white above green valleys");
    }

    @Test
    @DisplayName("the one fact names the summit most ringed places are nearest; a tie takes the earlier summit")
    void detect_commonestSummit() {
        LocationEntity g1 = nearHelvellyn(1, "G1");
        LocationEntity g2 = nearHelvellyn(2, "G2");
        LocationEntity k1 = nearSkiddaw(3, "K1");
        List<SnowStateEntity> base = new ArrayList<>(List.of(
                summit("helvellyn", FROM, 40.0), summit("skiddaw", FROM, 40.0),
                own(g1, FROM, 0.0, 30), own(g2, FROM, 0.0, 30), own(k1, FROM, 0.0, 30)));

        assertThat(detect(List.of(g1, g2, k1), base).get(0).facts()).extracting(f -> f.value())
                .containsExactly("Helvellyn white");

        List<SnowStateEntity> tied = new ArrayList<>(List.of(
                summit("helvellyn", FROM, 40.0), summit("skiddaw", FROM, 40.0),
                own(g1, FROM, 0.0, 30), own(k1, FROM, 0.0, 30)));
        assertThat(detect(List.of(g1, k1), tied).get(0).facts()).extracting(f -> f.value())
                .containsExactly("Helvellyn white");

        List<SnowStateEntity> skiddawWins = new ArrayList<>(List.of(
                summit("helvellyn", FROM, 40.0), summit("skiddaw", FROM, 40.0),
                own(g1, FROM, 0.0, 30), own(k1, FROM, 0.0, 30), own(nearSkiddaw(4, "K2"), FROM, 0.0, 30)));
        assertThat(detect(List.of(g1, k1, nearSkiddaw(4, "K2")), skiddawWins).get(0).facts())
                .extracting(f -> f.value()).containsExactly("Skiddaw white");
    }

    @Test
    @DisplayName("regions are the ringed places' regions; a place with no region or name still rings")
    void detect_regionsFromRingedPlaces() {
        LocationEntity lakes = nearHelvellyn(1, "Grasmere");
        LocationEntity noRegion = nearHelvellyn(2, "Nowhere");
        noRegion.setRegion(null);
        LocationEntity unnamedRegion = nearHelvellyn(3, "Y");
        unnamedRegion.setRegion(new RegionEntity());
        LocationEntity noName = place(4, "x", MOORS, 54.46, -3.02);
        noName.setName(null);
        LocationEntity farAway = place(5, "Far", "The Peak District", 53.3, -1.8);

        List<HotTopic> topics = detect(List.of(lakes, noRegion, unnamedRegion, noName, farAway),
                List.of(summit("helvellyn", FROM, 40.0), own(lakes, FROM, 0.0, 30), own(noRegion, FROM, 0.0, 30),
                        own(unnamedRegion, FROM, 0.0, 30), own(noName, FROM, 0.0, 30),
                        own(farAway, FROM, 0.0, 30)));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly(LAKES, MOORS);
        assertThat(topics.get(0).locationNames()).containsExactly("Grasmere", "Nowhere", "Y");
    }
}
