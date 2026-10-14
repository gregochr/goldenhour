"""Parity tests for slots.py against TimeSlotUtilsTest.java."""
import os
import sys
import unittest
from datetime import datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from slots import dark_side_index, find_best_index  # noqa: E402


class FindBestIndexTest(unittest.TestCase):
    def test_sunset_prefers_slot_before(self):  # TimeSlotUtilsTest.java:20-26
        self.assertEqual(find_best_index(["2026-03-05T17:00", "2026-03-05T18:00"],
                                         datetime(2026, 3, 5, 17, 35), "SUNSET"), 0)

    def test_sunrise_prefers_slot_after(self):  # TimeSlotUtilsTest.java:32-38
        self.assertEqual(find_best_index(["2026-03-05T06:00", "2026-03-05T07:00"],
                                         datetime(2026, 3, 5, 6, 25), "SUNRISE"), 1)

    def test_fallback_nearest(self):  # TimeSlotUtilsTest.java:44-50
        self.assertEqual(find_best_index(["2026-03-05T19:00", "2026-03-05T20:00"],
                                         datetime(2026, 3, 5, 17, 35), "SUNSET"), 0)

    def test_exact_match(self):  # TimeSlotUtilsTest.java:56-61
        self.assertEqual(find_best_index(["2026-03-05T17:00", "2026-03-05T18:00"],
                                         datetime(2026, 3, 5, 17, 0), "SUNSET"), 0)

    def test_angel_sunset(self):
        times = ["2026-10-13T%02d:00" % h for h in range(14, 20)]
        self.assertEqual(find_best_index(times, datetime(2026, 10, 13, 17, 12), "SUNSET"), 3)

    def test_sunrise_fallback_nearest_all_before(self):
        self.assertEqual(find_best_index(["2026-03-05T03:00", "2026-03-05T04:00"],
                                         datetime(2026, 3, 5, 6, 0), "SUNRISE"), 1)

    def test_bad_type(self):
        with self.assertRaises(ValueError):
            find_best_index(["2026-03-05T03:00"], datetime(2026, 3, 5), "NOON")


class DarkSideTest(unittest.TestCase):
    def test_sunset_next(self):  # TimeSlotUtilsTest.java:103
        self.assertEqual(dark_side_index(5, "SUNSET"), 6)

    def test_sunrise_previous(self):  # TimeSlotUtilsTest.java:109
        self.assertEqual(dark_side_index(5, "SUNRISE"), 4)

    def test_edges_out_of_range_none(self):  # Java returns -1 / 24 (TimeSlotUtilsTest.java:115-116); port gives None
        self.assertIsNone(dark_side_index(0, "SUNRISE"))
        self.assertIsNone(dark_side_index(23, "SUNSET", n=24))
        self.assertEqual(dark_side_index(22, "SUNSET", n=24), 23)


if __name__ == "__main__":
    unittest.main()
