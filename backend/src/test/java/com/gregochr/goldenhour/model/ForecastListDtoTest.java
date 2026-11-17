package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ForecastListDto}'s two copy methods, which the as-of-event serve path uses to put the star a
 * reader actually saw on a row whose own figures are a different forecast's. Every component is
 * populated with a distinct value so a swapped or dropped positional argument cannot hide behind a
 * pair of nulls.
 */
class ForecastListDtoTest {

    private static final ForecastListDto FULL = new ForecastListDto(
            7L, "Hartside", BigDecimal.valueOf(54.7), BigDecimal.valueOf(-2.4),
            LocalDate.of(2026, 10, 14), TargetType.SUNSET, LocalDateTime.of(2026, 10, 14, 9, 0),
            LocalDateTime.of(2026, 10, 14, 17, 0), 241, 3, 61, 54, TriageReason.HIGH_CLOUD,
            "the why", "the triage message", 11.5, 9.5, BigDecimal.valueOf(4.2), 270,
            BigDecimal.valueOf(0.3), 35);

    @Test
    @DisplayName("withRating replaces the rating and nothing else")
    void withRatingChangesOnlyTheRating() {
        ForecastListDto copy = FULL.withRating(5);

        assertThat(copy.rating()).isEqualTo(5);
        assertThat(copy).usingRecursiveComparison().ignoringFields("rating").isEqualTo(FULL);
    }

    @Test
    @DisplayName("withRating(null) is an unscored star with the scores left alone")
    void withRatingNullLeavesTheScores() {
        ForecastListDto copy = FULL.withRating(null);

        assertThat(copy.rating()).isNull();
        assertThat(copy.fierySkyPotential()).isEqualTo(61);
        assertThat(copy.goldenHourPotential()).isEqualTo(54);
    }

    @Test
    @DisplayName("unscored clears the rating and both scores and keeps every other component, the "
            + "stand-down reason included")
    void unscoredClearsOnlyTheStarAndTheScores() {
        ForecastListDto copy = FULL.unscored();

        assertThat(copy.rating()).isNull();
        assertThat(copy.fierySkyPotential()).isNull();
        assertThat(copy.goldenHourPotential()).isNull();
        assertThat(copy).usingRecursiveComparison()
                .ignoringFields("rating", "fierySkyPotential", "goldenHourPotential").isEqualTo(FULL);
        assertThat(copy.triageReason()).isEqualTo(TriageReason.HIGH_CLOUD);
    }

    private static final ForecastListDto WARNED = FULL.withWarningIds(List.of("W-1", "W-2"));

    @Test
    @DisplayName("withWarningIds sets the ids and changes nothing else; null reads as none")
    void withWarningIdsChangesOnlyTheIds() {
        assertThat(WARNED.warningIds()).containsExactly("W-1", "W-2");
        assertThat(WARNED).usingRecursiveComparison().ignoringFields("warningIds").isEqualTo(FULL);
        assertThat(WARNED.withWarningIds(null).warningIds()).isNull();
        assertThat(FULL.warningIds()).isNull();
    }

    @Test
    @DisplayName("every copy carries the warning ids through: re-rated, unscored and gated rows keep them")
    void everyCopyKeepsTheIds() {
        assertThat(WARNED.withRating(2).warningIds()).containsExactly("W-1", "W-2");
        assertThat(WARNED.unscored().warningIds()).containsExactly("W-1", "W-2");
        ForecastListDto gated = WARNED.forGated();
        assertThat(gated.gated()).isTrue();
        assertThat(gated.rating()).isNull();
        assertThat(gated.warningIds()).as("a warning is not a rating").containsExactly("W-1", "W-2");
    }

    @Test
    @DisplayName("the 22-component form defaults the ids to null and keeps gated")
    void twentyTwoComponentFormDefaultsIds() {
        ForecastListDto gated = new ForecastListDto(7L, "Hartside", null, null, LocalDate.of(2026, 10, 14),
                TargetType.SUNSET, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, Boolean.TRUE);

        assertThat(gated.gated()).isTrue();
        assertThat(gated.warningIds()).isNull();
    }
}
