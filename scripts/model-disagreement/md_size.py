"""T4: size the question. Reads the export and the cached fetch results (no network) and writes out/report.md.

Plan: docs/engineering/model-disagreement-sizing-plan.md sections 2 (question, decision rule) and 3 (lead).

Does "other models see cloud" predict the mid/high cloud ERA5 shows at the horizon when the stored
forecast (production's UKMO-led ``best_match``) read zero? The signal S2 is "at least two of ICON, GFS,
Meteo-France have M >= 30, all three voting"; ECMWF is excluded from it (ERA5 is ECMWF's own reanalysis).

Order of the report: dedup counts and funnel, the ALIGNMENT CONTROL (read first, section 3), the decision
table on the lead-fair population P' with full P beside it, lead bands, descriptive tables, baselines, the
observer secondary, and the worked examples (the trigger and training cases, never part of the decision).

Resolutions where the plan is silent or self-contradictory are listed in the report's "Notes" section.
Standard library only.
"""
from __future__ import annotations

import argparse
import csv
import os
import random
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date as _date
from datetime import timedelta
from typing import Callable, Dict, List, Optional, Sequence, Tuple

import md_common as mc
import md_sample

# ---- pre-registered decision constants (plan section 2). Never retuned after seeing data.
PRECISION_MIN, SEPARATION_MIN, RECALL_MIN, FLAG_MAX = 0.50, 0.15, 0.25, 0.40
MIN_ARM_UNITS, MIN_S2_DATES, MIN_PAIRS = 30, 10, 20
ALIGN_MAX_SHARE = 0.20
LEAD_BANDS = [("< 6 h", 0.0, 6.0), ("6-12 h", 6.0, 12.0), ("> 12 h", 12.0, 1e9)]
BLOCKS = (3, 5, 7)                  # 5 decides; 3 and 7 are sensitivity
DECISION_BLOCK = 5
DRAWS = mc.stats.DEFAULT_DRAWS      # 2,000
SEED = mc.stats.DEFAULT_SEED
MAX_DROPPED = mc.stats.MAX_DROPPED_FRACTION


def no_network(url: str):
    """The report never fetches: reaching the network is a bug."""
    raise AssertionError("md_size.py must not touch the network: %s" % url)


# ------------------------------------------------------------------------------ records
@dataclass
class Rec:
    """A unit with its fetched model values."""
    u: mc.Unit
    y: Optional[bool]
    M: Dict[str, Optional[int]] = field(default_factory=dict)       # cone M per model (None = not voting)
    PL: Dict[str, Optional[int]] = field(default_factory=dict)      # pressure-level value per model
    OBS: Dict[str, Optional[int]] = field(default_factory=dict)     # observer-point M per model
    weight: float = 1.0

    @property
    def s2(self) -> Optional[bool]:
        """The decision signal: >= 2 of ICON, GFS, Meteo-France at M >= 30, all three voting."""
        return mc.votes(self.M)

    def flag(self, model: str) -> Optional[bool]:
        """One model's M >= 30, None when it abstains."""
        v = self.M.get(model)
        return None if v is None else v >= mc.THRESH

    @property
    def lead_fair(self) -> bool:
        """P': refetched UKMO and best_match both vote and both read M < 10."""
        a, b = self.M.get(mc.UKMO), self.M.get(mc.BEST)
        return a is not None and b is not None and a < mc.LEAD_FAIR_MAX and b < mc.LEAD_FAIR_MAX

    @property
    def stored_nonzero(self) -> bool:
        """The stored-only predictor: any non-zero stored observer, far or pressure-level mid/high."""
        return any(v is not None and v > 0 for v in self.u.stored_extra)


def band_of(lead_h: float) -> str:
    """The lead band label for a lead in hours."""
    for label, lo, hi in LEAD_BANDS:
        if lo <= lead_h < hi:
            return label
    return LEAD_BANDS[-1][0]


def gather(client: mc.om.Client, units: Sequence[mc.Unit], plan: Dict[str, Dict[str, mc.Coord]],
           weights: Optional[Dict[int, float]] = None) -> List[Rec]:
    """Read every model's cached cone points for each unit (the client is dry-run: a coordinate that was
    never fetched is an empty Point, which makes that model abstain rather than read 0)."""
    memo: Dict[tuple, mc.om.Point] = {}

    def point(u: mc.Unit, model: str, c: mc.Coord) -> mc.om.Point:
        try:
            rc = mc.rep_coord(plan, u, c)
        except KeyError:
            rc = c
        k = (u.event_date, model, rc)
        if k not in memo:
            memo[k] = client.get(mc.KIND, [rc], mc.HOURLY, u.event_date, u.event_date, models=model)[0]
        return memo[k]

    out = []
    for u in units:
        rec = Rec(u=u, y=mc.era5_y(u), weight=(weights or {}).get(u.eval_id, 1.0))
        cone = mc.unit_cone(u)
        for m in mc.MODELS:
            pts = [point(u, m, c) for c in cone]
            hour = next((mc.slot_hour(u, p) for p in pts if p.hours), None)
            rec.M[m] = mc.model_value(pts, hour) if hour else None
            rec.PL[m] = mc.pressure_value(pts, hour) if hour else None
            op = point(u, m, mc.om.round_coord(u.lat, u.lon))
            ohour = mc.slot_hour(u, op)
            rec.OBS[m] = mc.observer_value(op, ohour) if ohour else None
        out.append(rec)
    return out


