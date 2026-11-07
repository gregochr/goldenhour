package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.BluebellLocationMetrics;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.LocationEntry;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail.RegionGroup;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SubRegionLocationRow;
import com.gregochr.goldenhour.model.Verdict;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Branch tests for {@link LitePickShaper}: odd authentications, the no-store header outside a full request,
 * lead-region ordering by mean and tier, null-tolerant hot-topic shaping and a null digest.
 */
class LitePickShaperBranchTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 27, 6, 0);
    private static final String PICKED = "Bamburgh Castle";
    private static final String UNPICKED = "Craster";

    private final UserSubRegionService picks = mock(UserSubRegionService.class);
    private final SubRegionProperties properties = new SubRegionProperties();
    private LitePickShaper shaper;

    @BeforeEach
    void setUp() {
        properties.setEnforce(true);
        shaper = new LitePickShaper(picks, properties);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static PickScope scopeOfPicked() {
        return PickScope.of(List.of(new SubRegionLocationRow(100L, 7L, PICKED, 55.6, -1.7)));
    }

    private static BriefingRegion region(String name, DisplayVerdict verdict, Double mean) {
        return new BriefingRegion(name, Verdict.GO, "prose", List.of(), List.of(), 11.0, 10.0, 4.0, 3,
                "headline", "detail", verdict, 3, "label", Confidence.HIGH, mean, 4, 0.4, true, null);
    }

    private static DailyBriefingResponse briefing(List<HotTopic> topics) {
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(), AT, null);
        return new DailyBriefingResponse(AT, "headline", List.of(new BriefingDay(DAY, List.of(summary))),
                List.of(), null, null, false, null, topics, List.of());
    }

    @Test
    @DisplayName("an authenticated caller with a null name is shaped to nothing and no picks are read")
    void scopeFor_nullName_nothing() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn(null);

        assertThat(shaper.scopeFor(auth)).isSameAs(PickScope.nothing());
        verifyNoInteractions(picks);
    }

    @Test
    @DisplayName("an authenticated caller with a blank name is shaped to nothing and no picks are read")
    void scopeFor_blankName_nothing() {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("  ");

        assertThat(shaper.scopeFor(auth)).isSameAs(PickScope.nothing());
        verifyNoInteractions(picks);
    }

    @Test
    @DisplayName("markNoStore sets no-store on the response of a servlet request")
    void markNoStore_setsHeader() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));

        LitePickShaper.markNoStore(scopeOfPicked());

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    @DisplayName("markNoStore is a no-op for an unshaped scope")
    void markNoStore_unshaped_noop() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));

        LitePickShaper.markNoStore(PickScope.unrestricted());

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
    }

    @Test
    @DisplayName("markNoStore tolerates request attributes that carry no response")
    void markNoStore_noResponse_noop() {
        ServletRequestAttributes attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attributes);

        LitePickShaper.markNoStore(scopeOfPicked());

        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(attributes);
    }

    @Test
    @DisplayName("markNoStore is a no-op outside a web request")
    void markNoStore_noRequest_noop() {
        RequestContextHolder.resetRequestAttributes();

        LitePickShaper.markNoStore(scopeOfPicked());

        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    @DisplayName("shapedLeadRegion prefers a region with a mean over a mean-less one of the same tier")
    void shapedLeadRegion_meanBeforeMeanless() {
        List<BriefingRegion> regions = List.of(
                region("Alpha", DisplayVerdict.WORTH_IT, null),
                region("Beta", DisplayVerdict.WORTH_IT, 3.0));

        assertThat(LitePickShaper.shapedLeadRegion(regions)).isEqualTo("Beta");
    }

    @Test
    @DisplayName("shapedLeadRegion ranks a region with no display verdict below any tiered one")
    void shapedLeadRegion_nullVerdictLast() {
        List<BriefingRegion> regions = List.of(
                region("Alpha", null, 4.0),
                region("Zulu", DisplayVerdict.AWAITING, 2.0));

        assertThat(LitePickShaper.shapedLeadRegion(regions)).isEqualTo("Zulu");
    }

    @Test
    @DisplayName("shapedLeadRegion ranks tiers WORTH_IT, MAYBE, UNRATED, STAND_DOWN, AWAITING")
    void shapedLeadRegion_tierOrder() {
        List<BriefingRegion> regions = List.of(
                region("A", DisplayVerdict.AWAITING, 2.0),
                region("B", DisplayVerdict.STAND_DOWN, 2.0),
                region("C", DisplayVerdict.UNRATED, 2.0),
                region("D", DisplayVerdict.MAYBE, 2.0));

        assertThat(LitePickShaper.shapedLeadRegion(regions)).isEqualTo("D");
        assertThat(LitePickShaper.shapedLeadRegion(regions.subList(0, 3))).isEqualTo("C");
        assertThat(LitePickShaper.shapedLeadRegion(regions.subList(0, 2))).isEqualTo("B");
    }

    @Test
    @DisplayName("a non-bluebell topic with null region groups, null metrics and entries is shaped without error")
    void hotTopics_nullPartsTolerated() {
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new RegionGroup("Empty", null, null, null, null)), null, null);
        HotTopic topic = new HotTopic("TIDE", "Tide", "detail", DAY, 1, "TIDE", List.of(), "d", detail);
        ExpandedHotTopicDetail noGroups = new ExpandedHotTopicDetail(null, null, null);
        HotTopic noGroupsTopic = new HotTopic("TIDE", "Tide", "detail", DAY, 1, "TIDE", List.of(), "d", noGroups);

        List<HotTopic> shaped = shaper.shapeBriefing(briefing(List.of(topic, noGroupsTopic)), scopeOfPicked())
                .hotTopics();

        assertThat(shaped.get(0).expandedDetail().regionGroups()).hasSize(1);
        assertThat(shaped.get(0).expandedDetail().regionGroups().get(0).locations()).isNull();
        assertThat(shaped.get(0).expandedDetail().bluebellMetrics()).isNull();
        assertThat(shaped.get(1).expandedDetail().regionGroups()).isNull();
    }

    @Test
    @DisplayName("a non-bluebell topic gates only unpicked entries that carry bluebell metrics")
    void hotTopics_nonBluebellEntries() {
        LocationEntry plain = new LocationEntry(UNPICKED, "Beach", "Best", null, null);
        LocationEntry unpickedRated = new LocationEntry("Seahouses", "Woodland", "Best",
                new BluebellLocationMetrics(4, "WOODLAND", "good"), null);
        LocationEntry pickedRated = new LocationEntry(PICKED, "Woodland", null,
                new BluebellLocationMetrics(5, "WOODLAND", "great"), null);
        ExpandedHotTopicDetail detail = new ExpandedHotTopicDetail(List.of(
                new RegionGroup("Mixed", null, null, null, List.of(plain, unpickedRated, pickedRated))), null, null);
        HotTopic topic = new HotTopic("TIDE", "Tide", "detail", DAY, 1, "TIDE", List.of(), "d", detail);

        List<LocationEntry> entries = shaper.shapeBriefing(briefing(List.of(topic)), scopeOfPicked())
                .hotTopics().get(0).expandedDetail().regionGroups().get(0).locations();

        assertThat(entries).extracting(LocationEntry::locationName).containsExactly(UNPICKED, PICKED, "Seahouses");
        assertThat(entries.get(0).bluebellLocationMetrics()).isNull();
        assertThat(entries.get(1).bluebellLocationMetrics().score()).isEqualTo(5);
        assertThat(entries.get(2).bluebellLocationMetrics()).isNull();
        assertThat(entries).extracting(LocationEntry::badge).containsOnlyNulls();
    }

    @Test
    @DisplayName("shapeDigest passes a null digest through")
    void shapeDigest_null() {
        assertThat(shaper.shapeDigest(null, scopeOfPicked())).isNull();
    }
}
