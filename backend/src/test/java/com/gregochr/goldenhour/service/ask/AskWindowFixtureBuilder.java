package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a weekend's {@link AskSnapshot} from a compact JSON fixture
 * ({@code prompt-regression/ask/ask-*-weekend.json}) through the real {@link AskSnapshotBuilder}, for the
 * window-context regression ({@link AskWindowContextRegressionTest}) and its unit test.
 *
 * <p>A 180-slot forecast written out by hand is unreadable and the band is the fact, so a region
 * describes a window two ways: <b>named</b> places with an explicit rating per window (the coastal
 * exceptions, the BEST BET) and a <b>synthetic</b> block per window, {@code bands} (the count of
 * unnamed places at 1, 2, 3, 4 and 5★) plus {@code unrated}, which the builder expands into slots
 * named {@code "<Region> fell NN"} ({@link #SYNTHETIC_MARK}) so an assertion can tell a filler place
 * from a named one. A region listed in {@code stoodDown} for a window is blanked the way the honesty
 * filter serves a region the briefing stood down whole: no slots, the {@code STOOD_DOWN} reason.
 * Every region is verdict-eligible.
 */
final class AskWindowFixtureBuilder {

    /** The fragment every synthetic place's name contains. */
    static final String SYNTHETIC_MARK = " fell ";

    private static final long SYNTHETIC_ID_BASE = 10_000L;
    private static final int STARS = 5;

    private AskWindowFixtureBuilder() {
    }

    /**
     * Reads a fixture from the test classpath.
     *
     * @param resource the classpath resource
     * @return the parsed fixture
     * @throws IOException when it cannot be read
     */
    static JsonNode load(String resource) throws IOException {
        try (InputStream in = AskWindowFixtureBuilder.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("fixture " + resource + " is not on the test classpath");
            }
            return new ObjectMapper().readTree(in);
        }
    }

    /**
     * The window id of a fixture window.
     *
     * @param window the fixture's window node
     * @return {@code yyyy-MM-dd_sunrise} or {@code _sunset}
     */
    static String windowId(JsonNode window) {
        return AskWindowId.format(LocalDate.parse(window.get("date").asText()),
                TargetType.valueOf(window.get("type").asText()));
    }

    /**
     * The id of the window the fixture gives a BEST BET, or null when it names none.
     *
     * @param root the fixture
     * @return the window id, or null
     */
    static String bestBetWindowId(JsonNode root) {
        for (JsonNode window : root.get("windows")) {
            if (window.has("bestBet")) {
                return windowId(window);
            }
        }
        return null;
    }

    /**
     * Builds the snapshot.
     *
     * @param root the fixture
     * @return the snapshot, through the real builder with the clock at the fixture's {@code now}
     */
    static AskSnapshot snapshot(JsonNode root) {
        LocalDateTime now = LocalDateTime.parse(root.get("now").asText());
        Map<LocalDate, List<BriefingEventSummary>> byDate = new LinkedHashMap<>();
        for (JsonNode window : root.get("windows")) {
            String id = windowId(window);
            List<BriefingRegion> regions = new ArrayList<>();
            long regionIndex = 0;
            for (JsonNode region : root.get("regions")) {
                regionIndex++;
                BriefingRegion built = region(region, id, SYNTHETIC_ID_BASE * regionIndex);
                if (built != null) {
                    regions.add(built);
                }
            }
            LocalDate date = LocalDate.parse(window.get("date").asText());
            BriefingWindow served = AskFixtures.window(date.atTime(LocalTime.parse(window.get("time").asText())),
                    DisplayVerdict.valueOf(window.get("verdict").asText()),
                    window.has("best") ? window.get("best").asInt() : null, bestBet(window));
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(AskFixtures.summary(
                    TargetType.valueOf(window.get("type").asText()), served, regions.toArray(BriefingRegion[]::new)));
        }
        List<BriefingDay> days = new ArrayList<>();
        byDate.forEach((date, summaries) ->
                days.add(AskFixtures.day(date, summaries.toArray(BriefingEventSummary[]::new))));
        return AskFixtures.snapshotAt(now, AskFixtures.briefing(days, List.of()), List.of());
    }

    private static BriefingRegion region(JsonNode region, String windowId, long firstSyntheticId) {
        String name = region.get("name").asText();
        for (JsonNode stood : region.path("stoodDown")) {
            if (windowId.equals(stood.asText())) {
                return AskFixtures.blankedRegion(name, CoverageReason.STOOD_DOWN);
            }
        }
        List<BriefingSlot> slots = new ArrayList<>();
        for (JsonNode named : region.path("named")) {
            if (named.get("ratings").has(windowId)) {
                slots.add(named(named, named.get("ratings").get(windowId).asInt()));
            }
        }
        JsonNode synthetic = region.path("synthetic").path(windowId);
        long id = firstSyntheticId;
        int n = 0;
        for (int star = 1; star <= STARS; star++) {
            int count = synthetic.path("bands").path(star - 1).asInt(0);
            for (int i = 0; i < count; i++) {
                slots.add(AskFixtures.slot(id++, name + SYNTHETIC_MARK + String.format("%02d", ++n), star));
            }
        }
        for (int i = 0; i < synthetic.path("unrated").asInt(0); i++) {
            slots.add(AskFixtures.slot(id++, name + SYNTHETIC_MARK + String.format("%02d", ++n), null));
        }
        return slots.isEmpty() ? null : AskFixtures.region(name, true, slots.toArray(BriefingSlot[]::new));
    }

    private static BriefingSlot named(JsonNode slot, int rating) {
        long id = slot.get("locationId").asLong();
        String name = slot.get("name").asText();
        BriefingSlot built = slot.has("tideState")
                ? AskFixtures.coastal(id, name, rating, slot.get("tideState").asText(),
                        slot.get("tideAligned").asBoolean())
                : AskFixtures.slot(id, name, rating);
        return AskFixtures.withHeadline(built, slot.get("headline").asText());
    }

    private static BriefingWindow.Pick bestBet(JsonNode window) {
        if (!window.has("bestBet")) {
            return null;
        }
        JsonNode pick = window.get("bestBet");
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, pick.get("region").asText(),
                pick.get("location").asText(), pick.get("locationId").asLong());
    }
}
