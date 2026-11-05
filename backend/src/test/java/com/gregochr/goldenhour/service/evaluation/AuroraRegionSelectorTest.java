package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link AuroraRegionSelector}: the ranking (clear count, mean stars, darkness, name)
 * and the scores it must ignore because they carry no usable region.
 */
@ExtendWith(MockitoExtension.class)
class AuroraRegionSelectorTest {

    @Mock
    private AuroraStateCache auroraStateCache;

    private AuroraRegionSelector selector;

    @BeforeEach
    void setUp() {
        selector = new AuroraRegionSelector(auroraStateCache);
    }

    private static AuroraForecastScore score(String region, int stars, int cloudPercent, Integer bortle) {
        LocationEntity location = LocationEntity.builder()
                .name("loc").lat(55.0).lon(-2.0).bortleClass(bortle)
                .region(RegionEntity.builder().name(region).build())
                .build();
        return new AuroraForecastScore(location, stars, AlertLevel.MODERATE, cloudPercent, "s", "d");
    }

    private void cached(AuroraForecastScore... scores) {
        when(auroraStateCache.getCachedScores()).thenReturn(new ArrayList<>(Arrays.asList(scores)));
    }

    @Test
    @DisplayName("no cached scores at all gives no region")
    void nullScores_noRegion() {
        when(auroraStateCache.getCachedScores()).thenReturn(null);

        assertThat(selector.bestAuroraRegion()).isNull();
    }

    @Test
    @DisplayName("an empty score list gives no region")
    void emptyScores_noRegion() {
        when(auroraStateCache.getCachedScores()).thenReturn(List.of());

        assertThat(selector.bestAuroraRegion()).isNull();
    }

    @Test
    @DisplayName("scores with no location, no region, a null name or a blank name are all skipped")
    void unusableScores_noRegion() {
        LocationEntity noRegion = LocationEntity.builder().name("x").lat(1.0).lon(1.0).build();
        cached(null,
                new AuroraForecastScore(null, 3, AlertLevel.MODERATE, 10, "s", "d"),
                new AuroraForecastScore(noRegion, 3, AlertLevel.MODERATE, 10, "s", "d"),
                score(null, 3, 10, 2),
                score("   ", 3, 10, 2));

        assertThat(selector.bestAuroraRegion()).isNull();
    }

    @Test
    @DisplayName("unusable scores are ignored and the usable region is still returned")
    void unusableScoresIgnored_usableRegionReturned() {
        cached(null, score(null, 5, 0, 1), score("Northumberland", 3, 40, 3));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Northumberland");
    }

    @Test
    @DisplayName("the region with more clear locations wins even with fewer stars")
    void moreClearLocations_wins() {
        cached(score("Cumbria", 2, 49, 4), score("Cumbria", 2, 10, 4),
                score("Durham", 5, 50, 1), score("Durham", 5, 80, 1));

        // Cumbria: 2 locations below 50% cloud; Durham: 0 (50 is not clear).
        assertThat(selector.bestAuroraRegion()).isEqualTo("Cumbria");
    }

    @Test
    @DisplayName("equal clear counts fall back to the higher mean star rating")
    void equalClear_higherMeanStarsWins() {
        cached(score("Cumbria", 2, 10, 1), score("Cumbria", 3, 10, 1),
                score("Durham", 4, 10, 6), score("Durham", 4, 10, 6));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Durham");
    }

    @Test
    @DisplayName("equal clear counts and stars fall back to the darker (lower Bortle) region")
    void equalStars_darkerRegionWins() {
        cached(score("Cumbria", 4, 10, 5), score("Durham", 4, 10, 2));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Durham");
    }

    @Test
    @DisplayName("a region whose locations have no Bortle class ranks as the brightest")
    void unknownBortle_rankedBrightest() {
        cached(score("Aberdeen", 4, 10, null), score("Borders", 4, 10, 8));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Borders");
    }

    @Test
    @DisplayName("the darkest Bortle class in a region is the one that counts")
    void darkestBortleInRegion_counts() {
        cached(score("Cumbria", 4, 10, 7), score("Cumbria", 4, 10, 2),
                score("Durham", 4, 10, 3), score("Durham", 4, 10, 3));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Cumbria");
    }

    @Test
    @DisplayName("a full tie is broken alphabetically by region name")
    void fullTie_alphabetical() {
        cached(score("Yorkshire", 4, 10, 3), score("Cumbria", 4, 10, 3));

        assertThat(selector.bestAuroraRegion()).isEqualTo("Cumbria");
    }
}