# ------------------------------------------------------------------------------ counts and metrics
def counts(recs: Sequence[Rec], sig: Callable[[Rec], Optional[bool]] = lambda r: r.s2,
           out: Callable[[Rec], Optional[bool]] = lambda r: r.y) -> Tuple[int, int, int, int]:
    """(n, flagged, flagged and Y, Y) over the units where the signal and outcome are defined."""
    n = s = sy = y = 0
    for r in recs:
        f, o = sig(r), out(r)
        if f is None or o is None:
            continue
        n += 1
        s += f
        y += o
        sy += f and o
    return n, s, sy, y


def ratio(a: int, b: int) -> Optional[float]:
    """a / b, None when b is 0."""
    return a / b if b else None


def metrics(c: Tuple[int, int, int, int]) -> Dict[str, Optional[float]]:
    """Precision P(Y|S), separation P(Y|S) - P(Y|not S), recall P(S|Y), flag rate P(S)."""
    n, s, sy, y = c
    p1, p0 = ratio(sy, s), ratio(y - sy, n - s)
    return {"precision": p1, "separation": None if p1 is None or p0 is None else p1 - p0,
            "recall": ratio(sy, y), "flag": ratio(s, n), "p_not": p0}


def _sum(ms: Sequence[Tuple[int, ...]]) -> Tuple[int, ...]:
    return tuple(sum(col) for col in zip(*ms))


def f_precision(ms):
    """Precision from per-month (n, s2, s2y, y) tuples."""
    n, s, sy, y = _sum(ms)
    return ratio(sy, s)


def f_separation(ms):
    """P(Y|S2) - P(Y|not S2) from per-month tuples."""
    return metrics(_sum(ms))["separation"]


def f_recall(ms):
    """P(S2|Y) from per-month tuples."""
    n, s, sy, y = _sum(ms)
    return ratio(sy, y)


def f_mantel_haenszel(ms):
    """Mantel-Haenszel pooled within-month risk difference: sum(w_m d_m) / sum(w_m), w_m = n1 n0 / n.
    Months missing an arm carry no weight, so S2 cannot win just by landing in the cloudier months."""
    num = den = 0.0
    for n, s, sy, y in ms:
        n0 = n - s
        if s and n0:
            w = s * n0 / n
            num += w * (sy / s - (y - sy) / n0)
            den += w
    return num / den if den else None


def _diff(prec_cols: Tuple[int, int]):
    a, b = prec_cols

    def f(ms):
        t = _sum(ms)
        s2, s2y = t[0], t[1]
        cmp_n, cmp_y = t[a], t[b]
        if not s2 or not cmp_n:
            return None
        return s2y / s2 - cmp_y / cmp_n
    return f


# Criterion 5 counters per unit: (s2, s2&y, ukmo, ukmo&y, best_match, best_match&y)
f_diff_ukmo = _diff((2, 3))
f_diff_best = _diff((4, 5))


def calendar_days(dates: Sequence[str]) -> List[str]:
    """Every ISO day from the first to the last of ``dates`` inclusive."""
    ds = sorted(_date.fromisoformat(d) for d in set(dates))
    return [(ds[0] + timedelta(days=i)).isoformat() for i in range((ds[-1] - ds[0]).days + 1)] if ds else []


class MonthBootstrap:
    """Moving-block bootstrap by calendar day, blocks resampled WITHIN each month (plan section 2): each
    month keeps its own day count, and the draw's per-month counters are handed to the statistics, so
    the within-month criterion sees the same resample as the pooled ones. Non-circular blocks, a trimmed
    last block, as ``stats.bootstrap_counts``."""

    def __init__(self, by_month: Dict[str, Dict[str, Tuple[int, ...]]]):
        self.months = sorted(by_month)
        self.width = len(next(iter(next(iter(by_month.values())).values()))) if by_month else 0
        self.prep = {}
        zero = (0,) * self.width
        for m in self.months:
            days = calendar_days(list(by_month[m]))
            prefix = [[0] * (len(days) + 1) for _ in range(self.width)]
            for k, d in enumerate(days):
                c = by_month[m].get(d, zero)
                for i in range(self.width):
                    prefix[i][k + 1] = prefix[i][k] + c[i]
            self.prep[m] = (len(days), prefix)

    def run(self, fns: Dict[str, Callable], block_days: int = DECISION_BLOCK, draws: int = DRAWS,
            seed: int = SEED) -> Dict[str, Tuple[List[float], int]]:
        """name -> (defined draws, number dropped because the statistic was undefined)."""
        rng = random.Random(seed)
        vals: Dict[str, List[float]] = {k: [] for k in fns}
        dropped = {k: 0 for k in fns}
        for _ in range(draws):
            per_month = []
            for m in self.months:
                n, prefix = self.prep[m]
                b = max(1, min(block_days, n))
                starts = n - b + 1
                tot = [0] * self.width
                got = 0
                while got < n:
                    s = rng.randrange(starts)
                    take = min(b, n - got)
                    for i in range(self.width):
                        tot[i] += prefix[i][s + take] - prefix[i][s]
                    got += take
                per_month.append(tuple(tot))
            for k, f in fns.items():
                v = f(per_month)
                if v is None:
                    dropped[k] += 1
                else:
                    vals[k].append(v)
        return {k: (vals[k], dropped[k]) for k in fns}


