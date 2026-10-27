package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.config.SubRegionProperties;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sentinel tests for {@link LitePickShaper}: a briefing whose every unpicked-location rating and prose field
 * carries a unique marker is projected exactly as it is served (through {@code PlanWindowProjector}, which copies
 * hot-topic text into window badges), shaped for a reader, serialised with the API's Jackson 3 mapper, and
 * searched for every marker. A field that is copied somewhere the shaper does not know about fails here, which a
 * per-field classification of the top-level components cannot see (the bluebell window badge was missed that way).
 */
class LitePickSentinelTest {

    private static final LocalDate DAY = LocalDate.of(2999, 3, 25);
    private static final LocalDateTime EVENT = LocalDateTime.of(2999, 3, 25, 6, 0);
    private static final long PICKED_ID = 7L;
    private static final long UNPICKED_ID = 8L;

    private static final List<String> UNPICKED_MARKERS = List.of("U_STANDDOWN", "U_FLAG", "U_SUMMARY",
            "U_HEADLINE", "U_BEST_SUMMARY", "U_FACT", "U_GLANCE", "U_LOC_SUMMARY", "U_QUALITY", "U_UNREG",
            "3.14159", "0.27182");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LitePickShaper shaper = new LitePickShaper(Mockito.mock(UserSubRegionService.class),
            new SubRegionProperties());

    /** The star an unpicked place carries: distinct from every picked star so any leak is visible. */
    private static final int UNPICKED_STAR = 5;
    private static final int PICKED_STAR = 2;

    private static BriefingSlot slot(long id, String name, String tag) {
        int star = id == PICKED_ID ? PICKED_STAR : UNPICKED_STAR;
        return new BriefingSlot(id, name, EVENT, Verdict.GO, null, null, List.of(tag + "_FLAG"),
                tag + "_STANDDOWN", star, 3, 70, 60, tag + "_SUMMARY", DisplayVerdict.WORTH_IT, tag + "_HEADLINE",
                false, null, null);
    }

