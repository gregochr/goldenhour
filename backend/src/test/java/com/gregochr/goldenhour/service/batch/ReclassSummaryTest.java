package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReclassSummary}.
 */
class ReclassSummaryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 30);

    private static CandidateDisposition disposition(DispositionCategory category, String detail) {
        return new CandidateDisposition(1L, "Bamburgh", DATE, TargetType.SUNRISE, 1, category, detail);
    }

    @Test
    @DisplayName("a settled skip and an evening deferral are counted apart")
    void from_countsEveningDeferralApartFromSettledSkip() {
        ReclassSummary summary = ReclassSummary.from(List.of(
                disposition(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED,
                        "settled, and two further looks are guaranteed before the event"),
                disposition(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED,
                        IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON),
                disposition(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED,
                        IntradayCandidateCollectionStrategy.EVENING_DEFERRAL_REASON),
                disposition(DispositionCategory.EVALUATED, null),
                disposition(DispositionCategory.FORCE_EVALUATED, null),
                disposition(DispositionCategory.SKIPPED_TRIAGED, "overcast")));

        assertThat(summary.considered()).isEqualTo(6);
        assertThat(summary.settledSkipped()).isEqualTo(1);
        assertThat(summary.deferredToEvening()).isEqualTo(2);
        assertThat(summary.evaluated()).isEqualTo(2);
    }

    @Test
    @DisplayName("the detail names the evening deferral rather than calling it settled-skipped")
    void detail_namesEveningDeferral() {
        ReclassSummary summary = new ReclassSummary(10, 1, 4, 5);

        assertThat(summary.detail()).isEqualTo(
                "10 considered, 1 settled-skipped, 4 evaluated, 5 left for tonight's evening refresh");
    }

    @Test
    @DisplayName("the detail is unchanged when nothing was deferred")
    void detail_unchangedWithoutDeferral() {
        assertThat(new ReclassSummary(3, 1, 2).detail())
                .isEqualTo("3 considered, 1 settled-skipped, 2 evaluated");
    }
}
