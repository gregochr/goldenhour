package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DispositionCategory}. The forward-compatibility guarantee
 * is the central concern: stored disposition strings written by future
 * deployments (e.g. the reserved {@code SKIPPED_NO_REFRESH_NEEDED}) must be
 * parseable without breaking older readers, and the parser must reject
 * unknown values rather than throw.
 */
class DispositionCategoryTest {

    @Test
    @DisplayName("fromString: every defined enum value round-trips via name")
    void fromString_everyValueRoundTrips() {
        for (DispositionCategory category : DispositionCategory.values()) {
            assertThat(DispositionCategory.fromString(category.name()))
                    .contains(category);
        }
    }

    @Test
    @DisplayName("fromString: unknown value returns empty (forward compat)")
    void fromString_unknownValue_returnsEmpty() {
        // The bar from the spec: "the enum/column accepts an unknown future value
        // (NO_REFRESH_NEEDED) without breaking read or display."
        assertThat(DispositionCategory.fromString("FUTURE_CATEGORY_THAT_DOES_NOT_EXIST_YET"))
                .isEmpty();
    }

    @Test
    @DisplayName("fromString: null input returns empty without NPE")
    void fromString_nullInput_returnsEmpty() {
        assertThat(DispositionCategory.fromString(null)).isEmpty();
    }

    @Test
    @DisplayName("fromString: empty string returns empty")
    void fromString_emptyString_returnsEmpty() {
        assertThat(DispositionCategory.fromString("")).isEmpty();
    }

    @Test
    @DisplayName("fromString: case-sensitive — lowercase is unknown")
    void fromString_caseSensitive_lowercase() {
        assertThat(DispositionCategory.fromString("evaluated")).isEmpty();
    }

    @Test
    @DisplayName("reserved SKIPPED_NO_REFRESH_NEEDED present in enum but not populated yet")
    void reservedIntradayCategory_present() {
        // Spec: "the enum/schema must accommodate a future SKIPPED_NO_REFRESH_NEEDED
        // (the intraday "settled, no refresh needed" case) without a schema change"
        Optional<DispositionCategory> reserved =
                DispositionCategory.fromString("SKIPPED_NO_REFRESH_NEEDED");
        assertThat(reserved).contains(DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
    }
}
