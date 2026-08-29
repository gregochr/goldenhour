package com.gregochr.goldenhour.model.comingup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link ComingUpConditionOccurrence}. */
class ComingUpConditionOccurrenceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 30);

    @Test
    @DisplayName("a promoted occurrence carries the entry id it resolves to")
    void promotedOccurrenceCarriesEntryId() {
        ComingUpConditionOccurrence occurrence = new ComingUpConditionOccurrence(
                DAY, "30 Aug", "4.8 m", "Spring tide", 5.4, null, "promoted",
                "spring-tide:2026-08-30:2026-09-01");

        assertThat(occurrence.date()).isEqualTo(DAY);
        assertThat(occurrence.dateLabel()).isEqualTo("30 Aug");
        assertThat(occurrence.valueLabel()).isEqualTo("4.8 m");
        assertThat(occurrence.label()).isEqualTo("Spring tide");
        assertThat(occurrence.bits()).isEqualTo(5.4);
        assertThat(occurrence.reason()).isNull();
        assertThat(occurrence.status()).isEqualTo("promoted");
        assertThat(occurrence.entryId()).isEqualTo("spring-tide:2026-08-30:2026-09-01");
    }

    @Test
    @DisplayName("a held-back occurrence carries no entry id and may carry a max-rule reason")
    void heldBackOccurrenceCarriesReasonNoEntryId() {
        ComingUpConditionOccurrence occurrence = new ComingUpConditionOccurrence(
                DAY, "30 Aug", "4.1 m", "King tide", 4.2, "max w/ supermoon", "heldBack", null);

        assertThat(occurrence.reason()).isEqualTo("max w/ supermoon");
        assertThat(occurrence.status()).isEqualTo("heldBack");
        assertThat(occurrence.entryId()).isNull();
    }

    @Test
    @DisplayName("label is null for a condition with only one occurrence kind (dust, inversion)")
    void labelIsNullWhenNotApplicable() {
        ComingUpConditionOccurrence occurrence = new ComingUpConditionOccurrence(
                DAY, "30 Aug", "AOD 0.55", null, 4.2, null, "heldBack", null);

        assertThat(occurrence.label()).isNull();
    }
}
