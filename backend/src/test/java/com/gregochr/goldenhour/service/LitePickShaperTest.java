package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AstroConditionsDto;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDayPeak;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.Verdict;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit tests for {@link LitePickShaper}: who is shaped (fail closed), the strip of sub-regions-plan §4.2 for
 * each payload, and the completeness tests that stop a future rating field slipping through unstripped.
 */
class LitePickShaperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 27, 6, 0);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

    private static final long PICKED_ID = 7L;
    private static final long UNPICKED_ID = 8L;
    private static final long NO_SUB_REGION_ID = 9L;
    private static final String PICKED = "Bamburgh Castle";
    private static final String UNPICKED = "Craster";

    private final UserSubRegionService picks = mock(UserSubRegionService.class);
    private final SubRegionProperties properties = new SubRegionProperties();
    private LitePickShaper shaper;

    @BeforeEach
    void setUp() {
        shaper = new LitePickShaper(picks, properties);
    }

    // ------------------------------------------------------------------ helpers

    private static Authentication auth(String name, String... roles) {
        return new UsernamePasswordAuthenticationToken(name, "n/a",
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static List<SubRegionLocationRow> pickedRows() {
        return List.of(new SubRegionLocationRow(100L, PICKED_ID, PICKED, 55.6, -1.7));
    }

    private static PickScope scopeOfPicked() {
        return PickScope.of(pickedRows());
    }

    private static BriefingSlot.WeatherConditions weather() {
        return new BriefingSlot.WeatherConditions(10, BigDecimal.ONE, 20000, 50, 10.0, 9.0, 3,
                BigDecimal.TEN, 20, 30);
    }

    private static BriefingSlot ratedSlot(Long id, String name) {
        return new BriefingSlot(id, name, AT, Verdict.GO, weather(), BriefingSlot.TideInfo.NONE,
                List.of("Clear all layers"), "reason", 4, 3, 70, 60, "summary", DisplayVerdict.WORTH_IT,
                "headline", false, null, Boolean.TRUE);
    }

    private static BriefingRegion region(String name, List<BriefingSlot> slots) {
        return new BriefingRegion(name, Verdict.GO, "region prose", List.of("tide note"), slots, 11.0, 10.0,
                4.0, 3, "gloss headline", "gloss detail", DisplayVerdict.WORTH_IT, slots.size(),
                "Worth it", false, Confidence.HIGH, 3.5, 5, 0.4, true, null);
    }

    private static BriefingWindow window() {
        return new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 5, Confidence.HIGH, null, List.of(), null,
                null, null);
    }

    private static HotTopic bluebellTopic() {
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new RegionGroup("North East", null, null, null, List.of(
                        new LocationEntry(PICKED, "Woodland", "Best",
                                new BluebellLocationMetrics(5, "WOODLAND", "Carpet in bloom"), null))),
                new RegionGroup("Elsewhere", null, null, null, List.of(
                        new LocationEntry(UNPICKED, "Open fell", "Best",
                                new BluebellLocationMetrics(4, "OPEN_FELL", "Good light"), null)))),
                new BluebellMetrics(5, "Excellent", 2), null);
        return new HotTopic("BLUEBELL", "Bluebell conditions", "Carpet in bloom today", DAY, 1, "BLUEBELL",
                List.of("North East"), "description", detail)
                .withScience(List.of(HotTopicFact.metric("conditions", "5/5 · excellent"),
                        new HotTopicFact(null, "2 sites scoring 4+/5", null, false, true)), "note")
                .withGlance(new GlanceLine("5/5 · excellent", "5/5 · excellent", null, false));
    }

    private static HotTopic auroraTopic() {
        return new HotTopic("AURORA", "Aurora", "Kp 6", DAY, 1, "AURORA", List.of(), "desc", null)
                .withScience(List.of(HotTopicFact.metric("kp", "6")), "n");
    }

    private static DailyBriefingResponse briefing(List<BriefingRegion> regions, List<BriefingSlot> unregioned,
            BriefingWindow window, List<HotTopic> topics) {
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, regions, unregioned, AT,
                window);
        return new DailyBriefingResponse(AT, "headline", List.of(new BriefingDay(DAY, List.of(summary))),
                List.of(), null, null, false, false, 0, null, topics, List.of());
    }

    private static BriefingEventSummary eventOf(DailyBriefingResponse response) {
        return response.days().get(0).eventSummaries().get(0);
    }

    // ------------------------------------------------------------------ who is shaped

    @Test
    @DisplayName("Flag off: nobody is shaped (LITE, PRO, ADMIN, no role, no authentication) and no picks are read")
    void flagOff_isUnrestrictedForEveryone() {
        properties.setEnforce(false);

        assertThat(shaper.scopeFor(auth("anna", "LITE_USER")).shaped()).isFalse();
        assertThat(shaper.scopeFor(auth("pat", "PRO_USER")).shaped()).isFalse();
        assertThat(shaper.scopeFor(auth("ann", "ADMIN")).shaped()).isFalse();
        assertThat(shaper.scopeFor(auth("nobody")).shaped()).isFalse();
        assertThat(shaper.scopeFor(null).shaped()).isFalse();
        verifyNoInteractions(picks);
    }

    @Test
    @DisplayName("Flag on: a positively PRO_USER or ADMIN caller is unrestricted and no picks are read")
    void flagOn_proAndAdminAreUnrestricted() {
        properties.setEnforce(true);

        PickScope pro = shaper.scopeFor(auth("pat", "PRO_USER"));
        PickScope admin = shaper.scopeFor(auth("ann", "ADMIN"));

        assertThat(pro.shaped()).isFalse();
        assertThat(pro.allowsId(null)).isTrue();
        assertThat(admin.shaped()).isFalse();
        verifyNoInteractions(picks);
    }

    @Test
    @DisplayName("Flag on: a LITE reader is allowed exactly the locations of the sub-regions they picked")
    void flagOn_liteIsAllowedTheirPickedLocations() {
        properties.setEnforce(true);
        when(picks.pickedLocationRows("anna")).thenReturn(pickedRows());

        PickScope scope = shaper.scopeFor(auth("anna", "LITE_USER"));

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.allowsId(PICKED_ID)).isTrue();
        assertThat(scope.allowsName(PICKED)).isTrue();
        assertThat(scope.allowsId(UNPICKED_ID)).as("a location in an unpicked sub-region").isFalse();
        assertThat(scope.allowsId(NO_SUB_REGION_ID)).as("a location with no sub-region").isFalse();
        assertThat(scope.allowsId(null)).as("an unregioned or unknown id").isFalse();
        assertThat(scope.allowsName(null)).isFalse();
        assertThat(scope.sortedIds()).containsExactly(PICKED_ID);
    }

    @Test
    @DisplayName("Flag on: an unknown role is shaped by its picks, never treated as everything")
    void flagOn_unknownRoleIsShaped() {
        properties.setEnforce(true);
        when(picks.pickedLocationRows("sam")).thenReturn(List.of());

        PickScope scope = shaper.scopeFor(auth("sam", "USER"));

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.allowsId(PICKED_ID)).isFalse();
    }

    @Test
    @DisplayName("Flag on: no authentication, or an unauthenticated one, allows nothing and reads no picks")
    void flagOn_noAuthenticationAllowsNothing() {
        properties.setEnforce(true);

        assertThat(shaper.scopeFor(null)).isEqualTo(PickScope.nothing());
        assertThat(shaper.scopeFor(new UsernamePasswordAuthenticationToken("anna", "n/a"))).isEqualTo(
                PickScope.nothing());
        verifyNoInteractions(picks);
    }

    @Test
    @DisplayName("Flag on: zero picks allows nothing")
    void flagOn_zeroPicksAllowsNothing() {
        properties.setEnforce(true);
        when(picks.pickedLocationRows("anna")).thenReturn(List.of());

        PickScope scope = shaper.scopeFor(auth("anna", "LITE_USER"));

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.allowsId(PICKED_ID)).isFalse();
        assertThat(scope.sortedIds()).isEmpty();
    }

    @Test
    @DisplayName("Flag on: a failed picks read fails closed (allows nothing), never open")
    void flagOn_failedPicksReadFailsClosed() {
        properties.setEnforce(true);
        when(picks.pickedLocationRows("anna")).thenThrow(new IllegalStateException("db down"));

        PickScope scope = shaper.scopeFor(auth("anna", "LITE_USER"));

        assertThat(scope.shaped()).isTrue();
        assertThat(scope.allowsId(PICKED_ID)).isFalse();
        verify(picks).pickedLocationRows("anna");
    }

    // ------------------------------------------------------------------ the briefing

    @Test
    @DisplayName("An unrestricted scope returns the very same instance, whatever the payload")
    void unrestrictedScopeReturnsTheInput() {
        DailyBriefingResponse response = briefing(List.of(region("North East",
                List.of(ratedSlot(PICKED_ID, PICKED)))), List.of(), window(), List.of(bluebellTopic()));
        PickScope open = PickScope.unrestricted();

        assertThat(shaper.shapeBriefing(response, open)).isSameAs(response);
        assertThat(shaper.shapeBriefing(null, scopeOfPicked())).isNull();
        BriefingDigestResponse digest = new BriefingDigestResponse(AT, List.of());
        assertThat(shaper.shapeDigest(digest, open)).isSameAs(digest);
        List<LocationEvaluationView> views = List.of();
        assertThat(shaper.shapeScores(views, open)).isSameAs(views);
    }

    @Test
    @DisplayName("Briefing: picked slots keep their ratings; unpicked, no-sub-region and unregioned slots are gated")
    void briefing_gatesEverySlotOutsideThePicks() {
        DailyBriefingResponse response = briefing(
                List.of(region("North East", List.of(ratedSlot(PICKED_ID, PICKED),
                        ratedSlot(UNPICKED_ID, UNPICKED), ratedSlot(NO_SUB_REGION_ID, "Loose end"),
                        ratedSlot(null, "Old payload")))),
                List.of(ratedSlot(PICKED_ID, PICKED)), window(), List.of());

        BriefingEventSummary shaped = eventOf(shaper.shapeBriefing(response, scopeOfPicked()));

        List<BriefingSlot> slots = shaped.regions().get(0).slots();
        assertThat(slots.get(0).gated()).isNull();
        assertThat(slots.get(0).claudeRating()).isEqualTo(4);
        assertThat(slots.get(0).verdict()).isEqualTo(Verdict.GO);
        for (BriefingSlot gated : slots.subList(1, 4)) {
            assertGatedSlot(gated);
        }
        assertThat(slots.get(1).locationName()).as("the place is still named").isEqualTo(UNPICKED);
        // D8: an unregioned slot is never allowed, even one that names a picked id.
        assertGatedSlot(shaped.unregioned().get(0));
    }

    private static void assertGatedSlot(BriefingSlot slot) {
        assertThat(slot.gated()).isTrue();
        assertThat(slot.verdict()).isNull();
        assertThat(slot.claudeRating()).isNull();
        assertThat(slot.skyRating()).isNull();
        assertThat(slot.displayVerdict()).isNull();
        assertThat(slot.standdownReason()).isNull();
        assertThat(slot.flags()).isEmpty();
        assertThat(slot.neighbourOutlier()).isNull();
        assertThat(slot.claudeSummary()).isNull();
        assertThat(slot.claudeHeadline()).isNull();
        assertThat(slot.weather()).as("weather facts stay").isNotNull();
        assertThat(slot.solarEventTime()).isEqualTo(AT);
    }

    @Test
    @DisplayName("Briefing: region and window best and mean stars go for any shaped response; verdicts and prose stay")
    void briefing_stripsBestAndMeanStarsKeepsVerdictsAndProse() {
        DailyBriefingResponse response = briefing(
                List.of(region("North East", List.of(ratedSlot(PICKED_ID, PICKED)))), List.of(), window(),
                List.of());

        DailyBriefingResponse shaped = shaper.shapeBriefing(response, scopeOfPicked());

        BriefingRegion region = eventOf(shaped).regions().get(0);
        assertThat(region.bestRating()).isNull();
        assertThat(region.meanRating()).isNull();
        assertThat(region.meanRatingDelta()).isNull();
        assertThat(region.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(region.verdictLabel()).isEqualTo("Worth it");
        assertThat(region.summary()).isEqualTo("region prose");
        assertThat(region.glossHeadline()).isEqualTo("gloss headline");
        assertThat(region.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(region.scoredLocationCount()).isEqualTo(1);
        BriefingWindow window = eventOf(shaped).window();
        assertThat(window.bestRating()).isNull();
        assertThat(window.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(window.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(shaped.allowedLocationIds()).containsExactly(PICKED_ID);
        assertThat(shaped.headline()).isEqualTo("headline");
    }

    private static BriefingSlot ratedSlot(Long id, String name, int rating, boolean canopy) {
        return new BriefingSlot(id, name, AT, Verdict.GO, weather(), BriefingSlot.TideInfo.NONE,
                List.of("Clear all layers"), "reason", rating, 3, 70, 60, "summary", DisplayVerdict.WORTH_IT,
                "headline", canopy, null, null);
    }

    private static BriefingRegion shapedRegion(DailyBriefingResponse response) {
        return eventOf(response).regions().get(0);
    }

    @Test
    @DisplayName("picksBestRating: the max over the allowed places only; an unpicked 5 never counts")
    void picksBestRating_isTheAllowedOnlyMax() {
        DailyBriefingResponse response = briefing(List.of(region("North East", List.of(
                ratedSlot(PICKED_ID, PICKED, 2, false), ratedSlot(UNPICKED_ID, UNPICKED, 5, false)))),
                List.of(), null, List.of());

        BriefingRegion shaped = shapedRegion(shaper.shapeBriefing(response, scopeOfPicked()));

        assertThat(shaped.picksBestRating()).isEqualTo(2);
    }

    @Test
    @DisplayName("picksBestRating: the region's own canopy rule, so a picked wood in a sky region does not vote")
    void picksBestRating_appliesTheCanopyRuleToTheFullSlotList() {
        // The region has a sky slot (unpicked), so only sky slots vote; the picked place is a wood.
        DailyBriefingResponse response = briefing(List.of(region("North East", List.of(
                ratedSlot(PICKED_ID, PICKED, 5, true), ratedSlot(UNPICKED_ID, UNPICKED, 3, false)))),
                List.of(), null, List.of());

        BriefingRegion shaped = shapedRegion(shaper.shapeBriefing(response, scopeOfPicked()));

        assertThat(shaped.picksBestRating()).as("the wood's 5 is not a sky star").isNull();
    }

    @Test
    @DisplayName("picksBestRating: an all-woodland region falls back to its woods, exactly as its own best does")
    void picksBestRating_allCanopyRegionCountsItsWoods() {
        DailyBriefingResponse response = briefing(List.of(region("North East", List.of(
                ratedSlot(PICKED_ID, PICKED, 4, true), ratedSlot(UNPICKED_ID, UNPICKED, 5, true)))),
                List.of(), null, List.of());

        BriefingRegion shaped = shapedRegion(shaper.shapeBriefing(response, scopeOfPicked()));

        assertThat(shaped.picksBestRating()).isEqualTo(4);
    }

    @Test
    @DisplayName("picksBestRating: null with no allowed slot, with an unrated allowed slot, and for a blanked region")
    void picksBestRating_nullWhenNothingQualifies() {
        BriefingSlot unrated = new BriefingSlot(PICKED_ID, PICKED, AT, Verdict.GO, weather(),
                BriefingSlot.TideInfo.NONE, List.of(), null, null, null, null, null, null,
                DisplayVerdict.UNRATED, null, false, null, null);
        DailyBriefingResponse noAllowed = briefing(List.of(region("North East", List.of(
                ratedSlot(UNPICKED_ID, UNPICKED, 5, false)))), List.of(), null, List.of());
        DailyBriefingResponse unratedAllowed = briefing(List.of(region("North East", List.of(unrated))),
                List.of(), null, List.of());
        DailyBriefingResponse blanked = briefing(List.of(region("North East", List.of())), List.of(), null,
                List.of());

        assertThat(shapedRegion(shaper.shapeBriefing(noAllowed, scopeOfPicked())).picksBestRating()).isNull();
        assertThat(shapedRegion(shaper.shapeBriefing(unratedAllowed, scopeOfPicked())).picksBestRating())
                .isNull();
        assertThat(shapedRegion(shaper.shapeBriefing(blanked, scopeOfPicked())).picksBestRating()).isNull();
        assertThat(shapedRegion(shaper.shapeBriefing(noAllowed, PickScope.nothing())).picksBestRating())
                .isNull();
    }

    @Test
    @DisplayName("picksBestRating: an unshaped response (unrestricted scope) is returned as it was, with none")
    void picksBestRating_absentWhenUnrestricted() {
        DailyBriefingResponse response = briefing(List.of(region("North East", List.of(
                ratedSlot(PICKED_ID, PICKED, 2, false)))), List.of(), null, List.of());

        DailyBriefingResponse out = shaper.shapeBriefing(response, PickScope.unrestricted());

        assertThat(shapedRegion(out).picksBestRating()).isNull();
        assertThat(eventOf(out).shapedLeadRegion()).isNull();
    }

    private static BriefingRegion tiered(String name, DisplayVerdict verdict, Double mean, boolean sufficient,
            List<BriefingSlot> slots) {
        return new BriefingRegion(name, Verdict.GO, "prose", List.of(), slots, 11.0, 10.0, 4.0, 3, null, null,
                verdict, slots.size(), null, false, Confidence.HIGH, mean, 5, null, sufficient, null);
    }

    @Test
    @DisplayName("shapedLeadRegion: by verdict tier alone, never by a mean; ties by name")
    void shapedLeadRegion_isChosenByTierThenName() {
        List<BriefingSlot> sky = List.of(ratedSlot(PICKED_ID, PICKED, 4, false));
        DailyBriefingResponse response = briefing(List.of(
                tiered("Zeta", DisplayVerdict.MAYBE, 4.9, true, sky),
                tiered("Yorkshire", DisplayVerdict.WORTH_IT, 3.5, true, sky),
                tiered("Cumbria", DisplayVerdict.WORTH_IT, 3.6, true, sky),
                tiered("Thin", DisplayVerdict.WORTH_IT, 4.4, false, sky)), List.of(), null, List.of());

        assertThat(eventOf(shaper.shapeBriefing(response, scopeOfPicked())).shapedLeadRegion())
                .as("eligible WORTH_IT regions first, then the name: Cumbria before Yorkshire")
                .isEqualTo("Cumbria");
    }

    @Test
    @DisplayName("shapedLeadRegion: a woodland-only region is not eligible in a mixed window; null with no region")
    void shapedLeadRegion_dropsCanopyOnlyRegionsAndIsNullWithNoRegion() {
        DailyBriefingResponse mixed = briefing(List.of(
                tiered("Aardvark Woods", DisplayVerdict.WORTH_IT, 4.5, true,
                        List.of(ratedSlot(UNPICKED_ID, UNPICKED, 5, true))),
                tiered("Borders", DisplayVerdict.MAYBE, 3.0, true,
                        List.of(ratedSlot(PICKED_ID, PICKED, 3, false)))), List.of(), null, List.of());
        DailyBriefingResponse empty = briefing(List.of(), List.of(), null, List.of());

        assertThat(eventOf(shaper.shapeBriefing(mixed, scopeOfPicked())).shapedLeadRegion())
                .isEqualTo("Borders");
        assertThat(eventOf(shaper.shapeBriefing(empty, scopeOfPicked())).shapedLeadRegion()).isNull();
    }

    @Test
    @DisplayName("Briefing: a reader with no picks sees every slot gated and an empty (not absent) allowed list")
    void briefing_zeroPicksGatesEverythingAndServesAnEmptyAllowedList() {
        DailyBriefingResponse response = briefing(
                List.of(region("North East", List.of(ratedSlot(PICKED_ID, PICKED)))), List.of(), null,
                List.of());

        DailyBriefingResponse shaped = shaper.shapeBriefing(response, PickScope.nothing());

        assertGatedSlot(eventOf(shaped).regions().get(0).slots().get(0));
        assertThat(shaped.allowedLocationIds()).isNotNull().isEmpty();
        assertThat(eventOf(shaped).window()).isNull();
    }

    @Test
    @DisplayName("Briefing: the input is never mutated and the gated flag is absent from an unshaped slot")
    void briefing_neverMutatesTheInput() {
        DailyBriefingResponse response = briefing(
                List.of(region("North East", List.of(ratedSlot(UNPICKED_ID, UNPICKED)))), List.of(), window(),
                List.of(bluebellTopic()));

        shaper.shapeBriefing(response, scopeOfPicked());

        BriefingSlot original = eventOf(response).regions().get(0).slots().get(0);
        assertThat(original.claudeRating()).isEqualTo(4);
        assertThat(original.gated()).isNull();
        assertThat(eventOf(response).regions().get(0).bestRating()).isEqualTo(5);
        assertThat(response.allowedLocationIds()).isNull();
        assertThat(response.hotTopics().get(0).facts()).hasSize(2);
    }

    // ------------------------------------------------------------------ hot topics

    @Test
    @DisplayName("Hot topics: an unpicked place loses its bluebell metrics and Best badge; a picked one keeps both")
    void hotTopics_stripBluebellRatingsPerLocation() {
        DailyBriefingResponse shaped = shaper.shapeBriefing(
                briefing(List.of(), List.of(), null, List.of(bluebellTopic())), scopeOfPicked());

        List<RegionGroup> groups = shaped.hotTopics().get(0).expandedDetail().regionGroups();
        LocationEntry picked = groups.get(0).locations().get(0);
        LocationEntry unpicked = groups.get(1).locations().get(0);
        assertThat(picked.bluebellLocationMetrics().score()).isEqualTo(5);
        assertThat(picked.badge()).isEqualTo("Best");
        assertThat(unpicked.locationName()).isEqualTo(UNPICKED);
        assertThat(unpicked.locationType()).isEqualTo("Open fell");
        assertThat(unpicked.bluebellLocationMetrics()).isNull();
        assertThat(unpicked.badge()).isNull();
    }

    @Test
    @DisplayName("Hot topics: the bluebell topic's best score, label, facts, glance chip and summary go; labels stay")
    void hotTopics_stripTheBluebellAggregates() {
        HotTopic topic = shaper.shapeBriefing(briefing(List.of(), List.of(), null, List.of(bluebellTopic())),
                scopeOfPicked()).hotTopics().get(0);

        assertThat(topic.expandedDetail().bluebellMetrics().bestScore()).isNull();
        assertThat(topic.expandedDetail().bluebellMetrics().qualityLabel()).isNull();
        assertThat(topic.expandedDetail().bluebellMetrics().scoringLocationCount()).isEqualTo(2);
        assertThat(topic.facts()).isNull();
        assertThat(topic.glance().value()).isNull();
        assertThat(topic.glance().shortValue()).isNull();
        assertThat(topic.glance().weather()).isFalse();
        assertThat(topic.detail()).as("the best summary goes, the day label stays").isEqualTo("today");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.date()).isEqualTo(DAY);
        assertThat(topic.regions()).containsExactly("North East");
        assertThat(topic.note()).isEqualTo("note");
    }

    @Test
    @DisplayName("Hot topics: a topic that carries no rating is untouched (free, the two-question rule)")
    void hotTopics_otherTopicsAreUntouched() {
        HotTopic aurora = auroraTopic();

        HotTopic shaped = shaper.shapeBriefing(briefing(List.of(), List.of(), null, List.of(aurora)),
                PickScope.nothing()).hotTopics().get(0);

        assertThat(shaped).isEqualTo(aurora);
    }

    @Test
    @DisplayName("Hot topics: a gated entry follows the allowed ones, by name, and 'Best' goes unless all are allowed")
    void hotTopics_orderGatedEntriesAfterAllowedAndDropBestUnlessAllAllowed() {
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new RegionGroup("Mixed", null, null, null, List.of(
                        new LocationEntry("Zeta", "Woodland", "Best",
                                new BluebellLocationMetrics(5, "WOODLAND", "z"), null),
                        new LocationEntry(PICKED, "Woodland", null,
                                new BluebellLocationMetrics(4, "WOODLAND", "p"), null),
                        new LocationEntry("Alpha", "Woodland", null,
                                new BluebellLocationMetrics(3, "WOODLAND", "a"), null))),
                new RegionGroup("Allowed", null, null, null, List.of(
                        new LocationEntry(PICKED, "Woodland", "Best",
                                new BluebellLocationMetrics(5, "WOODLAND", "p"), null)))),
                new BluebellMetrics(5, "Excellent", 4), null);
        HotTopic topic = new HotTopic("BLUEBELL", "Bluebell conditions", "x today", DAY, 1, "BLUEBELL",
                List.of(), "d", detail);

        List<RegionGroup> groups = shaper.shapeBriefing(briefing(List.of(), List.of(), null, List.of(topic)),
                scopeOfPicked()).hotTopics().get(0).expandedDetail().regionGroups();

        assertThat(groups.get(0).locations()).extracting(LocationEntry::locationName)
                .containsExactly(PICKED, "Alpha", "Zeta");
        assertThat(groups.get(0).locations()).extracting(LocationEntry::badge).containsOnlyNulls();
        assertThat(groups.get(1).locations().get(0).badge()).as("every entry allowed: Best stays")
                .isEqualTo("Best");
    }

    @Test
    @DisplayName("Window badges: the bluebell badge loses its chips and best-site summary; other badges are kept")
    void windowBadges_bluebellBadgeIsStripped() {
        BriefingWindow.Badge bluebell = new BriefingWindow.Badge("BLUEBELL", "Bluebell conditions",
                "Carpet in bloom today", List.of(HotTopicFact.metric("conditions", "5/5 · excellent")), "06:00",
                3, "note", null, null);
        BriefingWindow.Badge aurora = new BriefingWindow.Badge("AURORA", "Aurora", "Kp 6",
                List.of(HotTopicFact.metric("kp", "6")), null, 1, null, null, null);
        BriefingWindow window = new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 5, Confidence.HIGH, null,
                List.of(bluebell, aurora), null, null, null);

        List<BriefingWindow.Badge> badges = eventOf(shaper.shapeBriefing(
                briefing(List.of(), List.of(), window, List.of()), scopeOfPicked())).window().badges();

        assertThat(badges.get(0).facts()).isEmpty();
        assertThat(badges.get(0).detail()).isEqualTo("today");
        assertThat(badges.get(0).label()).isEqualTo("Bluebell conditions");
        assertThat(badges.get(0).note()).isEqualTo("note");
        assertThat(badges.get(1)).isEqualTo(aurora);
    }

    @Test
    @DisplayName("dayLabelOnly keeps a real day label and otherwise yields an empty string, never the original text")
    void dayLabelOnly_fallsBackToEmpty() {
        assertThat(LitePickShaper.dayLabelOnly("Carpet in bloom today")).isEqualTo("today");
        assertThat(LitePickShaper.dayLabelOnly("Great light tomorrow")).isEqualTo("tomorrow");
        assertThat(LitePickShaper.dayLabelOnly("Good Saturday")).isEqualTo("Saturday");
        assertThat(LitePickShaper.dayLabelOnly("tomorrow")).isEqualTo("tomorrow");
        assertThat(LitePickShaper.dayLabelOnly("Carpet in bloom at Craster")).isEmpty();
        assertThat(LitePickShaper.dayLabelOnly("5/5 excellent")).isEmpty();
        assertThat(LitePickShaper.dayLabelOnly("")).isEmpty();
        assertThat(LitePickShaper.dayLabelOnly(null)).isEmpty();
    }

    // ------------------------------------------------------------------ the other payloads

    @Test
    @DisplayName("Digest: each window's best star goes; the verdict and confidence stay")
    void digest_stripsBestRating() {
        BriefingDigestResponse digest = new BriefingDigestResponse(AT, List.of(
                new BriefingDigestResponse.Window(DAY, TargetType.SUNSET, AT, DisplayVerdict.WORTH_IT, 5,
                        Confidence.HIGH, null, null, null, null, null)));

        BriefingDigestResponse.Window shaped = shaper.shapeDigest(digest, scopeOfPicked()).windows().get(0);

        assertThat(shaped.bestRating()).isNull();
        assertThat(shaped.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(shaped.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    @DisplayName("Scores: a view outside the picks is gated; one inside is served as it was")
    void scores_gateViewsOutsideThePicks() {
        LocationEvaluationView inside = view(PICKED_ID);
        LocationEvaluationView outside = view(UNPICKED_ID);

        List<LocationEvaluationView> shaped = shaper.shapeScores(List.of(inside, outside), scopeOfPicked());

        assertThat(shaped.get(0)).isSameAs(inside);
        assertThat(shaped.get(1).gated()).isTrue();
        assertThat(shaped.get(1).rating()).isNull();
        assertThat(shaped.get(1).displayVerdict()).isNull();
        assertThat(shaped.get(1).triageReason()).isNull();
        assertThat(shaped.get(1).source()).isEqualTo(LocationEvaluationView.Source.NONE);
        assertThat(shaped.get(1).locationId()).isEqualTo(UNPICKED_ID);
        assertThat(shaped.get(1).goldenHourStart()).as("light times are location facts").isNotNull();
    }

    private static LocationEvaluationView view(long id) {
        return populated(LocationEvaluationView.class, id);
    }

    @Test
    @DisplayName("Forecast list: a row is matched by location name; one outside the picks is gated, past days too")
    void forecastList_gatesRowsByName() {
        ForecastListDto inside = populated(ForecastListDto.class, 1L, PICKED);
        ForecastListDto outside = populated(ForecastListDto.class, 2L, UNPICKED);

        assertThat(shaper.shapeListRow(inside, scopeOfPicked())).isSameAs(inside);
        ForecastListDto gated = shaper.shapeListRow(outside, scopeOfPicked());
        assertThat(gated.gated()).isTrue();
        assertThat(gated.rating()).isNull();
        assertThat(gated.id()).as("the id survives; the detail endpoint gates it too").isEqualTo(2L);
        assertThat(gated.windSpeed()).isNotNull();
    }

    @Test
    @DisplayName("Forecast detail: an id outside the picks never gets the full evaluation")
    void forecastDetail_unpickedIdGetsTheGatedDto() {
        ForecastEvaluationDto full = populated(ForecastEvaluationDto.class, 1L, UNPICKED);

        ForecastEvaluationDto shaped = shaper.shapeDetail(full, UNPICKED_ID, scopeOfPicked());

        assertThat(shaped.gated()).isTrue();
        assertThat(shaped.rating()).isNull();
        assertThat(shaped.summary()).isNull();
        assertThat(shaped.bluebellScore()).isNull();
        assertThat(shaped.lowCloud()).as("weather inputs stay").isNotNull();
        assertThat(shaper.shapeDetail(full, PICKED_ID, scopeOfPicked())).isSameAs(full);
        assertThat(shaper.shapeDetail(full, null, scopeOfPicked()).gated()).isTrue();
    }

    @Test
    @DisplayName("Astro: stars and prose go for a location outside the picks; the moon facts stay")
    void astro_gatesStarsAndProse() {
        AstroConditionsDto inside = populated(AstroConditionsDto.class, PICKED_ID);
        AstroConditionsDto outside = populated(AstroConditionsDto.class, UNPICKED_ID);

        assertThat(shaper.shapeAstro(inside, scopeOfPicked())).isSameAs(inside);
        AstroConditionsDto gated = shaper.shapeAstro(outside, scopeOfPicked());
        assertThat(gated.gated()).isTrue();
        assertThat(gated.stars()).isNull();
        assertThat(gated.summary()).isNull();
        assertThat(gated.moonPhase()).isEqualTo(outside.moonPhase());
        assertThat(gated.bortleClass()).isEqualTo(outside.bortleClass());
    }

    // ------------------------------------------------------------------ completeness

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... groups) {
        Set<String> all = new HashSet<>();
        for (Set<String> g : groups) {
            all.addAll(g);
        }
        return all;
    }

    private static Object get(RecordComponent c, Object target) {
        try {
            return c.getAccessor().invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isCleared(Object value) {
        return value == null || (value instanceof Collection<?> c && c.isEmpty());
    }

    /**
     * Every component of {@code type} must be classified, and the gated copy must clear every STRIPPED one,
     * keep every KEPT one verbatim, and set the marker: a new rating field fails this until it is handled.
     */
    private static <T extends Record> void assertGatedCopy(Class<T> type, T full, T gated,
            Set<String> stripped, Set<String> kept) {
        assertThat(names(type)).as("a new component of " + type.getSimpleName()
                        + " must be classified as stripped or kept here and handled in its forGated()")
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept, Set.of("gated")));
        for (RecordComponent c : type.getRecordComponents()) {
            Object before = get(c, full);
            Object after = get(c, gated);
            if (c.getName().equals("gated")) {
                assertThat(before).as("gated before").isNull();
                assertThat(after).as("gated after").isEqualTo(Boolean.TRUE);
            } else if (stripped.contains(c.getName())) {
                assertThat(before).as(c.getName() + " in the fixture").isNotNull();
                assertThat(isCleared(after)).as(c.getName() + " is stripped").isTrue();
            } else {
                assertThat(after).as(c.getName() + " is kept").isEqualTo(before);
            }
        }
    }

    @Test
    @DisplayName("Completeness: BriefingSlot.forGated clears exactly the rating components")
    void completeness_briefingSlot() {
        BriefingSlot full = populated(BriefingSlot.class, PICKED_ID);

        assertGatedCopy(BriefingSlot.class, full, full.forGated(),
                Set.of("verdict", "flags", "standdownReason", "claudeRating", "skyRating", "fierySkyPotential",
                        "goldenHourPotential", "claudeSummary", "displayVerdict", "claudeHeadline",
                        "neighbourOutlier"),
                Set.of("locationId", "locationName", "solarEventTime", "weather", "tide", "canopy",
                        "eclipse"));
    }

    @Test
    @DisplayName("Completeness: ForecastListDto.forGated clears exactly the rating components")
    void completeness_forecastListDto() {
        ForecastListDto full = populated(ForecastListDto.class, 1L, PICKED);

        assertGatedCopy(ForecastListDto.class, full, full.forGated(),
                Set.of("rating", "fierySkyPotential", "goldenHourPotential", "triageReason", "summary",
                        "triageMessage"),
                Set.of("id", "locationName", "locationLat", "locationLon", "targetDate", "targetType",
                        "forecastRunAt", "solarEventTime", "azimuthDeg", "temperatureCelsius",
                        "apparentTemperatureCelsius", "windSpeed", "windDirection", "precipitation",
                        "precipitationProbabilityPercent"));
    }

    @Test
    @DisplayName("Completeness: ForecastEvaluationDto.forGated clears exactly the rating components")
    void completeness_forecastEvaluationDto() {
        ForecastEvaluationDto full = populated(ForecastEvaluationDto.class, 1L, PICKED);

        assertGatedCopy(ForecastEvaluationDto.class, full, full.forGated(),
                Set.of("rating", "fierySkyPotential", "goldenHourPotential", "summary", "headline",
                        "bluebellScore", "bluebellSummary", "triageReason", "triageMessage", "mistPotential"),
                Set.of("id", "locationName", "locationLat", "locationLon", "targetDate", "targetType",
                        "forecastRunAt", "daysAhead", "solarEventTime", "azimuthDeg", "evaluationModel",
                        "lowCloud", "midCloud", "highCloud", "visibility", "windSpeed", "windDirection",
                        "precipitation", "humidity", "weatherCode", "boundaryLayerHeight",
                        "shortwaveRadiation", "pm25", "dust", "aerosolOpticalDepth", "temperatureCelsius",
                        "apparentTemperatureCelsius", "precipitationProbabilityPercent", "dewPointCelsius",
                        "tideState", "nextHighTideTime", "nextHighTideHeightMetres", "nextLowTideTime",
                        "nextLowTideHeightMetres", "tideAligned", "solarLowCloud", "solarMidCloud",
                        "solarHighCloud", "antisolarLowCloud", "antisolarMidCloud", "antisolarHighCloud",
                        "solarTrendEventLowCloud", "solarTrendEarliestLowCloud", "solarTrendBuilding",
                        "upwindCurrentLowCloud", "upwindEventLowCloud", "upwindDistanceKm", "lunarTideType",
                        "lunarPhase", "surgeTotalMetres", "surgePressureMetres", "surgeWindMetres",
                        "surgeRiskLevel", "surgeAdjustedRangeMetres", "surgeAstronomicalRangeMetres",
                        "inversionScore", "inversionPotential", "goldenHourStart", "goldenHourEnd",
                        "blueHourStart", "blueHourEnd", "bluebellExposure", "significantWaveHeightMetres",
                        "seaState"));
    }

    @Test
    @DisplayName("Completeness: LocationEvaluationView.forGated clears exactly the rating components")
    void completeness_locationEvaluationView() {
        LocationEvaluationView full = populated(LocationEvaluationView.class, PICKED_ID);
        LocationEvaluationView gated = full.forGated();

        // The source is masked to NONE rather than nulled: it is the one component that cannot be null.
        assertThat(gated.source()).isEqualTo(LocationEvaluationView.Source.NONE);
        assertThat(full.source()).isNotEqualTo(LocationEvaluationView.Source.NONE);
        assertThat(names(LocationEvaluationView.class)).containsExactlyInAnyOrderElementsOf(
                union(Set.of("rating", "summary", "fierySkyPotential", "goldenHourPotential", "triageReason",
                        "triageMessage", "displayVerdict", "evaluationModel", "evaluatedAt"),
                        Set.of("locationId", "locationName", "regionId", "regionName", "date", "targetType",
                                "goldenHourStart", "goldenHourEnd", "blueHourStart", "blueHourEnd"),
                        Set.of("source", "gated")));
        for (String stripped : List.of("rating", "summary", "fierySkyPotential", "goldenHourPotential",
                "triageReason", "triageMessage", "displayVerdict", "evaluationModel", "evaluatedAt")) {
            RecordComponent c = component(LocationEvaluationView.class, stripped);
            assertThat(get(c, full)).as(stripped + " in the fixture").isNotNull();
            assertThat(get(c, gated)).as(stripped).isNull();
        }
        for (String kept : List.of("locationId", "locationName", "regionId", "regionName", "date",
                "targetType", "goldenHourStart", "goldenHourEnd", "blueHourStart", "blueHourEnd")) {
            RecordComponent c = component(LocationEvaluationView.class, kept);
            assertThat(get(c, gated)).as(kept).isEqualTo(get(c, full));
        }
        assertThat(gated.gated()).isTrue();
        assertThat(full.gated()).isNull();
    }

    @Test
    @DisplayName("Completeness: AstroConditionsDto.forGated clears exactly the rating components")
    void completeness_astroConditionsDto() {
        AstroConditionsDto full = populated(AstroConditionsDto.class, PICKED_ID);

        assertGatedCopy(AstroConditionsDto.class, full, full.forGated(),
                Set.of("stars", "summary", "cloudExplanation", "visibilityExplanation", "moonExplanation"),
                Set.of("locationId", "locationName", "lat", "lon", "bortleClass", "forecastDate", "moonPhase",
                        "moonIlluminationPct", "nightStart", "nightEnd"));
    }

    @Test
    @DisplayName("Completeness: the digest window and the briefing window lose exactly their best rating")
    void completeness_windows() {
        BriefingDigestResponse.Window digest = populated(BriefingDigestResponse.Window.class);
        BriefingDigestResponse.Window shapedDigest = digest.withoutBestRating();
        assertThat(names(BriefingDigestResponse.Window.class)).containsExactlyInAnyOrderElementsOf(
                union(Set.of("bestRating"), Set.of("date", "event", "eventTime", "verdict", "confidence", "pick",
                        "headline", "regionName", "locationName", "locationId")));
        assertThat(shapedDigest.bestRating()).isNull();
        assertThat(shapedDigest.withoutPick()).isEqualTo(digest.withoutPick().withoutBestRating());

        BriefingWindow window = populated(BriefingWindow.class);
        BriefingWindow shaped = window.withBestRating(null);
        assertThat(names(BriefingWindow.class)).containsExactlyInAnyOrderElementsOf(
                union(Set.of("bestRating"), Set.of("eventTime", "verdict", "confidence", "pick", "badges",
                        "topRarityRank", "tide", "tideFacts")));
        for (RecordComponent c : BriefingWindow.class.getRecordComponents()) {
            if (c.getName().equals("bestRating")) {
                assertThat(get(c, window)).isNotNull();
                assertThat(get(c, shaped)).isNull();
            } else {
                assertThat(get(c, shaped)).as(c.getName()).isEqualTo(get(c, window));
            }
        }
    }

    @Test
    @DisplayName("Completeness: a shaped region loses exactly best, mean and delta; everything else is carried")
    void completeness_briefingRegion() {
        BriefingRegion full = populated(BriefingRegion.class);
        DailyBriefingResponse shapedResponse = shaper.shapeBriefing(
                briefing(List.of(full), List.of(), null, List.of()), scopeOfPicked());
        BriefingRegion shaped = eventOf(shapedResponse).regions().get(0);

        Set<String> stripped = Set.of("meanRating", "bestRating", "meanRatingDelta");
        // picksBestRating is computed by the shaper from the allowed slots, so it is neither stripped nor carried.
        Set<String> computed = Set.of("picksBestRating");
        Set<String> shapedPerSlot = Set.of("slots");
        Set<String> kept = Set.of("regionName", "verdict", "summary", "tideHighlights",
                "regionTemperatureCelsius", "regionApparentTemperatureCelsius", "regionWindSpeedMs",
                "regionWeatherCode", "glossHeadline", "glossDetail", "displayVerdict", "scoredLocationCount",
                "verdictLabel", "lightlyEvaluated", "confidence", "sampleSufficient", "forcedSample",
                "coverageReason");
        assertThat(names(BriefingRegion.class)).as("classify every BriefingRegion component")
                .containsExactlyInAnyOrderElementsOf(union(stripped, computed, shapedPerSlot, kept));
        for (RecordComponent c : BriefingRegion.class.getRecordComponents()) {
            if (stripped.contains(c.getName())) {
                assertThat(get(c, full)).as(c.getName() + " in the fixture").isNotNull();
                assertThat(get(c, shaped)).as(c.getName()).isNull();
            } else if (kept.contains(c.getName())) {
                assertThat(get(c, shaped)).as(c.getName()).isEqualTo(get(c, full));
            }
        }
    }

    @Test
    @DisplayName("Completeness: every component of the briefing tree is classified as carried or shaped")
    void completeness_briefingTree() {
        assertThat(names(DailyBriefingResponse.class)).containsExactlyInAnyOrderElementsOf(union(
                Set.of("days", "hotTopics", "allowedLocationIds"),
                Set.of("generatedAt", "headline", "bestBets", "auroraTonight", "auroraTomorrow", "stale",
                        "partialFailure", "failedLocationCount", "bestBetModel", "seasonalFeatures",
                        "bestBetStatus", "bestBetsWithdrawn", "renderedEvents", "previousGeneratedAt",
                        "bigSeas")));
        assertThat(names(BriefingDay.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("eventSummaries", "date", "peak"));
        assertThat(names(BriefingDayPeak.class)).as("the day peak carries a verdict, never a star")
                .containsExactlyInAnyOrderElementsOf(Set.of("verdict", "events", "regions"));
        assertThat(names(BriefingEventSummary.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("regions", "unregioned", "window", "targetType", "solarEventTime", "shapedLeadRegion"));
        assertThat(names(HotTopic.class)).containsExactlyInAnyOrderElementsOf(union(
                Set.of("expandedDetail", "detail", "facts", "glance"),
                Set.of("type", "label", "date", "priority", "filterAction", "regions", "description",
                        "eventType", "eventTime", "locationNames", "eveningWindow", "morningWindow", "note",
                        "tideRun", "surgeRun", "rarityNote", "safetyNote", "liteDetail")));
        assertThat(names(ExpandedHotTopicDetail.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("regionGroups", "bluebellMetrics", "tideMetrics"));
        assertThat(names(RegionGroup.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("regionName", "glossHeadline", "glossDetail", "verdict", "locations"));
        assertThat(names(LocationEntry.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("locationName", "locationType", "badge", "bluebellLocationMetrics",
                        "tideLocationMetrics"));
        assertThat(names(BluebellMetrics.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("bestScore", "qualityLabel", "scoringLocationCount"));
        assertThat(names(BluebellLocationMetrics.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("score", "exposure", "summary"));
        assertThat(names(GlanceLine.class)).containsExactlyInAnyOrderElementsOf(
                Set.of("value", "shortValue", "badge", "weather"));
    }

    @Test
    @DisplayName("Completeness: the bluebell entry and metrics strippers clear exactly the rating components")
    void completeness_bluebellRecords() {
        LocationEntry entry = new LocationEntry("Place", "Woodland", "Best",
                new BluebellLocationMetrics(5, "WOODLAND", "s"), new ExpandedHotTopicDetail.TideLocationMetrics("p"));
        LocationEntry stripped = entry.withoutBluebellRating();
        assertThat(stripped.badge()).isNull();
        assertThat(stripped.bluebellLocationMetrics()).isNull();
        assertThat(stripped.locationName()).isEqualTo("Place");
        assertThat(stripped.locationType()).isEqualTo("Woodland");
        assertThat(stripped.tideLocationMetrics()).isEqualTo(entry.tideLocationMetrics());

        BluebellMetrics metrics = new BluebellMetrics(5, "Excellent", 3).withoutScore();
        assertThat(metrics.bestScore()).isNull();
        assertThat(metrics.qualityLabel()).isNull();
        assertThat(metrics.scoringLocationCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ reflective fixtures

    /** Builds a record with every component populated, except {@code gated}/{@code allowedLocationIds} (null). */
    private static <T extends Record> T populated(Class<T> type, Object... overrides) {
        try {
            RecordComponent[] comps = type.getRecordComponents();
            Class<?>[] types = Arrays.stream(comps).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] args = new Object[comps.length];
            List<Object> pending = new ArrayList<>(Arrays.asList(overrides));
            for (int i = 0; i < comps.length; i++) {
                String name = comps[i].getName();
                if (name.equals("gated") || name.equals("allowedLocationIds")) {
                    args[i] = null;
                } else if (name.equals("locationId") && !pending.isEmpty() && pending.get(0) instanceof Long) {
                    args[i] = pending.remove(0);
                } else if (name.equals("id") && !pending.isEmpty() && pending.get(0) instanceof Long) {
                    args[i] = pending.remove(0);
                } else if (name.equals("locationName") && !pending.isEmpty() && pending.get(0) instanceof String) {
                    args[i] = pending.remove(0);
                } else {
                    args[i] = sample(comps[i].getType(), comps[i].getGenericType());
                }
            }
            Constructor<T> ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot populate " + type, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object sample(Class<?> type, Type generic) {
        if (type == String.class) {
            return "x";
        } else if (type == Long.class || type == long.class) {
            return 7L;
        } else if (type == Integer.class || type == int.class) {
            return 3;
        } else if (type == Double.class || type == double.class) {
            return 1.5;
        } else if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        } else if (type == BigDecimal.class) {
            return BigDecimal.ONE;
        } else if (type == LocalDate.class) {
            return DAY;
        } else if (type == LocalDateTime.class) {
            return AT;
        } else if (type == Instant.class) {
            return Instant.parse("2026-10-27T06:00:00Z");
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        } else if (type == List.class) {
            Type element = ((ParameterizedType) generic).getActualTypeArguments()[0];
            Class<?> elementClass = element instanceof Class<?> c ? c : (Class<?>) ((ParameterizedType) element)
                    .getRawType();
            return List.of(sample(elementClass, element));
        } else if (type.isRecord()) {
            return populated((Class<? extends Record>) type);
        }
        throw new IllegalStateException("No sample for " + type);
    }

    private static RecordComponent component(Class<? extends Record> type, String name) {
        return Arrays.stream(type.getRecordComponents()).filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow();
    }
}
