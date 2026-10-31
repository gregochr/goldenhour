package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CoastalStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code bigSeas} component of {@link DailyBriefingResponse}: defaults to null, and no wither drops it. */
class DailyBriefingResponseBigSeasTest {

    private static final LocalDateTime HW = LocalDateTime.of(2026, 12, 11, 8, 0);
    private static final BigSeasDay DAY = new BigSeasDay(LocalDate.of(2026, 12, 11), HW.minusMinutes(90),
            HW.plusMinutes(90), HW, BigSeasDay.AlignedEvent.SUNRISE, 5L, BigSeasDay.GapSide.BEFORE, 4.1, 85, 9.0,
            List.of(new BigSeasStructure(1L, "Roker Pier", CoastalStructure.PIER, 4.1, 85, 9.0, 90, HW, true)), 1,
            false, null, null);

    private static DailyBriefingResponse withBigSeas() {
        return new DailyBriefingResponse(LocalDateTime.of(2026, 12, 10, 6, 0), "h", List.of(), List.of(),
                null, null, false, null, List.of(), List.of(), null, null, null, null, List.of(DAY));
    }

    @Test
    @DisplayName("every shorter constructor, including the 16-argument one, leaves bigSeas null")
    void constructorsDefaultToNull() {
        DailyBriefingResponse sixteen = new DailyBriefingResponse(LocalDateTime.of(2026, 12, 10, 6, 0), "h",
                List.of(), List.of(), null, null, false, null, List.of(), List.of(), null, null, null,
                null);
        DailyBriefingResponse twelve = new DailyBriefingResponse(LocalDateTime.of(2026, 12, 10, 6, 0), "h",
                List.of(), List.of(), null, null, false, null, List.of(), List.of());

        assertThat(sixteen.bigSeas()).isNull();
        assertThat(twelve.bigSeas()).isNull();
    }

    @Test
    @DisplayName("every existing wither carries bigSeas through unchanged")
    void everyWitherCarriesIt() {
        DailyBriefingResponse base = withBigSeas();
        List<BigSeasDay> expected = List.of(DAY);

        assertThat(base.withDays(List.of()).bigSeas()).isEqualTo(expected);
        assertThat(base.withMovement(List.of(), LocalDateTime.of(2026, 12, 9, 6, 0)).bigSeas())
                .isEqualTo(expected);
        assertThat(base.withPlan(List.of(), List.of()).bigSeas()).isEqualTo(expected);
        assertThat(base.withBestBets(List.of(), null).bigSeas()).isEqualTo(expected);
        assertThat(base.withLiveOverlays(null, null, List.of()).bigSeas()).isEqualTo(expected);
    }

    @Test
    @DisplayName("withBigSeas replaces only bigSeas and can clear it")
    void withBigSeasReplacesOnlyThatField() {
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 12, 10, 6, 0), "headline",
                List.of(), List.of(), null, null, true, "Opus", List.of(), List.of("X"), null, true,
                List.of(), LocalDateTime.of(2026, 12, 9, 6, 0));

        DailyBriefingResponse attached = base.withBigSeas(List.of(DAY));

        assertThat(attached.bigSeas()).containsExactly(DAY);
        assertThat(attached).usingRecursiveComparison().ignoringFields("bigSeas").isEqualTo(base);
        assertThat(attached.withBigSeas(null).bigSeas()).isNull();
    }
}
