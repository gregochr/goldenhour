package com.gregochr.goldenhour.service.warnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarningGeometry}: tolerant parsing, the ray cast with holes, thinning and the small helpers.
 */
class WarningGeometryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static final String SQUARE = "[[0,0],[10,0],[10,10],[0,10],[0,0]]";
    private static final String HOLE = "[[4,4],[6,4],[6,6],[4,6],[4,4]]";

    @Test
    @DisplayName("a point inside a polygon is covered and a point outside is not")
    void insideAndOutside() throws Exception {
        var polygons = WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[" + SQUARE + "]}"));

        assertThat(WarningGeometry.contains(polygons, 5, 5)).isTrue();
        assertThat(WarningGeometry.contains(polygons, 11, 5)).isFalse();
        assertThat(WarningGeometry.contains(polygons, 5, -1)).isFalse();
    }

    @Test
    @DisplayName("a point in a hole is not covered, but a point beside the hole is")
    void holeExcludesPoint() throws Exception {
        var polygons = WarningGeometry.parse(json(
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[" + SQUARE + "," + HOLE + "]]}"));

        assertThat(WarningGeometry.contains(polygons, 5, 5)).isFalse();
        assertThat(WarningGeometry.contains(polygons, 2, 2)).isTrue();
    }

    @Test
    @DisplayName("a MultiPolygon is covered when the point is in any one of its polygons")
    void multiPolygonAnyPart() throws Exception {
        var polygons = WarningGeometry.parse(json("{\"type\":\"MultiPolygon\",\"coordinates\":["
                + "[" + SQUARE + "],[[[20,20],[30,20],[30,30],[20,30],[20,20]]]]}"));

        assertThat(polygons).hasSize(2);
        assertThat(WarningGeometry.contains(polygons, 25, 25)).isTrue();
        assertThat(WarningGeometry.contains(polygons, 15, 15)).isFalse();
    }

    @Test
    @DisplayName("a ring that does not repeat its first point is still tested correctly")
    void openRing() throws Exception {
        var polygons = WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[10,0],[10,10],[0,10]]]}"));

        assertThat(WarningGeometry.contains(polygons, 5, 5)).isTrue();
        assertThat(WarningGeometry.contains(polygons, 12, 5)).isFalse();
    }

    @Test
    @DisplayName("null, non-object, unknown-type and coordinate-less geometry parse to nothing")
    void tolerantOfNonsense() throws Exception {
        assertThat(WarningGeometry.parse((JsonNode) null)).isEmpty();
        assertThat(WarningGeometry.parse(json("[1,2]"))).isEmpty();
        assertThat(WarningGeometry.parse(json("{\"type\":\"Point\",\"coordinates\":[1,2]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json("{\"type\":\"MultiPolygon\"}"))).isEmpty();
        assertThat(WarningGeometry.parse(json("{\"type\":\"MultiPolygon\",\"coordinates\":{}}"))).isEmpty();
        assertThat(WarningGeometry.parse(json("{\"type\":\"Polygon\",\"coordinates\":[]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json("{\"type\":\"Polygon\",\"coordinates\":\"x\"}"))).isEmpty();
    }

    @Test
    @DisplayName("a polygon whose outer ring is malformed is dropped whole; a malformed hole is dropped alone")
    void malformedRings() throws Exception {
        var badOuter = WarningGeometry.parse(json("{\"type\":\"Polygon\",\"coordinates\":["
                + "[[0,0],[1,1]]," + HOLE + "]}"));
        var badHole = WarningGeometry.parse(json("{\"type\":\"Polygon\",\"coordinates\":["
                + SQUARE + ",[[4,4],[5,5]]]}"));

        assertThat(badOuter).isEmpty();
        assertThat(badHole).hasSize(1);
        assertThat(badHole.get(0)).hasSize(1);
        assertThat(WarningGeometry.contains(badHole, 5, 5)).isTrue();
    }

    @Test
    @DisplayName("a ring with a non-array point, a one-ordinate point or a non-numeric ordinate is malformed")
    void malformedPoints() throws Exception {
        assertThat(WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],\"x\",[1,1],[0,0]]]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1],[1,1],[0,0]]]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[\"a\",1],[1,1],[0,0]]]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,\"b\"],[1,1],[0,0]]]}"))).isEmpty();
        assertThat(WarningGeometry.parse(json(
                "{\"type\":\"Polygon\",\"coordinates\":[\"notaring\"]}"))).isEmpty();
    }

    @Test
    @DisplayName("stored geometry text: null, blank and invalid JSON parse to nothing; valid text parses")
    void parseText() {
        assertThat(WarningGeometry.parse((String) null)).isEmpty();
        assertThat(WarningGeometry.parse("  ")).isEmpty();
        assertThat(WarningGeometry.parse("{not json")).isEmpty();
        assertThat(WarningGeometry.parse("{\"type\":\"Polygon\",\"coordinates\":[" + SQUARE + "]}")).hasSize(1);
    }

    @Test
    @DisplayName("simplify returns null for geometry with no usable polygon")
    void simplifyNothing() throws Exception {
        assertThat(WarningGeometry.simplify(json("{\"type\":\"Point\"}"))).isNull();
    }

    @Test
    @DisplayName("a small ring is served as it is, as a MultiPolygon")
    void simplifySmallRingUnchanged() throws Exception {
        String out = WarningGeometry.simplify(json("{\"type\":\"Polygon\",\"coordinates\":[" + SQUARE + "]}"));

        JsonNode node = json(out);
        assertThat(node.get("type").asText()).isEqualTo("MultiPolygon");
        assertThat(node.get("coordinates").get(0).get(0)).hasSize(5);
    }

    @Test
    @DisplayName("a long open ring is thinned to at most 200 points and closed again")
    void simplifyThinsAndCloses() throws Exception {
        String out = WarningGeometry.simplify(json(
                "{\"type\":\"Polygon\",\"coordinates\":[" + circle(400, false) + "]}"));

        JsonNode ring = json(out).get("coordinates").get(0).get(0);
        assertThat(ring.size()).isLessThanOrEqualTo(WarningGeometry.MAX_SIMPLIFIED_RING_VERTICES + 1);
        assertThat(ring.get(0)).isEqualTo(ring.get(ring.size() - 1));
    }

    @Test
    @DisplayName("a thinned ring that already lands on its closing point is not given an extra one")
    void simplifyClosedRingLandingOnLastPoint() throws Exception {
        // 201 points, step 2: indices 0, 2 ... 200, the last of which is the closing point.
        String out = WarningGeometry.simplify(json(
                "{\"type\":\"Polygon\",\"coordinates\":[" + circle(201, true) + "]}"));

        JsonNode ring = json(out).get("coordinates").get(0).get(0);
        assertThat(ring.size()).isEqualTo(101);
        assertThat(ring.get(0)).isEqualTo(ring.get(100));
    }

    @Test
    @DisplayName("thinning closes a ring whose kept last point shares the first point's longitude only")
    void simplifyClosesWhenOnlyLatitudeDiffers() throws Exception {
        StringBuilder ring = new StringBuilder("[");
        for (int i = 0; i < 400; i++) {
            ring.append(i == 0 ? "" : ",").append("[1.0,").append(i).append(".0]");
        }
        ring.append("]");

        String out = WarningGeometry.simplify(json("{\"type\":\"Polygon\",\"coordinates\":[" + ring + "]}"));

        JsonNode thinned = json(out).get("coordinates").get(0).get(0);
        assertThat(thinned.get(0)).isEqualTo(thinned.get(thinned.size() - 1));
    }

    @Test
    @DisplayName("toMap reads geometry text into plain maps, and is null for null, blank or invalid text")
    void toMap() {
        Map<String, Object> map = WarningGeometry.toMap("{\"type\":\"MultiPolygon\",\"coordinates\":[]}");

        assertThat(map).containsEntry("type", "MultiPolygon");
        assertThat(WarningGeometry.toMap(null)).isNull();
        assertThat(WarningGeometry.toMap(" ")).isNull();
        assertThat(WarningGeometry.toMap("{oops")).isNull();
    }

    @Test
    @DisplayName("toJson writes a JSON array of strings")
    void toJson() {
        assertThat(WarningGeometry.toJson(List.of("SNOW", "ICE"))).isEqualTo("[\"SNOW\",\"ICE\"]");
    }

    @Test
    @DisplayName("square builds a closed MultiPolygon that covers its centre and not a point beyond its edge")
    void square() {
        var polygons = WarningGeometry.parse(WarningGeometry.square(-1.5, 54.5, 0.25));

        assertThat(polygons).hasSize(1);
        assertThat(WarningGeometry.contains(polygons, -1.5, 54.5)).isTrue();
        assertThat(WarningGeometry.contains(polygons, -1.2, 54.5)).isFalse();
    }

    /** A circle of {@code n} points as ring text, optionally repeating the first point as the last. */
    private static String circle(int n, boolean closed) {
        int points = closed ? n - 1 : n;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            sb.append(i == 0 ? "" : ",").append("[").append(Math.cos(angle)).append(",")
                    .append(Math.sin(angle)).append("]");
        }
        if (closed) {
            sb.append(",[").append(Math.cos(0)).append(",").append(Math.sin(0)).append("]");
        }
        return sb.append("]").toString();
    }
}
