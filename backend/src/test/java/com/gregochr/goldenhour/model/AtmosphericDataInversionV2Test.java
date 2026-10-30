package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.ForecastStability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The v2 inversion carrier is one appended nullable field on {@link AtmosphericData}: every {@code withX} copy
 * must carry it, and {@code withInversionV2} must change nothing else.
 */
class AtmosphericDataInversionV2Test {

    private static final InversionV2Result CARRIER = InversionV2Result.ofProfile(
            new InversionProfile(54.5, -3.1, 100.0, 100, null, List.of()));

    private static AtmosphericData base() {
        return TestAtmosphericData.builder().inversionScore(7.0).mistScore(5.5)
                .mistPotential(MistPotential.POSSIBLE).build().withInversionV2(CARRIER);
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

        assertThat(after.inversionV2()).as("carrier survives the copy").isSameAs(CARRIER);
        assertThat(differing(before, after)).containsExactlyInAnyOrder(changed);
    }

    @Test
    @DisplayName("withDirectionalCloud carries the v2 carrier and leaves every other field alone")
    void withDirectionalCloud() throws Exception {
        assertChangesOnly(d -> d.withDirectionalCloud(mock(DirectionalCloudData.class)), "directionalCloud");
    }

    @Test
    @DisplayName("withTide carries the v2 carrier and leaves every other field alone")
    void withTide() throws Exception {
        assertChangesOnly(d -> d.withTide(mock(TideSnapshot.class)), "tide");
    }

    @Test
    @DisplayName("withCloudApproach carries the v2 carrier and leaves every other field alone")
    void withCloudApproach() throws Exception {
        assertChangesOnly(d -> d.withCloudApproach(mock(CloudApproachData.class)), "cloudApproach");
    }

    @Test
    @DisplayName("withLocationOrientation carries the v2 carrier and leaves every other field alone")
    void withLocationOrientation() throws Exception {
        assertChangesOnly(d -> d.withLocationOrientation("sunrise-optimised"), "locationOrientation");
    }

    @Test
    @DisplayName("withSurge carries the v2 carrier and leaves every other field alone")
    void withSurge() throws Exception {
        assertChangesOnly(d -> d.withSurge(mock(StormSurgeBreakdown.class), 1.5, 2.5),
                "surge", "adjustedRangeMetres", "astronomicalRangeMetres");
    }

    @Test
    @DisplayName("withInversionScore carries the v2 carrier and leaves every other field alone")
    void withInversionScore() throws Exception {
        assertChangesOnly(d -> d.withInversionScore(9.0), "inversionScore");
    }

    @Test
    @DisplayName("withBluebellConditionScore carries the v2 carrier and leaves every other field alone")
    void withBluebellConditionScore() throws Exception {
        assertChangesOnly(d -> d.withBluebellConditionScore(mock(BluebellConditionScore.class)),
                "bluebellConditionScore");
    }

    @Test
    @DisplayName("withStability carries the v2 carrier and leaves every other field alone")
    void withStability() throws Exception {
        assertChangesOnly(d -> d.withStability(ForecastStability.SETTLED, "calm"), "stability", "stabilityReason");
    }

    @Test
    @DisplayName("withValleyMist carries the v2 carrier and leaves every other field alone")
    void withValleyMist() throws Exception {
        assertChangesOnly(d -> d.withValleyMist(8.0, MistPotential.LIKELY), "mistScore", "mistPotential");
    }

    @Test
    @DisplayName("withInversionV2 changes only the carrier")
    void withInversionV2() throws Exception {
        AtmosphericData before = base();
        InversionV2Result other = CARRIER.withStorage(6.0, 7.0, true);

        AtmosphericData after = before.withInversionV2(other);

        assertThat(after.inversionV2()).isSameAs(other);
        assertThat(differing(before, after)).containsExactly("inversionV2");
    }

    @Test
    @DisplayName("the constructors at today's arities read the carrier as absent")
    void olderConstructorsHaveNoCarrier() {
        AtmosphericData twentyTwo = new AtmosphericData("x", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 5.0, MistPotential.POSSIBLE);
        AtmosphericData twenty = new AtmosphericData("x", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);

        assertThat(twentyTwo.inversionV2()).isNull();
        assertThat(twentyTwo.mistScore()).isEqualTo(5.0);
        assertThat(twenty.inversionV2()).isNull();
    }

    @Test
    @DisplayName("withStorage and withCaps change only what they name")
    void resultCopies() {
        InversionV2Result stored = CARRIER.withStorage(6.0, 7.0, true);
        InversionV2Result capped = stored.withCaps("edge(8)");

        assertThat(stored.v1Score()).isEqualTo(6.0);
        assertThat(stored.stored()).isEqualTo(7.0);
        assertThat(stored.v2Drove()).isTrue();
        assertThat(stored.profile()).isSameAs(CARRIER.profile());
        assertThat(capped.caps()).isEqualTo("edge(8)");
        assertThat(capped.stored()).isEqualTo(7.0);
    }
}
