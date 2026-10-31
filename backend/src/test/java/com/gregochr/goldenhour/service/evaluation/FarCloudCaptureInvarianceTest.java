package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.BluebellConditionScore;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.service.WeatherTriageEvaluator;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the far-point values recorded for far-cloud-lid-plan P2 (mid, high and the dark-side
 * hour) and the eight far-point pressure-level cloud values (T7) can never reach a prompt or triage.
 * Each state is built twice: once with the recorded components null, once with every component
 * outside the originally prompted seven set to a loud value, tried at 0, 50 and 95. Every prompt builder entry
 * point and the triage evaluator must give identical output.
 * Positive controls prove the test would notice a leak: the markers the prompt does produce are
 * present, and the one prompted far field still moves the message.
 */
class FarCloudCaptureInvarianceTest {

    private static final Set<String> PROMPTED = Set.of(
            "solarLowCloudPercent", "solarMidCloudPercent", "solarHighCloudPercent",
            "antisolarLowCloudPercent", "antisolarMidCloudPercent", "antisolarHighCloudPercent",
            "farSolarLowCloudPercent");

    /** Values for every recorded component on the loud variant: none, middling and extreme. */
    private static final int[] LOUD_VALUES = {0, 50, 95};

    /** States as {solarLow, farLow}: plain, THIN STRIP, FAR CORRIDOR, and one that triage stands down. */
    private static final List<int[]> STATES = List.of(
            new int[] {10, 5}, new int[] {70, 20}, new int[] {70, 65}, new int[] {85, 20});

    private final WeatherTriageEvaluator triage = new WeatherTriageEvaluator();

    // ---- positive controls ----

    @Test
    void thinStripStateProducesItsMarkerInTheUserMessage() {
        assertTrue(LowHauxleyFixture.userMessage(quiet(70, 20)).contains("[THIN STRIP"));
    }

    @Test
    void farCorridorStateProducesItsMarkerInTheUserMessage() {
        assertTrue(LowHauxleyFixture.userMessage(quiet(70, 65)).contains("[FAR CORRIDOR ALSO CLOUDY"));
    }

    @Test
    void changingOnlyTheFarLowReadingChangesTheUserMessage() {
        assertNotEquals(LowHauxleyFixture.userMessage(quiet(10, 5)),
                LowHauxleyFixture.userMessage(quiet(10, 40)));
    }

    @Test
    void loudVariantReallyDiffersInTheRecordedComponents() throws ReflectiveOperationException {
        assertNotEquals(quiet(70, 20).directionalCloud(), loud(70, 20, 95).directionalCloud());
        int unprompted = 0;
        for (RecordComponent c : DirectionalCloudData.class.getRecordComponents()) {
            if (PROMPTED.contains(c.getName())) {
                continue;
            }
            unprompted++;
            assertNull(c.getAccessor().invoke(quiet(70, 20).directionalCloud()),
                    c.getName() + " must be null on the quiet variant");
            assertEquals(95, c.getAccessor().invoke(loud(70, 20, 95).directionalCloud()),
                    c.getName() + " must carry the loud value on the loud variant");
        }
        assertTrue(unprompted >= 13, "T1 five, T7 six levels, cirrus two");
    }

    @Test
    void triagePositiveControl_quietStandDownStateIsAStandDownAndQuietClearStateIsNot() {
        // WeatherTriageEvaluator stands down when solar low cloud exceeds 80%: 85 does, 10 does not.
        assertTrue(triage.evaluate(quiet(85, 20)).isPresent());
        assertTrue(triage.evaluate(quiet(10, 5)).isEmpty());
    }

    // ---- invariance ----

    @Test
    void sharedPromptBuilderOutputIgnoresRecordedFarFields() {
        assertBuilderInvariant(new PromptBuilder());
    }

    @Test
    void coastalPromptBuilderOutputIgnoresRecordedFarFields() {
        assertBuilderInvariant(new CoastalPromptBuilder());
    }

