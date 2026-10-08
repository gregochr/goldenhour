package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan's P1 acceptance measurement: what serving {@code window.tideFacts} costs on a
 * production-shaped briefing (five days; 225 sunrise slots with 59 coastal and 182 sunset slots
 * with 27 coastal, every day), through a Jackson 3 {@code JsonMapper}, raw and gzipped, plus the
 * projector's time. The figures are printed so they can be quoted; the thresholds are the plan's
 * (raw growth at most 300 KB and 25%, gzip growth at most 40 KB, projector under 20 ms).
 *
 * <p>The projector time asserted is the best of many warmed runs, so a loaded machine cannot fail
 * it on scheduling noise alone.
 */
class WindowTideFactsPayloadTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 8);
    private static final int REGIONS = 6;
    private static final int DAYS = 5;

    private static final String[] NAMES = {"Bamburgh Castle", "Dunstanburgh Castle", "Saltburn Pier",
        "Whitby Abbey", "Hartlepool Headland", "St Mary's Lighthouse", "Craster Harbour",
        "Cresswell Beach", "Robin Hood's Bay", "Roker Pier"};

    private static BriefingSlot slot(long id, boolean coastal, int day, boolean sunrise) {
        String name = NAMES[(int) (id % NAMES.length)] + " " + id;
        BriefingSlot.TideInfo tide = BriefingSlot.TideInfo.NONE;
        if (coastal) {
            boolean aligned = id % 3 != 0;
            String state = new String[] {"HIGH", "MID", "LOW"}[(int) (id % 3)];
            String direction = id % 2 == 0 ? "RISING" : "FALLING";
            String offset = TideWording.offsetPhrase(sunrise ? 25 + id % 90 : -(40 + id % 120),
                    sunrise ? "sunrise" : "sunset");
            String height = TideWording.metres(2.0 + (id % 30) / 10.0);
            String fit = TideWording.tideFitPhrase(aligned, Set.of(TideType.HIGH, TideType.MID),
                    state, direction, offset, LocalDateTime.of(2026, 10, 8, 6, 30), height,
                    TideWording.metres(5.1));
            tide = new BriefingSlot.TideInfo(state, aligned,
                    LocalDateTime.of(2026, 10, 8 + day, 5, 40), new BigDecimal("4.9"), false,
                    false, null, "Waxing gibbous", false, 25, "HW", aligned, offset,
                    0.1 + (id % 9) / 10.0, direction, height,
                    aligned ? null : "wants higher", fit, 0.2 + (id % 8) / 10.0);
        }
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(
                (int) (id % 100), new BigDecimal("0.1"), 24000, 71, 9.4, 7.1, 3,
                new BigDecimal("4.2"), (int) (id % 90), (int) (id % 70));
        return new BriefingSlot(id, name, LocalDateTime.of(2026, 10, 8 + day, sunrise ? 6 : 17, 40),
                Verdict.GO, weather, tide, List.of("Clear horizon", "Low wind"), null,
                3 + (int) (id % 3), 3, 60, 55,
                "Broken mid cloud should catch the first colour; low cloud is thin at the horizon.",
                DisplayVerdict.MAYBE, "Colour likely", false, null);
    }

    private static DailyBriefingResponse briefing(boolean withFacts) {
        List<BriefingDay> days = new ArrayList<>();
        long id = 1;
        for (int d = 0; d < DAYS; d++) {
            List<BriefingEventSummary> summaries = new ArrayList<>();
            for (boolean sunrise : new boolean[] {true, false}) {
                int total = sunrise ? 225 : 182;
                int coastal = sunrise ? 59 : 27;
                List<List<BriefingSlot>> perRegion = new ArrayList<>();
                for (int r = 0; r < REGIONS; r++) {
                    perRegion.add(new ArrayList<>());
                }
                for (int i = 0; i < total; i++) {
                    perRegion.get(i % REGIONS).add(slot(id++, i < coastal, d, sunrise));
                }
                List<BriefingRegion> regions = new ArrayList<>();
                for (int r = 0; r < REGIONS; r++) {
                    regions.add(new BriefingRegion("Region " + r, Verdict.GO, "Clear at 12 of 40",
                            List.of(), perRegion.get(r), 9.4, 7.1, 4.2, 3,
                            "Gloss headline for the region", "Gloss detail sentence.",
                            DisplayVerdict.MAYBE, 20, null, false, null));
                }
                summaries.add(new BriefingEventSummary(
                        sunrise ? TargetType.SUNRISE : TargetType.SUNSET, regions, List.of()));
            }
            days.add(new BriefingDay(START.plusDays(d), summaries));
        }
        DailyBriefingResponse raw = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0),
                "headline", days, List.of(), null, null, false, false, 0, null, List.of(),
                List.of());
        Map<PlanWindowProjector.WindowKey, List<LocationTideFact>> facts = withFacts
                ? WindowTideFactProjector.project(days) : Map.of();
        return PlanWindowProjector.apply(raw, LocalDateTime.of(2026, 10, 8, 4, 0), Map.of(), facts);
    }

    /** The P5 shape: the same response with every served slot's tide removed. */
    private static DailyBriefingResponse stripped(DailyBriefingResponse response) {
        List<BriefingDay> days = response.days().stream().map(day -> day.withEventSummaries(
                day.eventSummaries().stream().map(es -> es.withRegions(es.regions().stream()
                        .map(r -> r.withSlots(r.slots().stream().map(sl -> sl.withTide(null))
                                .toList())).toList())
                        .withUnregioned(es.unregioned().stream().map(sl -> sl.withTide(null))
                                .toList())).toList())).toList();
        return response.withDays(days);
    }

    private static int gzipSize(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.size();
    }

    @Test
    @DisplayName("P1 acceptance: payload growth and projector time stay inside the plan's thresholds")
    void payloadAndProjectorWithinThresholds() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        DailyBriefingResponse without = briefing(false);
        DailyBriefingResponse with = briefing(true);

        byte[] rawWithout = mapper.writeValueAsBytes(without);
        byte[] rawWith = mapper.writeValueAsBytes(with);
        long rawGrowth = rawWith.length - rawWithout.length;
        long gzGrowth = gzipSize(rawWith) - gzipSize(rawWithout);
        double pct = 100.0 * rawGrowth / rawWithout.length;

        List<BriefingDay> days = without.days();
        for (int i = 0; i < 100; i++) {
            WindowTideFactProjector.project(days);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            WindowTideFactProjector.project(days);
            best = Math.min(best, System.nanoTime() - t0);
        }
        double ms = best / 1_000_000.0;
        int facts = with.days().stream().flatMap(d -> d.eventSummaries().stream())
                .mapToInt(es -> es.window().tideFacts() == null ? 0
                        : es.window().tideFacts().size()).sum();

        System.out.printf("PAYLOAD facts=%d raw_without=%d raw_with=%d growth=%d (%.1f%%) "
                        + "gzip_without=%d gzip_with=%d gzip_growth=%d projector_best_ms=%.3f%n",
                facts, rawWithout.length, rawWith.length, rawGrowth, pct, gzipSize(rawWithout),
                gzipSize(rawWith), gzGrowth, ms);

        byte[] rawStripped = mapper.writeValueAsBytes(stripped(with));
        System.out.printf("PAYLOAD_P5 before_P1_raw=%d after_P1_raw=%d after_P5_raw=%d "
                        + "(vs before P1: %+d, %+.1f%%) before_P1_gzip=%d after_P1_gzip=%d "
                        + "after_P5_gzip=%d (vs before P1: %+d)%n",
                rawWithout.length, rawWith.length, rawStripped.length,
                rawStripped.length - rawWithout.length,
                100.0 * (rawStripped.length - rawWithout.length) / rawWithout.length,
                gzipSize(rawWithout), gzipSize(rawWith), gzipSize(rawStripped),
                gzipSize(rawStripped) - gzipSize(rawWithout));
        assertThat(rawStripped.length).as("P5 ends smaller than before P1")
                .isLessThan(rawWithout.length);
        assertThat(new String(rawStripped, java.nio.charset.StandardCharsets.UTF_8))
                .as("no slot tide key survives, facts remain")
                .doesNotContain("\"heightAboveP95\"").contains("\"tideFacts\"");

        assertThat(facts).isEqualTo(DAYS * (59 + 27));
        assertThat(rawGrowth).as("raw growth bytes").isLessThanOrEqualTo(300 * 1024);
        assertThat(pct).as("raw growth percent").isLessThanOrEqualTo(25.0);
        assertThat(gzGrowth).as("gzip growth bytes").isLessThanOrEqualTo(40 * 1024);
        assertThat(ms).as("projector ms").isLessThan(20.0);
    }
}
