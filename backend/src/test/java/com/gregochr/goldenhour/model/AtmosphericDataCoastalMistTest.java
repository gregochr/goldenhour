package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The coastal mist carrier is one appended nullable field, LAST on {@link AtmosphericData}: every {@code withX}
 * copy must carry it, and {@code withCoastalMist} must change nothing else.
 */
class AtmosphericDataCoastalMistTest {

    private static final CoastalMistResult CARRIER = new CoastalMistResult(CoastalMistBand.LIGHT, 7_200, 0.8, 30);

    private static AtmosphericData base() {
        return TestAtmosphericData.builder().inversionScore(7.0).mistScore(5.5)
                .mistPotential(MistPotential.POSSIBLE).build().withCoastalMist(CARRIER);
    }

    /** The names of the record components whose values differ between two instances. */
    private static List<String> differing(AtmosphericData a, AtmosphericData b) throws Exception {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : AtmosphericData.class.getRecordComponents()) {
            try {
                Object left = c.getAccessor().invoke(a);
                Object right = c.getAccessor().invoke(b);
                if (left == null ? right != null : !left.equals(right)) {
                    names.add(c.getName());
                }
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }
        return names;
    }

    private static void assertChangesOnly(UnaryOperator<AtmosphericData> withX, String... changed)
            throws Exception {
        AtmosphericData before = base();

        AtmosphericData after = withX.apply(before);

        assertThat(after.coastalMist()).as("carrier survives the copy").isSameAs(CARRIER);
        assertThat(differing(before, after)).containsExactlyInAnyOrder(changed);
    }

    @Test
    @DisplayName("the carrier is the last record component, so every older positional constructor is untouched")
    void carrierIsLast() {
        RecordComponent[] components = AtmosphericData.class.getRecordComponents();

        assertThat(components[components.length - 1].getName()).isEqualTo("coastalMist");
        assertThat(components[components.length - 2].getName()).isEqualTo("inversionV2");
    }

    @Test
    @DisplayName("every withX copy method is covered by a test in this class")
    void everyWithMethodIsCovered() {
        List<String> withMethods = new ArrayList<>();
        for (Method m : AtmosphericData.class.getDeclaredMethods()) {
            if (m.getName().startsWith("with") && Modifier.isPublic(m.getModifiers())) {
                withMethods.add(m.getName());
            }
        }
        List<String> covered = new ArrayList<>();
        for (Method m : AtmosphericDataCoastalMistTest.class.getDeclaredMethods()) {
            if (m.getName().startsWith("with")) {
                covered.add(m.getName());
            }
        }

        assertThat(covered).containsExactlyInAnyOrderElementsOf(withMethods);
    }

    @Test
    @DisplayName("withDirectionalCloud carries the coastal mist carrier and leaves every other field alone")
    void withDirectionalCloud() throws Exception {
        assertChangesOnly(d -> d.withDirectionalCloud(mock(DirectionalCloudData.class)), "directionalCloud");
    }

    @Test
    @DisplayName("withTide carries the coastal mist carrier and leaves every other field alone")
    void withTide() throws Exception {
        assertChangesOnly(d -> d.withTide(mock(TideSnapshot.class)), "tide");
    }

    @Test
    @DisplayName("withCloudApproach carries the coastal mist carrier and leaves every other field alone")
    void withCloudApproach() throws Exception {
        assertChangesOnly(d -> d.withCloudApproach(mock(CloudApproachData.class)), "cloudApproach");
    }

    @Test
    @DisplayName("withLocationOrientation carries the coastal mist carrier and leaves every other field alone")
    void withLocationOrientation() throws Exception {
        assertChangesOnly(d -> d.withLocationOrientation("sunrise-optimised"), "locationOrientation");
    }

    @Test
    @DisplayName("withSurge carries the coastal mist carrier and leaves every other field alone")
    void withSurge() throws Exception {
        assertChangesOnly(d -> d.withSurge(mock(StormSurgeBreakdown.class), 1.5, 2.5),
                "surge", "adjustedRangeMetres", "astronomicalRangeMetres");
    }

    @Test
    @DisplayName("withInversionScore carries the coastal mist carrier and leaves every other field alone")
    void withInversionScore() throws Exception {
        assertChangesOnly(d -> d.withInversionScore(9.0), "inversionScore");
    }

    @Test
    @DisplayName("withBluebellConditionScore carries the coastal mist carrier and leaves every other field alone")
    void withBluebellConditionScore() throws Exception {
        assertChangesOnly(d -> d.withBluebellConditionScore(mock(BluebellConditionScore.class)),
                "bluebellConditionScore");
    }

    @Test
    @DisplayName("withStability carries the coastal mist carrier and leaves every other field alone")
    void withStability() throws Exception {
        assertChangesOnly(d -> d.withStability(ForecastStability.SETTLED, "calm"), "stability", "stabilityReason");
    }

    @Test
    @DisplayName("withValleyMist carries the coastal mist carrier and leaves every other field alone")
    void withValleyMist() throws Exception {
        assertChangesOnly(d -> d.withValleyMist(8.0, MistPotential.LIKELY), "mistScore", "mistPotential");
    }

    @Test
    @DisplayName("withInversionV2 carries the coastal mist carrier and leaves every other field alone")
    void withInversionV2() throws Exception {
        assertChangesOnly(d -> d.withInversionV2(InversionV2Result.ofProfile(null)), "inversionV2");
    }

    @Test
    @DisplayName("withCoastalMist changes only the carrier, and null clears it")
    void withCoastalMist() throws Exception {
        AtmosphericData before = base();
        CoastalMistResult other = new CoastalMistResult(CoastalMistBand.THICK, 2_000, 0.1, 90);

        AtmosphericData after = before.withCoastalMist(other);
        AtmosphericData cleared = before.withCoastalMist(null);

        assertThat(after.coastalMist()).isSameAs(other);
        assertThat(differing(before, after)).containsExactly("coastalMist");
        assertThat(cleared.coastalMist()).isNull();
        assertThat(differing(before, cleared)).containsExactly("coastalMist");
    }

    @Test
    @DisplayName("the constructors at today's arities (20, 22 and 23 arguments) read the carrier as absent")
    void olderConstructorsHaveNoCarrier() {
        AtmosphericData twentyThree = new AtmosphericData("x", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, 5.0, MistPotential.POSSIBLE,
                InversionV2Result.ofProfile(null));
        AtmosphericData twentyTwo = new AtmosphericData("x", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 5.0, MistPotential.POSSIBLE);
        AtmosphericData twenty = new AtmosphericData("x", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertThat(twentyThree.coastalMist()).isNull();
        assertThat(twentyThree.inversionV2()).isNotNull();
        assertThat(twentyTwo.coastalMist()).isNull();
        assertThat(twenty.coastalMist()).isNull();
    }
}
