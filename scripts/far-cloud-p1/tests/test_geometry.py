"""Parity tests for geometry.py against the Java tests' expected values."""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import geometry as g  # noqa: E402

LAT, LON, AZ = 54.7753, -1.5849, 245  # DirectionalSamplingGeometryTest.java:19-21; GeoUtilsTest.java:14-15


def dist_km(p):
    """Haversine, mean radius 6371.0088 km, independent check of distance."""
    la1, lo1, la2, lo2 = map(math.radians, (LAT, LON, p[0], p[1]))
    a = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


class OffsetPointTest(unittest.TestCase):
    def test_due_north(self):  # GeoUtilsTest.java:20-23
        r = g.offset_point(LAT, LON, 0, 50_000)
        self.assertAlmostEqual(r[0], LAT + 0.449, delta=0.01)
        self.assertAlmostEqual(r[1], LON, delta=0.01)

    def test_due_east(self):  # GeoUtilsTest.java:29-32
        r = g.offset_point(LAT, LON, 90, 50_000)
        self.assertAlmostEqual(r[0], LAT, delta=0.01)
        self.assertGreater(r[1], LON)

    def test_due_south(self):  # GeoUtilsTest.java:38-41
        r = g.offset_point(LAT, LON, 180, 50_000)
        self.assertLess(r[0], LAT)
        self.assertAlmostEqual(r[1], LON, delta=0.01)

    def test_zero_distance(self):  # GeoUtilsTest.java:47-50
        r = g.offset_point(LAT, LON, 270, 0)
        self.assertAlmostEqual(r[0], LAT, delta=0.0001)
        self.assertAlmostEqual(r[1], LON, delta=0.0001)

    def test_antisolar_bearing(self):  # GeoUtilsTest.java:56-60
        for a, e in ((0, 180.0), (90, 270.0), (180, 0.0), (270, 90.0), (245, 65.0)):
            self.assertEqual(g.antisolar_bearing(a), e)

    def test_longitude_not_normalised(self):
        # Crossing the antimeridian eastward must exceed 180 (Java does not wrap).
        self.assertGreater(g.offset_point(0.0, 179.9, 90, 100_000)[1], 180.0)


class DirectionalGeometryTest(unittest.TestCase):
    def test_far_point(self):  # DirectionalSamplingGeometryTest.java:26-32
        p = g.far_point(LAT, LON, AZ)
        e = g.offset_point(LAT, LON, AZ, 226_000.0)
        self.assertAlmostEqual(dist_km(p), 226.0, delta=1.0)
        self.assertAlmostEqual(p[0], e[0], delta=1e-9)
        self.assertAlmostEqual(p[1], e[1], delta=1e-9)

    def test_antisolar_point(self):  # DirectionalSamplingGeometryTest.java:38-44
        p = g.antisolar_point(LAT, LON, AZ)
        e = g.offset_point(LAT, LON, (AZ + 180) % 360, 113_000.0)
        self.assertAlmostEqual(dist_km(p), 113.0, delta=1.0)
        self.assertAlmostEqual(p[0], e[0], delta=1e-6)
        self.assertAlmostEqual(p[1], e[1], delta=1e-6)

    def test_cone_order_and_count(self):
        # Java: bearings az-15, az, az+15 at 113 km (DirectionalSamplingGeometry.solarConeBearings);
        # index constants SOLAR_CONE_POINT_COUNT=3 (DirectionalSamplingGeometryTest.java:50-53).
        c = g.cone_points(LAT, LON, AZ)
        self.assertEqual(len(c), 3)
        for p, b in zip(c, (230, 245, 260)):
            e = g.offset_point(LAT, LON, b, 113_000.0)
            self.assertEqual(p, e)
            self.assertAlmostEqual(dist_km(p), 113.0, delta=1.0)
        # flank-low first: bearing 230 is more southerly than 260 for a westward azimuth
        self.assertLess(c[0][0], c[2][0])

    def test_cone_negative_bearing(self):
        c = g.cone_points(LAT, LON, 5)  # az-15 = -10, as in Java (no wrap)
        self.assertEqual(c[0], g.offset_point(LAT, LON, -10, 113_000.0))

    def test_probe_points(self):
        pts = g.probe_points(LAT, LON, AZ)
        for p, km in zip(pts, (165, 226, 320)):
            self.assertAlmostEqual(dist_km(p), km, delta=1.5)
        self.assertEqual(pts[1], g.far_point(LAT, LON, AZ))

    def test_transect(self):
        t = g.transect(LAT, LON, AZ, 50, 300)
        self.assertEqual(len(t), 6)
        self.assertAlmostEqual(dist_km(t[-1]), 300, delta=1.5)
        self.assertEqual(len(g.transect(LAT, LON, AZ, 40, 200)), 5)


class GridKeyTest(unittest.TestCase):
    def test_snapping(self):  # CloudPointCacheTest.java:20-25
        k = g.grid_key(54.90, -1.58)
        self.assertEqual(g.grid_key(54.9004, -1.5804), k)
        self.assertEqual(k, "549_-16")

    def test_different_cell(self):  # CloudPointCacheTest.java:32-37
        self.assertNotEqual(g.grid_key(55.50, -2.00), g.grid_key(54.90, -1.58))

    def test_java_round_edges(self):
        self.assertEqual(g.java_round(-0.5), 0)    # Python round(-0.5) == -0 -> 0 too, but...
        self.assertEqual(g.java_round(-1.5), -1)   # Python round(-1.5) == -2
        self.assertEqual(g.java_round(2.5), 3)     # Python round(2.5) == 2
        self.assertEqual(g.java_round(0.5), 1)
        self.assertEqual(g.java_round(-2.5), -2)   # Python round(-2.5) == -2 (coincides)
        self.assertEqual(g.java_round(-0.4), 0)
        self.assertEqual(g.java_round(-1.6), -2)

    def test_half_cell_boundaries(self):
        # Keys must use the same double arithmetic as Java: x / 0.1 then floor(x + 0.5).
        self.assertEqual(g.grid_key(-0.15, -0.25),
                         "%d_%d" % (g.java_round(-0.15 / 0.1), g.java_round(-0.25 / 0.1)))
        self.assertEqual(g.grid_key(0.0, -0.25), "0_-2")  # -2.5 -> -2 (Python round(-2.5) is also -2)
        self.assertEqual(g.grid_key(0.0, 0.25), "0_3")    # 2.5 -> 3 (Python round gives 2)

    def test_dedupe(self):
        pts = [(54.90, -1.58), (54.9004, -1.5804), (55.5, -2.0)]
        self.assertEqual(g.dedupe_by_cell(pts), [pts[0], pts[2]])


if __name__ == "__main__":
    unittest.main()