    private static HotTopic bluebell() {
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new ExpandedHotTopicDetail.RegionGroup("North East", null, null, null, List.of(
                        new ExpandedHotTopicDetail.LocationEntry("Bamburgh Castle", "Woodland", "Best",
                                new ExpandedHotTopicDetail.BluebellLocationMetrics(5, "WOODLAND", "P_LOC_SUMMARY"),
                                null),
                        new ExpandedHotTopicDetail.LocationEntry("Craster", "Open fell", null,
                                new ExpandedHotTopicDetail.BluebellLocationMetrics(4, "OPEN_FELL",
                                        "U_LOC_SUMMARY"), null)))),
                new ExpandedHotTopicDetail.BluebellMetrics(5, "U_QUALITY", 2), null);
        return new HotTopic("BLUEBELL", "Bluebell conditions", "U_BEST_SUMMARY today", DAY, 1, "BLUEBELL",
                List.of("North East"), "description", detail, "SUNRISE", "06:00", null, null, null,
                List.of(HotTopicFact.metric("conditions", "U_FACT 5/5 · excellent")), "note", null, null, null,
                null, new GlanceLine("U_GLANCE", "U_GLANCE", null, false));
    }

    private static DailyBriefingResponse briefing() {
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "Region prose", List.of(),
                List.of(slot(PICKED_ID, "Bamburgh Castle", "P"), slot(UNPICKED_ID, "Craster", "U")), null, null,
                null, null, null, null, DisplayVerdict.WORTH_IT, 2, "Worth it", false, Confidence.HIGH, 3.14159, 5,
                0.27182, true, null);
        BriefingEventSummary sunrise = new BriefingEventSummary(TargetType.SUNRISE, List.of(region),
                List.of(slot(UNPICKED_ID, "Unregioned spot", "U_UNREG")), EVENT, null);
        return new DailyBriefingResponse(LocalDateTime.of(2999, 3, 24, 9, 0), "headline",
                List.of(new BriefingDay(DAY, List.of(sunrise))), List.of(), null, null, false, false, 0, "Opus",
                List.of(bluebell()), List.of());
    }

    /** The briefing as the controller builds it: projected (badges), then role-shaped, then pick-shaped. */
    private String served(PickScope scope) {
        DailyBriefingResponse projected = com.gregochr.goldenhour.service.PlanWindowProjector.apply(briefing(),
                EVENT.minusDays(1), Map.of(), Map.of());
        DailyBriefingResponse lite = new BriefingRoleShaper().forLite(projected);
        return mapper.writeValueAsString(shaper.shapeBriefing(lite, scope));
    }

    @Test
    @DisplayName("Control: before shaping, every marker is on the wire, including in the window badge")
    void controlEveryMarkerIsPresentBeforeShaping() {
        String json = served(PickScope.unrestricted());

        for (String marker : UNPICKED_MARKERS) {
            if (!marker.equals("U_SUMMARY") && !marker.equals("U_HEADLINE")) {
                assertThat(json).as("control: " + marker).contains(marker);
            }
        }
        assertThat(json).as("the badge route exists").contains("\"badges\":[{\"type\":\"BLUEBELL\"");
        assertThat(json.split("U_FACT", -1).length - 1).as("topic and badge both carry the chip")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("A reader with no picks: no unpicked marker appears anywhere in the serialised briefing")
    void noMarkerSurvivesForAReaderWithNoPicks() {
        String json = served(PickScope.nothing());

        for (String marker : UNPICKED_MARKERS) {
            assertThat(json).as("leaked: " + marker).doesNotContain(marker);
        }
        assertThat(json).as("the shaped body is still a briefing").contains("Region prose")
                .contains("\"allowedLocationIds\":[]");
    }

    @Test
    @DisplayName("A reader holding Bamburgh: the unpicked markers are gone, the picked ones survive")
    void onlyThePickedMarkersSurviveForAReaderWithAPick() {
        String json = served(PickScope.of(List.of(
                new SubRegionLocationRow(100L, PICKED_ID, "Bamburgh Castle", 55.6, -1.7))));

        for (String marker : UNPICKED_MARKERS) {
            assertThat(json).as("leaked: " + marker).doesNotContain(marker);
        }
        assertThat(json).contains("P_STANDDOWN").contains("P_FLAG").contains("P_LOC_SUMMARY");
    }

    // ------------------------------------------------------------------ picksBestRating and shapedLeadRegion

    private JsonNode regionNode(String json) {
        return mapper.readTree(json).get("days").get(0).get("eventSummaries").get(0).get("regions").get(0);
    }

    private static boolean containsNumber(JsonNode node, int value) {
        if (node.isNumber() && node.asInt() == value && !node.isFloatingPointNumber()) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsNumber(child, value)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("A shaped body serves the picked place's star as picksBestRating (2) and the unpicked 5 is nowhere")
    void shapedRegionServesOnlyThePickedBest() {
        String json = served(PickScope.of(List.of(
                new SubRegionLocationRow(100L, PICKED_ID, "Bamburgh Castle", 55.6, -1.7))));

        assertThat(json).contains("\"picksBestRating\":2");
        assertThat(containsNumber(regionNode(json), UNPICKED_STAR)).as("no unpicked star under the region")
                .isFalse();
        assertThat(json).contains("\"shapedLeadRegion\":\"North East\"");
    }

    @Test
    @DisplayName("A reader with no picks: no picksBestRating key, no 5 under the region, the lead region is kept")
    void noPicksServesNoPicksBest() {
        String json = served(PickScope.nothing());

        assertThat(json).doesNotContain("picksBestRating");
        assertThat(containsNumber(regionNode(json), UNPICKED_STAR)).isFalse();
        assertThat(json).contains("\"shapedLeadRegion\":\"North East\"");
    }

    @Test
    @DisplayName("An unshaped body (PRO, or enforcement off) carries neither picksBestRating nor shapedLeadRegion")
    void unshapedBodyCarriesNeither() {
        String json = served(PickScope.unrestricted());

        assertThat(json).doesNotContain("picksBestRating").doesNotContain("shapedLeadRegion");
    }

    // ------------------------------------------------------------------ every reachable record

    /**
     * Every record type reachable from {@link DailyBriefingResponse}, with its components, as reviewed for
     * per-location ratings. A new type or component anywhere in the tree fails
     * {@link #everyReachableRecordTypeIsReviewed()} until someone decides whether the shaper must handle it.
     */
    private static final Map<String, Set<String>> REVIEWED = ReviewedTypes.table();

    private static void walk(Class<?> type, Set<Class<?>> seen) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        for (RecordComponent c : type.getRecordComponents()) {
            walk(c.getType(), seen);
            Type generic = c.getGenericType();
            if (generic instanceof ParameterizedType p) {
                for (Type arg : p.getActualTypeArguments()) {
                    if (arg instanceof Class<?> argClass) {
                        walk(argClass, seen);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Every record type reachable from DailyBriefingResponse, and every component of each, is reviewed")
    void everyReachableRecordTypeIsReviewed() {
        Set<Class<?>> reachable = new LinkedHashSet<>();
        walk(DailyBriefingResponse.class, reachable);

        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (Class<?> type : reachable) {
            actual.put(type.getName().substring(type.getName().lastIndexOf('.') + 1),
                    new TreeSet<>(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                            .toList()));
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : actual.entrySet()) {
            Set<String> reviewed = REVIEWED.get(e.getKey());
            if (reviewed == null) {
                problems.add("unreviewed type " + e.getKey() + " " + e.getValue());
            } else if (!reviewed.equals(e.getValue())) {
                problems.add("components of " + e.getKey() + " changed: " + e.getValue() + " vs reviewed "
                        + reviewed);
            }
        }
        for (String name : REVIEWED.keySet()) {
            if (!actual.containsKey(name)) {
                problems.add("reviewed type no longer reachable: " + name);
            }
        }
        assertThat(problems).as("review each new type or component for per-location ratings and prose "
                + "(strip it in LitePickShaper and add a sentinel), then update ReviewedTypes").isEmpty();
    }
}
