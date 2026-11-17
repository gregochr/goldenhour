package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.model.BriefingWindow.WarningPlaceCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BriefingWindow}'s warning overlay fields survive every positional copy, because the overlay is attached
 * last and the role shapers rebuild the window after it (docs/engineering/weather-warnings-plan.md section 8).
 */
class BriefingWindowWarningTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 11, 6, 0);
    private static final List<WarningPlaceCount> COUNTS = List.of(new WarningPlaceCount("W-1", "Dales", 2, 5));

    private static BriefingWindow plain() {
        return new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 4, null, null, List.of(), null);
    }

    @Test
    @DisplayName("a window built the old way carries no warnings, and withWarnings sets both fields only")
    void withWarningsSetsOnlyTheOverlay() {
        BriefingWindow warned = plain().withWarnings(List.of("W-1"), COUNTS);

        assertThat(plain().warningIds()).isNull();
        assertThat(plain().warningPlaceCounts()).isNull();
        assertThat(warned.warningIds()).containsExactly("W-1");
        assertThat(warned.warningPlaceCounts()).isEqualTo(COUNTS);
        assertThat(warned).usingRecursiveComparison().ignoringFields("warningIds", "warningPlaceCounts")
                .isEqualTo(plain());
    }

    @Test
    @DisplayName("every wither keeps the warning fields: tide, tide facts, badges, best rating and pick")
    void everyWitherKeepsTheOverlay() {
        BriefingWindow warned = plain().withWarnings(List.of("W-1"), COUNTS);

        List<BriefingWindow> copies = List.of(warned.withTide(null), warned.withTideFacts(null),
                warned.withBadges(List.of()), warned.withBestRating(null), warned.withPick(null));

        assertThat(copies).allSatisfy(c -> {
            assertThat(c.warningIds()).containsExactly("W-1");
            assertThat(c.warningPlaceCounts()).isEqualTo(COUNTS);
        });
        assertThat(warned.withBestRating(null).bestRating()).isNull();
    }

    @Test
    @DisplayName("the eight-component form keeps the tide facts and no warnings")
    void eightComponentForm() {
        BriefingWindow w = new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 4, null, null, List.of(), null,
                List.of());

        assertThat(w.tideFacts()).isEmpty();
        assertThat(w.warningIds()).isNull();
    }
}