    private void assertBuilderInvariant(PromptBuilder builder) {
        for (int[] s : STATES) {
            for (int v : LOUD_VALUES) {
                AtmosphericData base = quiet(s[0], s[1]);
                AtmosphericData lou = loud(s[0], s[1], v);
                String label = "state solarLow=" + s[0] + " farLow=" + s[1] + " loud=" + v;
                assertEquals(builder.buildUserMessage(base), builder.buildUserMessage(lou), label);
                assertEquals(
                        builder.buildUserMessage(base, base.surge(),
                                base.adjustedRangeMetres(), base.astronomicalRangeMetres()),
                        builder.buildUserMessage(lou, lou.surge(),
                                lou.adjustedRangeMetres(), lou.astronomicalRangeMetres()),
                        label + " (surge overload)");
            }
        }
    }

    @Test
    void woodlandPromptBuilderOutputIgnoresRecordedFarFields() {
        // Guards against a future leak: this builder does not read directionalCloud today.
        WoodlandPromptBuilder builder = new WoodlandPromptBuilder(new WoodlandVerdictEvaluator());
        for (int[] s : STATES) {
            for (int v : LOUD_VALUES) {
                assertEquals(builder.buildUserMessage(quiet(s[0], s[1])),
                        builder.buildUserMessage(loud(s[0], s[1], v)),
                        "state " + s[0] + "/" + s[1] + " loud=" + v);
            }
        }
    }

    @Test
    void bluebellPromptBuilderOutputIgnoresRecordedFarFields() {
        // Guards against a future leak: this builder does not read directionalCloud today.
        BluebellPromptBuilder builder = new BluebellPromptBuilder();
        BluebellConditionScore score = new BluebellConditionScore(
                8, true, true, true, false, true, true, BluebellExposure.WOODLAND,
                "Misty and still");
        for (int[] s : STATES) {
            for (int v : LOUD_VALUES) {
                assertEquals(builder.buildUserMessage(quiet(s[0], s[1]).withBluebellConditionScore(score)),
                        builder.buildUserMessage(loud(s[0], s[1], v).withBluebellConditionScore(score)),
                        "state " + s[0] + "/" + s[1] + " loud=" + v);
            }
        }
    }

    @Test
    void triageResultIgnoresRecordedFarFields() {
        for (int[] s : STATES) {
            for (int v : LOUD_VALUES) {
                assertEquals(triage.evaluate(quiet(s[0], s[1])), triage.evaluate(loud(s[0], s[1], v)),
                        "state solarLow=" + s[0] + " farLow=" + s[1] + " loud=" + v);
            }
        }
    }

    // ---- data builders ----

    private static AtmosphericData quiet(int solarLow, int farLow) {
        return LowHauxleyFixture.atmosphericData().withDirectionalCloud(record(solarLow, farLow, null));
    }

    private static AtmosphericData loud(int solarLow, int farLow, int value) {
        return LowHauxleyFixture.atmosphericData().withDirectionalCloud(record(solarLow, farLow, value));
    }

    /**
     * Builds a record through its canonical constructor found by reflection. The seven prompted
     * components come from the fixture except solar low and far low; every other component takes
     * {@code unprompted} (null, or a loud value), so a future component is covered with no edit.
     */
    private static DirectionalCloudData record(int solarLow, int farLow, Integer unprompted) {
        DirectionalCloudData fixture = LowHauxleyFixture.atmosphericData().directionalCloud();
        RecordComponent[] components = DirectionalCloudData.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
        Object[] args = new Object[components.length];
        try {
            for (int i = 0; i < components.length; i++) {
                String name = components[i].getName();
                if (name.equals("solarLowCloudPercent")) {
                    args[i] = solarLow;
                } else if (name.equals("farSolarLowCloudPercent")) {
                    args[i] = farLow;
                } else if (PROMPTED.contains(name)) {
                    args[i] = components[i].getAccessor().invoke(fixture);
                } else {
                    args[i] = unprompted;
                }
            }
            Constructor<DirectionalCloudData> ctor = DirectionalCloudData.class.getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build DirectionalCloudData reflectively", e);
        }
    }
}
