"""Tests for sun.py (NOAA algorithm)."""
import os
import sys
import unittest
from datetime import date, datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import sun  # noqa: E402

ANGEL = (54.914, -1.589)


def minutes(dt):
    return dt.hour * 60 + dt.minute + dt.second / 60.0


class AngelTest(unittest.TestCase):
    def test_sunset(self):
        s = sun.sunset_utc(date(2026, 10, 13), *ANGEL)
        self.assertTrue(17 * 60 + 7 <= minutes(s) <= 17 * 60 + 13, s)

    def test_azimuth_at_sunset(self):
        s = sun.sunset_utc(date(2026, 10, 13), *ANGEL)
        _, az = sun.sun_position(s, *ANGEL)
        self.assertAlmostEqual(az, 257.4, delta=0.7)

    def test_elevations(self):
        e17, _ = sun.sun_position(datetime(2026, 10, 13, 17, 0), *ANGEL)
        e16, _ = sun.sun_position(datetime(2026, 10, 13, 16, 0), *ANGEL)
        self.assertAlmostEqual(e17, 0.9, delta=0.4)
        self.assertAlmostEqual(e16, 8.9, delta=0.4)


class GreenwichTest(unittest.TestCase):
    def test_solstice(self):
        d = date(2024, 6, 21)
        rise = sun.sunrise_utc(d, 51.4769, 0.0)
        sset = sun.sunset_utc(d, 51.4769, 0.0)
        self.assertAlmostEqual(minutes(rise), 3 * 60 + 43, delta=3)
        self.assertAlmostEqual(minutes(sset), 20 * 60 + 21, delta=3)

    def test_noon_equinox_elevation(self):
        e, az = sun.sun_position(datetime(2024, 3, 20, 12, 7), 51.4769, 0.0)
        self.assertAlmostEqual(e, 90 - 51.4769, delta=1.0)
        self.assertAlmostEqual(az, 180.0, delta=3.0)

    def test_polar_night_none(self):
        self.assertIsNone(sun.sunrise_utc(date(2024, 12, 21), 80.0, 0.0))


if __name__ == "__main__":
    unittest.main()
