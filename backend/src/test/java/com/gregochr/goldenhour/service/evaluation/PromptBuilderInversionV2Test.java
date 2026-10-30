package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.InversionClearance;
import com.gregochr.goldenhour.model.InversionRegime;
import com.gregochr.goldenhour.model.InversionV2Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v2 "Warm layer:" line in the user message (inversion-v2-plan Phase 1b, point 9). It appears only when
 * v2 drove the stored score; the fallback path is the old "Measured reversal" line, byte for byte.
 */
class PromptBuilderInversionV2Test {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    /** A slot whose stored inversion score is {@code score}, with a 925 hPa reading so the old line can print. */
    private static TestAtmosphericData slot(double score) {
        return TestAtmosphericData.builder().inversionScore(score).temperature(3.0).dewPoint(2.0)
                .temperature925hPa(5.4);
    }

    private static InversionV2Result drove(InversionClearance clearance) {
        return new InversionV2Result(null, 6.0, 8.0, 8.0, InversionRegime.ELEVATED, 682.0, 1130.0, 4.6,
                152, 868, clearance, "-", true);
    }

    @Test
    @DisplayName("v2 drove, viewpoint clear: a standalone Warm layer line replaces the 925 hPa reversal line")
    void warmLayerClear() {
        AtmosphericData data = slot(8.0).build().withInversionV2(drove(InversionClearance.CLEAR));

        String message = promptBuilder.buildUserMessage(data);

        assertThat(message).contains("CLOUD INVERSION FORECAST:")
                .contains("Warm layer: +4.6°C from about 682 m to 1130 m above a valley at 152 m; "
                        + "this viewpoint (868 m) is clear of it.")
                .doesNotContain("Measured reversal");
    }

    @Test
    @DisplayName("v2 drove, viewpoint at the edge: the line says so")
    void warmLayerEdge() {
        AtmosphericData data = slot(8.0).build().withInversionV2(drove(InversionClearance.EDGE));

        assertThat(promptBuilder.buildUserMessage(data)).contains("this viewpoint (868 m) is at its edge.");
    }

    @Test
    @DisplayName("the score line still reads from the stored whole-number score")
    void scoreLineUnchanged() {
        AtmosphericData data = slot(9.0).build().withInversionV2(drove(InversionClearance.CLEAR));

        assertThat(promptBuilder.buildUserMessage(data)).contains("Score: 9/10 (Strong Cloud Inversion Potential)");
    }

    @Test
    @DisplayName("fallback path: with no carrier the old Measured reversal line prints exactly as before")
    void fallbackWithNoCarrierIsTheOldLine() {
        String message = promptBuilder.buildUserMessage(slot(8.0).build());

        assertThat(message).contains("Measured reversal: +2.4°C between the surface and ~760m (925 hPa)")
                .doesNotContain("Warm layer");
    }

    @Test
    @DisplayName("fallback path: a carrier where v2 did not drive leaves the message byte-identical")
    void carrierThatDidNotDriveChangesNothing() {
        AtmosphericData plain = slot(8.0).build();
        InversionV2Result notDriving = new InversionV2Result(null, null, null, null, null, null, null, null,
                null, null, null, null, false);

        String without = promptBuilder.buildUserMessage(plain);
        String with = promptBuilder.buildUserMessage(plain.withInversionV2(notDriving));
        String withScoredButOld = promptBuilder.buildUserMessage(plain.withInversionV2(
                drove(InversionClearance.CLEAR).withStorage(8.0, 8.0, false)));

        assertThat(with).isEqualTo(without);
        assertThat(withScoredButOld).isEqualTo(without);
    }

    @Test
    @DisplayName("a driving carrier without its figures prints no Warm layer line (and no stale reversal)")
    void incompleteCarrierPrintsNoLine() {
        InversionV2Result noFigures = new InversionV2Result(null, 6.0, 8.0, 8.0, InversionRegime.NONE, null, null,
                null, 152, 868, InversionClearance.NO_LAYER, "none(6)", true);

        String message = promptBuilder.buildUserMessage(slot(8.0).build().withInversionV2(noFigures));

        assertThat(message).contains("CLOUD INVERSION FORECAST:").doesNotContain("Warm layer")
                .doesNotContain("Measured reversal");
    }

    @Test
    @DisplayName("below the MODERATE band no inversion block prints, whatever v2 says")
    void belowModerateNoBlock() {
        AtmosphericData data = slot(6.0).build().withInversionV2(drove(InversionClearance.INSIDE));

        assertThat(promptBuilder.buildUserMessage(data)).doesNotContain("CLOUD INVERSION FORECAST")
                .doesNotContain("Warm layer");
    }

    /** A driving carrier with the night fields: night 75, mid 75, with Option B and the cap-bound flags as given. */
    private static InversionV2Result nightDrove(boolean nightCapBound, boolean optionB, boolean optionBBound) {
        return new InversionV2Result(null, 6.0, 8.0, 8.0, InversionRegime.ELEVATED, 682.0, 1130.0, 4.6,
                152, 868, InversionClearance.CLEAR, "night-mid(8)", true, 75.0, 75.0, optionB, nightCapBound,
                optionBBound);
    }

    @Test
    @DisplayName("the night cap bound: the Warm layer line gains '; under a cloudy night'")
    void nightCapClause() {
        AtmosphericData data = slot(8.0).build().withInversionV2(nightDrove(true, false, false));

        assertThat(promptBuilder.buildUserMessage(data)).contains("Warm layer: +4.6°C from about 682 m to 1130 m "
                + "above a valley at 152 m; this viewpoint (868 m) is clear of it; under a cloudy night.");
    }

    @Test
    @DisplayName("Option B made an unconfirmed layer an edge: the line gains '; after a clear, calm night'")
    void optionBClause() {
        InversionV2Result carrier = new InversionV2Result(null, 6.0, 8.0, 8.0, InversionRegime.SURFACE_BASED, 100.0,
                450.0, 2.5, 100, 450, InversionClearance.EDGE, "night-fog(8)", true, 5.0, 0.0, true, false, true);
        AtmosphericData data = slot(8.0).build().withInversionV2(carrier);

        assertThat(promptBuilder.buildUserMessage(data)).contains("this viewpoint (450 m) is at its edge; "
                + "after a clear, calm night.");
    }

    @Test
    @DisplayName("a night that bound nothing leaves the line byte-identical, whatever the night figures say")
    void unboundNightChangesNothing() {
        AtmosphericData plain = slot(8.0).build().withInversionV2(drove(InversionClearance.CLEAR));
        AtmosphericData withNightFigures = slot(8.0).build().withInversionV2(nightDrove(false, true, false));
        AtmosphericData sameFigures = slot(8.0).build().withInversionV2(new InversionV2Result(null, 6.0, 8.0, 8.0,
                InversionRegime.ELEVATED, 682.0, 1130.0, 4.6, 152, 868, InversionClearance.CLEAR, "-", true,
                75.0, 75.0, true, false, false));

        assertThat(promptBuilder.buildUserMessage(withNightFigures)).isEqualTo(promptBuilder.buildUserMessage(plain));
        assertThat(promptBuilder.buildUserMessage(sameFigures)).isEqualTo(promptBuilder.buildUserMessage(plain));
    }
}
