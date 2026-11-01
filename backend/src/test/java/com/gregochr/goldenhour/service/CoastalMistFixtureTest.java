package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link CoastalMistCalculator} against the 1,000 Boulmer sunrise mornings of the plan's evidence
 * ({@code docs/engineering/coastal-mist-data/}) as an EXACT confusion matrix, not percentage bars: with 38 fog
 * mornings a percentage cannot carry a +/-15 point interval, so a change to any threshold must show here as a
 * changed count and be reviewed as a deliberate retune.
 *
 * <p>Inputs are the model columns ({@code model_vis_km} to metres, {@code model_t_minus_td_c},
 * {@code model_low_cloud_pct}); the observed class is collapsed to CLEAR / MIST (MIST-on, MIST-off, MIST-calm)
 * / FOG (FOG-on, FOG-off). The CSV stores the model values rounded to 0.1, so the counts can sit a row or two
 * away from the planner's reference computed on unrounded values; this test pins what the code computes from
 * the committed file.
 */
class CoastalMistFixtureTest {

    private static final String CSV = "/coastal-mist/boulmer-sunrise-vs-model-2023-2025.csv";

    private static final int ROWS = 1000;

    private enum Observed { CLEAR, MIST, FOG }

    /** One CSV row reduced to what the calculator and the matrix need. */
    private record Row(Observed observed, int visibilityMetres, double gapC, int lowCloud) {
    }

    private static Observed collapse(String observedClass) {
        if (observedClass.equals("CLEAR")) {
            return Observed.CLEAR;
        }
        if (observedClass.startsWith("FOG")) {
            return Observed.FOG;
        }
        assertThat(observedClass).startsWith("MIST");
        return Observed.MIST;
    }

    private static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = CoastalMistFixtureTest.class.getResourceAsStream(CSV)) {
            assertThat(in).as("fixture " + CSV).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] header = reader.readLine().split(",");
            int observed = indexOf(header, "observed_class");
            int vis = indexOf(header, "model_vis_km");
            int low = indexOf(header, "model_low_cloud_pct");
            int gap = indexOf(header, "model_t_minus_td_c");
            String line;
            while ((line = reader.readLine()) != null) {
                // Only the first three and the model columns matter, and none of them contains a comma.
                String[] f = line.split(",", -1);
                rows.add(new Row(collapse(f[observed]),
                        (int) Math.round(Double.parseDouble(f[vis]) * 1000.0),
                        Double.parseDouble(f[gap]),
                        (int) Math.round(Double.parseDouble(f[low]))));
            }
        }
        return rows;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("no column " + name);
    }

    private static Map<CoastalMistBand, Integer> countsFor(List<Row> rows, Observed observed) {
        Map<CoastalMistBand, Integer> counts = new EnumMap<>(CoastalMistBand.class);
        for (CoastalMistBand band : CoastalMistBand.values()) {
            counts.put(band, 0);
        }
        for (Row row : rows) {
            if (row.observed() != observed) {
                continue;
            }
            // The gap column is already T minus Td: temperature = gap, dew point = 0.
            CoastalMistBand band = CoastalMistCalculator.calculate(TestAtmosphericData.builder()
                    .visibility(row.visibilityMetres()).temperature(row.gapC()).dewPoint(0.0)
                    .lowCloud(row.lowCloud()).build()).band();
            counts.merge(band, 1, Integer::sum);
        }
        return counts;
    }

    @Test
    @DisplayName("the 1,000 Boulmer mornings band into the pinned confusion matrix")
    void confusionMatrixIsPinned() throws IOException {
        List<Row> rows = rows();
        assertThat(rows).hasSize(ROWS);

        Map<CoastalMistBand, Integer> clear = countsFor(rows, Observed.CLEAR);
        Map<CoastalMistBand, Integer> mist = countsFor(rows, Observed.MIST);
        Map<CoastalMistBand, Integer> fog = countsFor(rows, Observed.FOG);

        assertThat(clear).containsEntry(CoastalMistBand.NONE, 751)
                .containsEntry(CoastalMistBand.LIGHT, 33)
                .containsEntry(CoastalMistBand.THICK, 40);
        assertThat(mist).containsEntry(CoastalMistBand.NONE, 53)
                .containsEntry(CoastalMistBand.LIGHT, 43)
                .containsEntry(CoastalMistBand.THICK, 42);
        assertThat(fog).containsEntry(CoastalMistBand.NONE, 6)
                .containsEntry(CoastalMistBand.LIGHT, 1)
                .containsEntry(CoastalMistBand.THICK, 31);
    }

    @Test
    @DisplayName("the observed classes are 824 clear, 138 mist and 38 fog, so the matrix covers every row")
    void observedClassTotals() throws IOException {
        List<Row> rows = rows();

        assertThat(rows.stream().filter(r -> r.observed() == Observed.CLEAR).count()).isEqualTo(824);
        assertThat(rows.stream().filter(r -> r.observed() == Observed.MIST).count()).isEqualTo(138);
        assertThat(rows.stream().filter(r -> r.observed() == Observed.FOG).count()).isEqualTo(38);
    }
}