def by_month_days(recs: Sequence[Rec], contrib: Callable[[Rec], Tuple[int, ...]]
                  ) -> Dict[str, Dict[str, Tuple[int, ...]]]:
    """month -> date -> summed counters, for the bootstrap."""
    out: Dict[str, Dict[str, Tuple[int, ...]]] = defaultdict(dict)
    for r in recs:
        c = contrib(r)
        d = out[r.u.month]
        d[r.u.date] = tuple(a + b for a, b in zip(d.get(r.u.date, (0,) * len(c)), c))
    return dict(out)


def contrib_main(r: Rec) -> Tuple[int, ...]:
    """(n, S2, S2 and Y, Y) for one unit with a defined signal and outcome."""
    f, o = r.s2, r.y
    return (1, int(f), int(f and o), int(o))


def contrib_cmp(r: Rec) -> Tuple[int, ...]:
    """(S2, S2&Y, UKMO>=30, UKMO>=30&Y, best_match>=30, best_match>=30&Y) for criterion 5."""
    f, o = bool(r.s2), bool(r.y)
    u, b = bool(r.flag(mc.UKMO)), bool(r.flag(mc.BEST))
    return (int(f), int(f and o), int(u), int(u and o), int(b), int(b and o))


# ------------------------------------------------------------------------------ alignment (section 3)
def alignment(P: Sequence[Rec]) -> dict:
    """Per event and lead band: the share of P where refetched best_match reads M >= 30 (the gate), with
    P1's median |stored - refetched| beside it. The stored value is 0 by construction of P, so the
    median |delta| is just the median refetched M and is near 0 by design. Gate: the pooled share, or any
    event's share with >= 20 pairs, above 20%."""
    rows = []
    for ev in ("SUNRISE", "SUNSET", "ALL"):
        for band in [b[0] for b in LEAD_BANDS] + ["ALL"]:
            sel = [r for r in P if (ev == "ALL" or r.u.ttype == ev) and (band == "ALL" or band_of(r.u.lead_h) == band)
                   and r.M.get(mc.BEST) is not None]
            hi = sum(1 for r in sel if r.M[mc.BEST] >= mc.THRESH)
            med = statistics.median([r.M[mc.BEST] for r in sel]) if sel else None
            rows.append({"event": ev, "band": band, "pairs": len(sel), "hi": hi,
                         "share": ratio(hi, len(sel)), "median_delta": med})
    pooled = next(r for r in rows if r["event"] == "ALL" and r["band"] == "ALL")
    fired = []
    if pooled["pairs"] >= MIN_PAIRS and pooled["share"] > ALIGN_MAX_SHARE:
        fired.append("pooled")
    for r in rows:
        if r["band"] == "ALL" and r["event"] != "ALL" and r["pairs"] >= MIN_PAIRS and r["share"] > ALIGN_MAX_SHARE:
            fired.append(r["event"].lower())
    return {"rows": rows, "pairs": pooled["pairs"], "pooled_share": pooled["share"], "fired": fired,
            "gate": bool(fired)}


# ------------------------------------------------------------------------------ the decision
@dataclass
class Criterion:
    """One line of the decision table."""
    name: str
    rule: str
    estimate: str
    bound: str
    sens: str
    status: str            # pass | fail | inconclusive


def _lb(res: Tuple[List[float], int], draws: int) -> Tuple[Optional[float], bool]:
    """(95% lower bound, usable). Unusable when no draw is defined or more than 5% were dropped
    (an arm empty in the resample): the interval would be conditioned on the survivors."""
    vals, dropped = res
    if not vals or dropped / float(draws) > MAX_DROPPED:
        return None, False
    iv = mc.stats.percentile_interval(vals)
    return iv[0], True


def _crit(name, rule, est_txt, lb, usable, ok_fn, fmt, sens_txt) -> Criterion:
    if not usable:
        return Criterion(name, rule, est_txt, "n/a (undefined or >5% of draws dropped)", sens_txt, "inconclusive")
    return Criterion(name, rule, est_txt, fmt(lb), sens_txt, "pass" if ok_fn(lb) else "fail")


