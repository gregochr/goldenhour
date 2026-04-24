package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory.CacheKey;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CacheKeyFactoryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 23);

    @Test
    void buildProducesCanonicalFormat() {
        String key = CacheKeyFactory.build("North East", DATE, TargetType.SUNRISE);
        assertThat(key).isEqualTo("North East|2026-04-23|SUNRISE");
    }

    @Test
    void buildThrowsOnNullRegion() {
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeyFactory.build(null, DATE, TargetType.SUNRISE));
    }

    @Test
    void buildThrowsOnNullDate() {
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeyFactory.build("North East", null, TargetType.SUNRISE));
    }

    @Test
    void buildThrowsOnNullTargetType() {
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeyFactory.build("North East", DATE, null));
    }

    @Test
    void buildRejectsRegionContainingSeparator() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CacheKeyFactory.build("North|East", DATE, TargetType.SUNRISE))
                .withMessageContaining("|")
                .withMessageContaining("North|East");
    }

    @Test
    void parseReturnsStructuredKey() {
        CacheKey parsed = CacheKeyFactory.parse("North East|2026-04-23|SUNRISE");
        assertThat(parsed.regionName()).isEqualTo("North East");
        assertThat(parsed.date()).isEqualTo(DATE);
        assertThat(parsed.targetType()).isEqualTo(TargetType.SUNRISE);
    }

    @Test
    void parseRoundTripsBuildOutput() {
        String built = CacheKeyFactory.build("Lake District", DATE, TargetType.SUNSET);
        CacheKey parsed = CacheKeyFactory.parse(built);
        assertThat(parsed)
                .isEqualTo(new CacheKey("Lake District", DATE, TargetType.SUNSET));
    }

    @Test
    void parseThrowsOnNullKey() {
        assertThatNullPointerException()
                .isThrownBy(() -> CacheKeyFactory.parse(null));
    }

    @Test
    void parseThrowsOnTwoParts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CacheKeyFactory.parse("North East|2026-04-23"))
                .withMessageContaining("expected 3 parts");
    }

    @Test
    void parseThrowsOnFourParts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CacheKeyFactory.parse("a|b|c|d"))
                .withMessageContaining("expected 3 parts");
    }

    @Test
    void parseThrowsOnInvalidDate() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CacheKeyFactory.parse("North East|not-a-date|SUNRISE"))
                .withMessageContaining("Malformed cache key");
    }

    @Test
    void parseThrowsOnUnknownTargetType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CacheKeyFactory.parse("North East|2026-04-23|NOPE"))
                .withMessageContaining("Malformed cache key");
    }

    @Test
    void parsePreservesEmptyRegionSegment() {
        CacheKey parsed = CacheKeyFactory.parse("|2026-04-23|SUNRISE");
        assertThat(parsed.regionName()).isEmpty();
        assertThat(parsed.date()).isEqualTo(DATE);
        assertThat(parsed.targetType()).isEqualTo(TargetType.SUNRISE);
    }
}
