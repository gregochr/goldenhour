package com.gregochr.goldenhour.service.warnings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure GeoJSON polygon helpers for weather warnings: tolerant parsing of a {@code Polygon} or {@code MultiPolygon},
 * an even-odd ray-cast point-in-polygon test that honours holes, and a deterministic vertex-thinning for the map copy.
 *
 * <p>No JTS is on the classpath, so this is a small hand-written implementation. A polygon here is a list of rings;
 * the first ring is the outer boundary and any others are holes. A ring is an array of {@code {longitude, latitude}}
 * points. Malformed pieces are dropped, never thrown: a polygon whose outer ring is malformed is dropped whole (a
 * hole must never be promoted to an outer boundary), a malformed hole is dropped alone.
 */
public final class WarningGeometry {

    /** The most vertices kept on one ring of the simplified (map) geometry. */
    static final int MAX_SIMPLIFIED_RING_VERTICES = 200;

    /** The fewest distinct points a ring needs to enclose an area. */
    private static final int MIN_DISTINCT_POINTS = 3;

    /** The number of ordinates a point needs: longitude then latitude. */
    private static final int POINT_ORDINATES = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WarningGeometry() {
    }

    /**
     * Parses a GeoJSON geometry into polygons.
     *
     * @param geometry a {@code Polygon} or {@code MultiPolygon} geometry node; anything else is tolerated
     * @return the well-formed polygons, possibly empty; never null
     */
    public static List<List<double[][]>> parse(JsonNode geometry) {
        List<List<double[][]>> polygons = new ArrayList<>();
        if (geometry == null || !geometry.isObject()) {
            return polygons;
        }
        String type = geometry.path("type").asText("");
        JsonNode coordinates = geometry.path("coordinates");
        if ("MultiPolygon".equals(type) && coordinates.isArray()) {
            for (JsonNode polygonNode : coordinates) {
                addIfPresent(polygons, polygon(polygonNode));
            }
        } else if ("Polygon".equals(type)) {
            addIfPresent(polygons, polygon(coordinates));
        }
        return polygons;
    }

    /**
     * Parses stored geometry text.
     *
     * @param geometryText stored GeoJSON text, possibly null or unparseable
     * @return the well-formed polygons, possibly empty; never null
     */
    public static List<List<double[][]>> parse(String geometryText) {
        if (geometryText == null || geometryText.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return parse(MAPPER.readTree(geometryText));
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Whether a point lies inside any polygon (inside the outer ring and outside every hole).
     *
     * @param polygons  polygons from {@link #parse}
     * @param longitude the point's longitude
     * @param latitude  the point's latitude
     * @return true when covered
     */
    public static boolean contains(List<List<double[][]>> polygons, double longitude, double latitude) {
        for (List<double[][]> rings : polygons) {
            if (!inRing(rings.get(0), longitude, latitude)) {
                continue;
            }
            boolean inHole = false;
            for (int i = 1; i < rings.size() && !inHole; i++) {
                inHole = inRing(rings.get(i), longitude, latitude);
            }
            if (!inHole) {
                return true;
            }
        }
        return false;
    }

    /**
     * Thins a geometry's rings for the map: every ring keeps at most {@link #MAX_SIMPLIFIED_RING_VERTICES} points by
     * taking every n-th vertex (deterministic), and stays closed. A ring already within the limit is kept as it is.
     *
     * @param geometry a GeoJSON geometry node
     * @return {@code MultiPolygon} JSON text, or null when the geometry has no usable polygon
     */
    public static String simplify(JsonNode geometry) {
        List<List<double[][]>> polygons = parse(geometry);
        if (polygons.isEmpty()) {
            return null;
        }
        List<List<double[][]>> thinned = new ArrayList<>();
        for (List<double[][]> rings : polygons) {
            List<double[][]> thinnedRings = new ArrayList<>();
            for (double[][] ring : rings) {
                thinnedRings.add(thin(ring));
            }
            thinned.add(thinnedRings);
        }
        return MAPPER.valueToTree(new MultiPolygon("MultiPolygon", thinned)).toString();
    }

    /**
     * Reads stored geometry text into plain maps and lists, ready to serve.
     *
     * @param geometryText stored GeoJSON text, possibly null or unparseable
     * @return the geometry as a map, or null when absent or unreadable
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String geometryText) {
        if (geometryText == null || geometryText.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(geometryText, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Serialises a list of strings as JSON array text.
     *
     * @param values the strings
     * @return JSON array text
     */
    public static String toJson(List<String> values) {
        return MAPPER.valueToTree(values).toString();
    }

    /**
     * A square {@code MultiPolygon} around a point, as GeoJSON text (used for simulated warnings).
     *
     * @param longitude  centre longitude
     * @param latitude   centre latitude
     * @param halfSide   half the side length in degrees
     * @return GeoJSON text
     */
    public static String square(double longitude, double latitude, double halfSide) {
        double[][] ring = {
            {longitude - halfSide, latitude - halfSide}, {longitude + halfSide, latitude - halfSide},
            {longitude + halfSide, latitude + halfSide}, {longitude - halfSide, latitude + halfSide},
            {longitude - halfSide, latitude - halfSide}};
        List<double[][]> rings = Collections.singletonList(ring);
        return MAPPER.valueToTree(new MultiPolygon("MultiPolygon", List.of(rings))).toString();
    }

    /** The serialised shape of the simplified geometry. */
    private record MultiPolygon(String type, List<List<double[][]>> coordinates) {
    }

    private static double[][] thin(double[][] ring) {
        if (ring.length <= MAX_SIMPLIFIED_RING_VERTICES) {
            return ring;
        }
        int step = (int) Math.ceil((double) ring.length / MAX_SIMPLIFIED_RING_VERTICES);
        List<double[]> kept = new ArrayList<>();
        for (int i = 0; i < ring.length; i += step) {
            kept.add(ring[i]);
        }
        double[] first = kept.get(0);
        double[] last = kept.get(kept.size() - 1);
        if (first[0] != last[0] || first[1] != last[1]) {
            kept.add(first);
        }
        return kept.toArray(new double[0][]);
    }

    private static void addIfPresent(List<List<double[][]>> polygons, List<double[][]> polygon) {
        if (polygon != null) {
            polygons.add(polygon);
        }
    }

    private static List<double[][]> polygon(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<double[][]> rings = new ArrayList<>();
        boolean outer = true;
        for (JsonNode ringNode : node) {
            double[][] ring = ring(ringNode);
            if (ring == null && outer) {
                return null;
            }
            if (ring != null) {
                rings.add(ring);
            }
            outer = false;
        }
        return rings;
    }

    private static double[][] ring(JsonNode node) {
        if (!node.isArray() || node.size() < MIN_DISTINCT_POINTS) {
            return null;
        }
        double[][] points = new double[node.size()][];
        int i = 0;
        for (JsonNode point : node) {
            if (!point.isArray() || point.size() < POINT_ORDINATES
                    || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                return null;
            }
            points[i++] = new double[] {point.get(0).asDouble(), point.get(1).asDouble()};
        }
        return points;
    }

    /** Even-odd ray cast; works whether or not the ring repeats its first point at the end. */
    private static boolean inRing(double[][] ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0];
            double yi = ring[i][1];
            double xj = ring[j][0];
            double yj = ring[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