def decide(label: str, dec: Sequence[Rec], full_p: Sequence[Rec], pairs: int, gate: bool,
           draws: int = DRAWS, seed: int = SEED, blocks: Sequence[int] = BLOCKS) -> dict:
    """Pass / fail / inconclusive for one decision population (plan section 2).

    ``dec`` are the units that decide (all three voters present); ``full_p`` is full P, on which criterion 5
    is evaluated because the comparators are zero by construction in P' (note 2 in the report)."""
    c = counts(dec)
    n, s, sy, y = c
    mt = metrics(c)
    dates_s2 = len({r.u.date for r in dec if r.s2})
    mins = [("units in S2 >= %d" % MIN_ARM_UNITS, s, s >= MIN_ARM_UNITS),
            ("units in not-S2 >= %d" % MIN_ARM_UNITS, n - s, n - s >= MIN_ARM_UNITS),
            ("distinct dates in S2 >= %d" % MIN_S2_DATES, dates_s2, dates_s2 >= MIN_S2_DATES),
            ("alignment pairs >= %d" % MIN_PAIRS, pairs, pairs >= MIN_PAIRS)]
    min_ok = all(ok for _, _, ok in mins)

    main_boot = MonthBootstrap(by_month_days(dec, contrib_main)) if dec else None
    cmp_boot = MonthBootstrap(by_month_days([r for r in full_p if r.s2 is not None], contrib_cmp)) \
        if any(r.s2 is not None for r in full_p) else None
    res_main = {b: main_boot.run({"prec": f_precision, "sep": f_separation, "rec": f_recall,
                                  "mh": f_mantel_haenszel}, b, draws, seed) for b in blocks} if main_boot else {}
    res_cmp = {b: cmp_boot.run({"uk": f_diff_ukmo, "bm": f_diff_best}, b, draws, seed)
               for b in blocks} if cmp_boot else {}

    def lbs(group, key):
        return {b: (_lb(group[b][key], draws) if b in group else (None, False)) for b in blocks}

    def sens(group, key, fmt):
        other = [b for b in blocks if b != DECISION_BLOCK]
        return " / ".join("%dd %s" % (b, fmt(lbs(group, key)[b][0]) if lbs(group, key)[b][1] else "n/a") for b in other)

    pp = lambda x: "-" if x is None else "%+.1f pp" % (100 * x)
    pc = lambda x: "-" if x is None else "%.1f%%" % (100 * x)
    crits: List[Criterion] = []
    for name, key, rule, est, ok, fmt in [
            ("1 precision P(Y|S2)", "prec", "lower bound >= %d%%" % (100 * PRECISION_MIN), mt["precision"],
             lambda lb: lb >= PRECISION_MIN, pc),
            ("2 separation vs not-S2", "sep", "lower bound >= +%d pp" % (100 * SEPARATION_MIN), mt["separation"],
             lambda lb: lb >= SEPARATION_MIN, pp),
            ("3 recall P(S2|Y)", "rec", "lower bound >= %d%%" % (100 * RECALL_MIN), mt["recall"],
             lambda lb: lb >= RECALL_MIN, pc)]:
        lb, usable = lbs(res_main, key)[DECISION_BLOCK] if res_main else (None, False)
        crits.append(_crit(name, rule, (pp if key == "sep" else pc)(est), lb, usable, ok, fmt,
                           sens(res_main, key, fmt) if res_main else "-"))
    flag = mt["flag"]
    crits.append(Criterion("4 flag rate P(S2)", "<= %d%%" % (100 * FLAG_MAX), pc(flag), "(point estimate)", "-",
                           "pass" if flag is not None and flag <= FLAG_MAX else "fail"))
    # criterion 5, on full P against both comparators
    cn, cs, csy, cy = counts(full_p)
    uk_n = sum(1 for r in full_p if r.s2 is not None and r.flag(mc.UKMO))
    bm_n = sum(1 for r in full_p if r.s2 is not None and r.flag(mc.BEST))
    sub = []
    for key, nm, cnt in (("uk", "UKMO", uk_n), ("bm", "best_match", bm_n)):
        sub.append((nm, cnt, lbs(res_cmp, key)[DECISION_BLOCK] if res_cmp else (None, False),
                    sens(res_cmp, key, pp) if res_cmp else "-"))
    parts, sts = [], []
    for nm, cnt, (lb, usable), _ in sub:
        if cnt < MIN_ARM_UNITS:
            # a silent comparator: short lead alone does not reproduce the cloud, which is what this tests
            parts.append("%s silent (fires on %d < %d units): short lead alone does not reproduce the cloud"
                         % (nm, cnt, MIN_ARM_UNITS))
            sts.append("pass")
            continue
        parts.append("%s fires on %d units, lower bound %s" % (nm, cnt, pp(lb) if usable else "n/a"))
        sts.append("inconclusive" if not usable else ("pass" if lb > 0 else "fail"))
    st5 = "fail" if "fail" in sts else ("inconclusive" if "inconclusive" in sts else "pass")
    crits.append(Criterion("5 beats the null comparators (full P)", "lower bound of precision(S2) - precision(S_UK) > 0, "
                           "same for best_match", "S2 precision %s" % pc(ratio(csy, cs)), "; ".join(parts),
                           "; ".join("%s: %s" % (x[0], x[3]) for x in sub), st5))
    lb6, ok6 = lbs(res_main, "mh")[DECISION_BLOCK] if res_main else (None, False)
    mh_est = f_mantel_haenszel([m for m in _month_tuples(dec)])
    crits.append(_crit("6 within-month separation (Mantel-Haenszel)", "lower bound > 0", pp(mh_est), lb6, ok6,
                       lambda lb: lb > 0, pp, sens(res_main, "mh", pp) if res_main else "-"))
    ev_txt, ev_ok = [], True
    for ev in ("SUNRISE", "SUNSET"):
        e = counts([r for r in dec if r.u.ttype == ev])
        p = ratio(e[2], e[1])
        ev_txt.append("%s %s (n=%d)" % (ev.lower(), pc(p), e[1]))
        ev_ok = ev_ok and p is not None and p >= PRECISION_MIN
    crits.append(Criterion("7 each event alone", "point precision >= %d%% for sunrise and sunset" % (100 * PRECISION_MIN),
                           "; ".join(ev_txt), "(point estimates)", "-", "pass" if ev_ok else "fail"))

    if not min_ok:
        outcome = "inconclusive"
        why = "minimum count(s) not met: " + "; ".join("%s (have %d)" % (t, v) for t, v, ok in mins if not ok)
    elif any(k.status == "fail" for k in crits):
        outcome, why = "fail", "failed: " + ", ".join(k.name for k in crits if k.status == "fail")
    elif any(k.status == "inconclusive" for k in crits):
        outcome, why = "inconclusive", "not assessable: " + ", ".join(k.name for k in crits if k.status == "inconclusive")
    else:
        outcome, why = "pass", "all seven criteria met"
    capped = False
    if outcome == "pass" and gate:
        outcome, capped = "no pass possible", True
        why = "criteria met, but the alignment control fired (best_match already reads cloud on > 20% of P): " \
              "a pass is not possible on this population"
    return {"label": label, "n": n, "counts": c, "metrics": mt, "mins": mins, "criteria": crits,
            "outcome": outcome, "why": why, "capped": capped, "dates_s2": dates_s2}


