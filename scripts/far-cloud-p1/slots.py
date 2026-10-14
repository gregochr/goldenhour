"""Exact port of ``TimeSlotUtils.findBestIndex`` and ``darkSideIndex``."""
from __future__ import annotations

from datetime import datetime
from typing import Optional, Sequence


def _parse(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%dT%H:%M")


def _seconds_between(a: datetime, b: datetime) -> int:
    """Java ``ChronoUnit.SECONDS.between(a, b)`` for whole-second naive datetimes."""
    return int((b - a).total_seconds())


def find_best_index(times: Sequence[str], event: datetime, target_type: str) -> int:
    """SUNSET: latest slot <= event; SUNRISE: earliest slot >= event; first wins ties;
    fallback is the absolute nearest slot (first wins). ``event`` is naive UTC."""
    if target_type not in ("SUNSET", "SUNRISE"):
        raise ValueError(target_type)
    best_idx, best_diff = -1, None
    for i, t in enumerate(times):
        diff = _seconds_between(_parse(t), event)  # >0: slot before event
        valid = diff >= 0 if target_type == "SUNSET" else diff <= 0
        ad = abs(diff)
        if valid and (best_diff is None or ad < best_diff):
            best_diff, best_idx = ad, i
    if best_idx == -1:
        best_idx, min_diff = 0, None
        for i, t in enumerate(times):
            d = abs(_seconds_between(_parse(t), event))
            if min_diff is None or d < min_diff:
                min_diff, best_idx = d, i
    return best_idx


def dark_side_index(best_idx: int, target_type: str, n: Optional[int] = None) -> Optional[int]:
    """+1 for SUNSET, -1 for SUNRISE. Out of range (<0, or >= n when n given) gives None."""
    idx = best_idx + 1 if target_type == "SUNSET" else best_idx - 1
    if idx < 0 or (n is not None and idx >= n):
        return None
    return idx