def _month_tuples(recs: Sequence[Rec]) -> List[Tuple[int, ...]]:
    """Per-month (n, S2, S2&Y, Y) tuples for the point estimate of the within-month statistic."""
    return [_sum([contrib_main(r) for r in recs if r.u.month == m]) for m in sorted({r.u.month for r in recs})]


# ------------------------------------------------------------------------------ report pieces
def md_table(head: Sequence[str], rows: Sequence[Sequence[object]]) -> str:
    """A markdown table."""
    def cell(c: object) -> str:
        return str(c).replace("|", "\\|")      # a pipe inside P(Y|S2) must not split the cell
    out = ["| " + " | ".join(cell(c) for c in head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    out += ["| " + " | ".join(cell(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def kish(recs: Sequence[Rec]) -> Optional[Tuple[int, int]]:
    """(k_eff, n_eff) as INTEGERS for a Wilson interval beside the raw one: the weighted precision and the
    Kish effective n of the S2 units. A float is never handed to ``stats.wilson`` (mc.safe_wilson enforces)."""
    ws = [(r.weight, bool(r.y)) for r in recs if r.s2]
    if not ws:
        return None
    sw = sum(w for w, _ in ws)
    n_eff = sw * sw / sum(w * w for w, _ in ws)
    p = sum(w for w, y in ws if y) / sw
    return int(round(p * n_eff)), int(round(n_eff))


def desc_line(name: str, c: Tuple[int, int, int, int]) -> List[str]:
    """One descriptive row: flagged, precision, separation, recall, flag rate."""
    n, s, sy, y = c
    mt = metrics(c)
    return [name, n, s, mc.fmt_ci(sy, s) if s else "-",
            "-" if mt["separation"] is None else "%+.1f pp" % (100 * mt["separation"]),
            mc.fmt_ci(sy, y) if y else "-", mc.fmt_pct(mt["flag"])]


DESC_HEAD = ["signal", "units", "flagged", "precision P(Y|flag)", "separation", "recall P(flag|Y)", "flag rate"]


def lead_band_table(recs: Sequence[Rec]) -> str:
    """Every S2 metric in each lead band (descriptive)."""
    rows = []
    for label, _, _ in LEAD_BANDS:
        rows.append(desc_line(label, counts([r for r in recs if band_of(r.u.lead_h) == label])))
    rows.append(desc_line("all", counts(recs)))
    return md_table(DESC_HEAD, rows)


def descriptive(recs: Sequence[Rec]) -> str:
    """Per-model, alternative signals, outcome variants, rating split (all descriptive, nothing decides)."""
    L: List[str] = []
    rows = []
    for m in mc.MODELS:
        voters = [r for r in recs if r.M.get(m) is not None and r.y is not None]
        hi = [r for r in voters if r.M[m] >= mc.THRESH]
        lo = [r for r in voters if r.M[m] < mc.THRESH]
        rows.append([mc.SHORT[m] + (" (optimistic: shares ERA5's physics)" if m == mc.ECMWF else ""),
                     len(voters), len(hi), mc.fmt_pct(ratio(len(hi), len(voters))),
                     mc.fmt_ci(sum(1 for r in hi if r.y), len(hi)) if hi else "-",
                     mc.fmt_ci(sum(1 for r in lo if r.y), len(lo)) if lo else "-"])
    L.append(md_table(["model", "voting", "M >= 30", "flag rate", "P(Y | M >= 30)", "P(Y | M < 30)"], rows))
    sigs = [("S2 (ICON, GFS, Meteo-France)", lambda r: r.s2),
            ("S1 (any of the three)", lambda r: mc.votes(r.M, need=1)),
            ("S3 (all three)", lambda r: mc.votes(r.M, need=3)),
            ("S2 without GFS (ICON and Meteo-France)", lambda r: mc.votes(r.M, (mc.ICON, mc.MF), 2)),
            ("S2+E (>= 2 of the three plus ECMWF; optimistic)", lambda r: mc.votes(r.M, mc.VOTERS + (mc.ECMWF,), 2)),
            ("ECMWF alone (optimistic)", lambda r: r.flag(mc.ECMWF)),
            ("S2-PL (700/500/300 hPa, same vote)", lambda r: mc.votes(r.PL))]
    L.append("")
    L.append(md_table(DESC_HEAD, [desc_line(nm, counts(recs, f)) for nm, f in sigs]))
    L.append("")
    L.append("**Outcome variants for S2** (descriptive):")
    L.append("")
    L.append(md_table(DESC_HEAD, [
        desc_line("Y (max(mid, high) >= 30, low < 60)", counts(recs)),
        desc_line("Y_mid (mid >= 30, low < 60)", counts(recs, out=lambda r: mc.era5_y(r.u, mid_only=True))),
        desc_line("Y50 (max(mid, high) >= 50, low < 60)", counts(recs, out=lambda r: mc.era5_y(r.u, mc.Y50))),
        desc_line("Y, stored rating <= 2", counts([r for r in recs if r.u.rating is not None and r.u.rating <= 2])),
        desc_line("Y, stored rating >= 3", counts([r for r in recs if r.u.rating is not None and r.u.rating >= 3])),
        desc_line("Y, no stored rating", counts([r for r in recs if r.u.rating is None]))]))
    return "\n".join(L)


def baselines(recs: Sequence[Rec]) -> str:
    """Climatology, the stored-only predictor, and S2 inside the 'zero at every stored point' shape."""
    L: List[str] = []
    by_lm: Dict[tuple, List[Rec]] = defaultdict(list)
    for r in recs:
        by_lm[(r.u.location_id, r.u.month)].append(r)

    def clim(r: Rec) -> Optional[bool]:
        """Leave-one-out location x month Y rate >= 50% (needs >= 3 other units, else abstains)."""
        others = [o for o in by_lm[(r.u.location_id, r.u.month)] if o is not r and o.y is not None]
        return None if len(others) < 3 else sum(1 for o in others if o.y) / len(others) >= 0.5
    L.append(md_table(DESC_HEAD, [
        desc_line("S2", counts(recs)),
        desc_line("month x location climatology (leave-one-out Y rate >= 50%)", counts(recs, clim)),
        desc_line("stored-only (any non-zero stored observer, far or pressure-level mid/high)",
                  counts(recs, lambda r: r.stored_nonzero if r.y is not None else None))]))
    L.append("")
    zero = [r for r in recs if not r.stored_nonzero]
    rest = [r for r in recs if r.stored_nonzero]
    L.append("**S2 by stored shape.** 'Zero at every stored point' is the 31 Oct shape:")
    L.append("")
    L.append(md_table(DESC_HEAD, [desc_line("zero at every stored point (n=%d)" % len(zero), counts(zero)),
                                  desc_line("some stored point non-zero (n=%d)" % len(rest), counts(rest))]))
    sep = metrics(counts(zero))["separation"]
    L.append("")
    L.append("S2 inside the all-zero shape: " + (
        "no separation measurable (an arm is empty)." if sep is None else
        ("separation %+.1f pp: it adds nothing over the stored-only predictor there." % (100 * sep) if sep <= 0 else
         "separation %+.1f pp: it adds information the stored values do not carry." % (100 * sep))))
    months = sorted({r.u.month for r in recs})
    L.append("")
    L.append(md_table(["month", "units", "Y rate"], [
        [m, sum(1 for r in recs if r.u.month == m),
         mc.fmt_pct(ratio(sum(1 for r in recs if r.u.month == m and r.y), sum(1 for r in recs if r.u.month == m)))]
        for m in months]))
    return "\n".join(L)


def observer_secondary(recs: Sequence[Rec]) -> str:
    """The same at the observer point (ERA5 cv_observer_*, no cone). Fetched only if budget allowed."""
    have = [r for r in recs if all(r.OBS.get(m) is not None for m in mc.VOTERS)]
    if not have:
        return "No observer-point values in the cache (run md_fetch.py --observer if budget allows)."
    sig = lambda r: mc.votes(r.OBS)
    out = lambda r: mc.era5_y_observer(r.u)
    return md_table(DESC_HEAD, [desc_line("S2 at the observer point vs ERA5 observer (%d units)" % len(have),
                                          counts(have, sig, out))])


def worked_examples(recs: Sequence[Rec]) -> str:
    """The trigger and training cases, on their own."""
    if not recs:
        return "None of the trigger or training units is in this export."
    head = ["case", "location", "date", "lead h", "stored L/M/H", "ERA5 L/M/H"] + [mc.SHORT[m] for m in mc.MODELS] + ["S2", "Y"]
    rows = []
    for r in recs:
        u = r.u
        f = lambda v: "-" if v is None else "%d" % v
        s2 = r.s2
        rows.append([u.case, u.name, u.date, "%.1f" % u.lead_h, "%s/%s/%s" % (f(u.stored_low), f(u.stored_mid), f(u.stored_high)),
                     "%s/%s/%s" % (f(u.h_low), f(u.h_mid), f(u.h_high)) if u.cv_id else "not verified yet"]
                    + ["vote -" if r.M[m] is None else r.M[m] for m in mc.MODELS]
                    + ["-" if s2 is None else ("yes" if s2 else "no"), "-" if r.y is None else ("yes" if r.y else "no")])
    return md_table(head, rows)


# ------------------------------------------------------------------------------ the whole report
def decision_block(d: dict) -> str:
    """One decision table with its minimum counts and outcome."""
    L = ["**%s** - n=%d decisive units (S2: %d on %d dates). **Outcome: %s.** %s" % (
        d["label"], d["n"], d["counts"][1], d["dates_s2"], d["outcome"].upper(), d["why"]), ""]
    L.append(md_table(["minimum count", "have", "met"], [[t, v, "yes" if ok else "NO"] for t, v, ok in d["mins"]]))
    L.append("")
    L.append(md_table(["criterion", "rule", "estimate", "95% bound (5-day blocks)", "sensitivity (3 / 7-day)", "status"],
                      [[k.name, k.rule, k.estimate, k.bound, k.sens, k.status] for k in d["criteria"]]))
    return "\n".join(L)


NOTES = """\
1. **Population for the sample.** P' needs the refetched UKMO and best_match, which only exist after the fetch, so
   the sample is drawn from P and P' is applied here.
2. **Criterion 5 is evaluated on full P.** In P' the comparators are below 10 by construction, so S_UK and the
   best_match comparator can never fire there and precision(S_UK) is undefined. The criterion therefore compares
   precision(S2) with precision(UKMO M >= 30) and with precision(best_match M >= 30) on P, per comparator. A
   comparator firing on fewer than 30 units is silent: that part passes (short lead alone does not reproduce the
   cloud), no bootstrap. Otherwise the lower bound of the precision difference must be > 0 (undefined in more than 5%
   of draws: not assessable). Fail if either part fails, else inconclusive if either is, else pass.
3. **Alignment gate.** It fires if the pooled share, or any single event's share with >= 20 pairs, exceeds 20%.
   When it fires, a pass on full P is reported as 'no pass possible'; the headline decision is P' either way.
4. **Overall outcome.** Minimum counts unmet -> inconclusive; else any criterion failing -> fail; else any criterion
   not assessable (bootstrap statistic undefined in more than 5% of draws) -> inconclusive; else pass.
5. **Y variants** keep the low < 60 clause. The climatology baseline is the leave-one-out Y rate of the same location
   and month. S2+E needs >= 2 of the four voters (ICON, GFS, Meteo-France, ECMWF), all four voting.
6. **Bounds** are the 2.5th percentile of the within-month moving-block bootstrap ({draws} draws, seed {seed}). Wilson
   intervals are descriptive and use integer counts only (Kish-effective integers for the weighted ones).
"""


def build_report(recs: Sequence[Rec], worked: Sequence[Rec], pop: mc.Population, dcounts: mc.DedupCounts,
                 args_text: str, draws: int = DRAWS, seed: int = SEED) -> Tuple[str, dict]:
    """The whole report text and the structured results (for tests)."""
    P = [r for r in recs if r.y is not None]
    voting3 = [r for r in P if r.s2 is not None]
    n_lt3 = len(P) - len(voting3)
    align = alignment(P)
    pprime_all = [r for r in P if r.lead_fair]
    pprime = [r for r in pprime_all if r.s2 is not None]
    dec_p = decide("Lead-fair population P' (headline)", pprime, P, align["pairs"], False, draws, seed)
    dec_full = decide("Full population P (beside it)", voting3, P, align["pairs"], align["gate"], draws, seed)
    if align["gate"]:
        dec_p["why"] += " Alignment control fired, so the decision stands on P' alone."
    L: List[str] = ["# Model disagreement sizing - report", "", args_text, ""]
    L.append("## 1. Units and population")
    L.append("")
    L.append("Export rows %d; eligible (batch_state NULL/SCORED, run before the event) %d; **units after "
             "deduplication %d**." % (dcounts.rows, dcounts.eligible, dcounts.units))
    L.append("")
    L.append(md_table(["step", "units"], [[a, b] for a, b in pop.funnel]))
    L.append("")
    L.append("Moved-location guard dropped %d unit(s). In the analysis: %d units of P with ERA5 and fetched values; "
             "%d have all three voters, **%d do not vote and never decide** (reported separately); "
             "P' = %d units (%d with three voters)." % (pop.moved, len(P), len(voting3), n_lt3, len(pprime_all), len(pprime)))
    k = kish(voting3)
    if k:
        L.append("")
        L.append("Weighted precision beside the raw one (Kish effective n, integers): %s" % mc.fmt_ci(*k))
    L.append("")
    L.append("## 2. Alignment control (read first)")
    L.append("")
    L.append("Share of P where the refetched `best_match` already reads M >= 30 although the stored value was 0 "
             "(the gate; limit %d%%), with P1's median |delta| beside it (near 0 by construction on a stored-zero "
             "population). Pairs are units where best_match votes." % (100 * ALIGN_MAX_SHARE))
    L.append("")
    L.append(md_table(["event", "lead band", "pairs", "best_match M >= 30", "share", "median |delta|"], [
        [r["event"], r["band"], r["pairs"], r["hi"], mc.fmt_pct(r["share"]),
         "-" if r["median_delta"] is None else r["median_delta"]] for r in align["rows"]]))
    L.append("")
    L.append("**Gate: %s.** %s" % ("FIRED (%s)" % ", ".join(align["fired"]) if align["gate"] else "not fired",
                                   "No pass is possible on full P; the decision stands on P' alone."
                                   if align["gate"] else "Pairs and share are within the limits (or pairs < %d: inconclusive)." % MIN_PAIRS))
    L.append("")
    L.append("## 3. Decision (pooled sunrise + sunset, T+0)")
    L.append("")
    L.append(decision_block(dec_p))
    L.append("")
    L.append(decision_block(dec_full))
    L.append("")
    L.append("A pass is an **upper bound**: the comparators here run at a short stitched lead (plan section 3).")
    L.append("")
    L.append("## 4. Lead bands")
    L.append("")
    L.append("Lead-fair population P':")
    L.append("")
    L.append(lead_band_table(pprime))
    L.append("")
    L.append("Full P:")
    L.append("")
    L.append(lead_band_table(voting3))
    L.append("")
    L.append("## 5. Descriptive tables")
    L.append("")
    L.append("### Lead-fair population P'")
    L.append("")
    L.append(descriptive(pprime))
    L.append("")
    L.append("### Full P (comparators can fire here)")
    L.append("")
    L.append(descriptive(P))
    L.append("")
    L.append("## 6. Baselines (P')")
    L.append("")
    L.append(baselines(pprime))
    L.append("")
    L.append("## 7. Secondary: the observer point")
    L.append("")
    L.append(observer_secondary(P))
    L.append("")
    L.append("## 8. Worked examples (excluded from the decision)")
    L.append("")
    L.append(worked_examples(worked))
    L.append("")
    L.append("## 9. Notes: where the plan was silent or inconsistent")
    L.append("")
    L.append(NOTES.replace("{draws}", str(draws)).replace("{seed}", str(seed)))
    return "\n".join(L), {"P": P, "pprime": pprime, "align": align, "decision": dec_p, "decision_full": dec_full}


def write_units_csv(path: str, recs: Sequence[Rec]) -> None:
    """One audit row per unit: the values behind every number in the report."""
    cols = ["evaluation_id", "location_id", "target_date", "target_type", "lead_h", "y", "lead_fair", "s2"] + \
           ["M_" + m for m in mc.MODELS] + ["PL_" + m for m in mc.MODELS]
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for r in recs:
            u = r.u
            w.writerow([u.eval_id, u.location_id, u.date, u.ttype, "%.2f" % u.lead_h, r.y, r.lead_fair, r.s2]
                       + [r.M.get(m) for m in mc.MODELS] + [r.PL.get(m) for m in mc.MODELS])


def main(argv: Optional[List[str]] = None, client: Optional[mc.om.Client] = None) -> int:
    """CLI entry point: no network; reads the export and the cache, writes ``out/report.md``."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", help="the P1 export (export_forecasts.sql output)")
    ap.add_argument("--list", default=None, help="out/fetch_list.csv (the sample); default: all of P")
    ap.add_argument("--out", default="out")
    ap.add_argument("--cache", default=mc.DEFAULT_CACHE, help="cache dir (default: far-cloud-p1/cache)")
    ap.add_argument("--observer", action="store_true",
                    help="the fetch used --observer (the plan only matters for observer coordinates)")
    ap.add_argument("--draws", type=int, default=DRAWS)
    ap.add_argument("--seed", type=int, default=SEED)
    a = ap.parse_args(argv)

    units, dcounts = mc.dedup_units(mc.load_rows(a.csv))
    pop = mc.select_population(units)
    weights: Dict[int, float] = {}
    members, worked_units = pop.members, pop.worked
    if a.list:
        rows = md_sample.load_fetch_list(a.list)
        weights = {int(r["evaluation_id"]): float(r["weight"]) for r in rows if r["role"] == "sample"}
        members = [u for u in pop.members if u.eval_id in weights]
        worked_ids = {int(r["evaluation_id"]) for r in rows if r["role"] == "worked"}
        worked_units = [u for u in pop.worked if u.eval_id in worked_ids]
    try:
        client = client or mc.om.Client(a.cache, 0, fetch=no_network, dry_run=True)
    except mc.om.BudgetFileCorrupt as e:
        print("REFUSING TO RUN: %s" % e)
        return 2
    plan = mc.plan_requests(list(members) + list(worked_units), a.observer)
    recs = gather(client, members, plan, weights)
    wrecs = gather(client, worked_units, plan)
    args_text = "Export `%s`; sample `%s`; cache `%s`; seed %d; %d bootstrap draws." % (
        a.csv, a.list or "(all of P)", a.cache, a.seed, a.draws)
    text, res = build_report(recs, wrecs, pop, dcounts, args_text, a.draws, a.seed)
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, "report.md")
    with open(path, "w") as f:
        f.write(text + "\n")
    write_units_csv(os.path.join(a.out, "units.csv"), recs)
    d = res["decision"]
    print("DECISION on P' (T+0): %s - %s" % (d["outcome"].upper(), d["why"]))
    print("full P: %s" % res["decision_full"]["outcome"].upper())
    print("wrote %s and units.csv" % path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
