# Results — 2026-10-16

Run against production on 2026-10-16 (afternoon) with `since=2026-08-17` (the last 60 days) and,
as a same-week control, `since=2026-10-08`. `km=12` for the near-twin files.

Rated rows exist from 2026-08-29. Near-term (T+0/T+1) was rated by **Haiku 4.5 until 2026-10-04**
and by **Sonnet 5.5 from 2026-10-08**. The FAR bucket (T+2, and T+3 SETTLED only) is Haiku 4.5
throughout. So the 60-day window holds a same-HORIZON comparison (Haiku T+0/T+1 in Aug–Oct against
Sonnet T+0/T+1 in Oct) and the `since=2026-10-08` window a same-WEEK one (Haiku T+2/T+3 against
Sonnet T+0/T+1 under the same weather).

## Same-cell groups, last 60 days (`summary.sql`)

The served-rating columns include tidal groups, where a 2-star gap can be honest. The inland and
sky columns are the noise measure.

| model | T+ | runs | groups | served ≥2 | served ≥3 | inland groups | **inland ≥2** | sky groups | **sky ≥2** |
|---|---|---|---|---|---|---|---|---|---|
| Haiku 4.5 | 0 | 56 | 1,462 | 143 (9.8%) | 12 (0.8%) | 1,047 | **78 (7.4%)** | 50 | **7 (14%)** |
| Haiku 4.5 | 1 | 54 | 2,070 | 243 (11.7%) | 25 (1.2%) | 1,438 | **119 (8.3%)** | 0 | — |
| Haiku 4.5 | 2 | 35 | 723 | 77 (10.7%) | 6 (0.8%) | 442 | **37 (8.4%)** | 208 | **12 (5.8%)** |
| Haiku 4.5 | 3 | 24 | 217 | 22 (10.1%) | 1 (0.5%) | 65 | **2 (3.1%)** | 66 | **3 (4.5%)** |
| Sonnet 5.5 | 0 | 16 | 443 | 31 (7.0%) | 1 (0.2%) | 302 | **1 (0.3%)** | 443 | **1 (0.2%)** |
| Sonnet 5.5 | 1 | 18 | 713 | 53 (7.4%) | 2 (0.3%) | 440 | **2 (0.5%)** | 713 | **2 (0.3%)** |

Sonnet's served-rating spread is almost entirely tide: 30 of its 31 T+0 groups and 51 of its 53 T+1
groups with a 2-star gap have a tidal member, and on `sky_rating` they agree.

Same week only (`since=2026-10-08`):

| model | T+ | runs | groups | served ≥2 | inland ≥2 | sky ≥2 |
|---|---|---|---|---|---|---|
| Haiku 4.5 | 2 | 9 | 181 | 16 (8.8%) | 6 of 105 (5.7%) | 11 of 181 (6.1%) |
| Haiku 4.5 | 3 | 4 | 50 | 5 (10.0%) | 0 of 9 | 3 of 50 (6.0%) |
| Sonnet 5.5 | 0 | 16 | 443 | 31 (7.0%) | 1 of 302 (0.3%) | 1 of 443 (0.2%) |
| Sonnet 5.5 | 1 | 18 | 713 | 53 (7.4%) | 2 of 440 (0.5%) | 2 of 713 (0.3%) |

## Near-twin pairs, ≤ 12 km, solar cloud within 5 points (`neighbour-pairs.sql`)

| model | T+ | pairs | ≥2 apart | ≥3 apart | inland pairs | **inland ≥2** |
|---|---|---|---|---|---|---|
| Haiku 4.5 | 0 | 16,850 | 1,492 (8.9%) | 144 | 13,457 | **1,087 (8.1%)** |
| Haiku 4.5 | 1 | 25,706 | 3,016 (11.7%) | 334 | 20,347 | **2,291 (11.3%)** |
| Haiku 4.5 | 2 | 11,468 | 1,084 (9.5%) | 144 | 8,193 | **715 (8.7%)** |
| Haiku 4.5 | 3 | 2,730 | 249 (9.1%) | 21 | 897 | **54 (6.0%)** |
| Sonnet 5.5 | 0 | 4,323 | 152 (3.5%) | 4 | 3,323 | **10 (0.3%)** |
| Sonnet 5.5 | 1 | 8,417 | 278 (3.3%) | 2 | 6,309 | **13 (0.2%)** |

Same week: Haiku T+2 263 of 2,225 inland pairs (11.8%), T+3 6 of 93 (6.5%); Sonnet as above.

## What a neighbour guard would flag, last 60 days (`guard-candidates.sql`)

A rated row is flagged when it is 3★ or more and at least 2 stars above every near-twin.

| model | T+ | runs | rows with a twin | flagged | of which 4★+ | flagged, inland |
|---|---|---|---|---|---|---|
| Haiku 4.5 | 0 | 56 | 8,375 | 120 (1.4%) | 85 | 94 |
| Haiku 4.5 | 1 | 54 | 11,691 | 219 (1.9%) | 177 | 164 |
| Haiku 4.5 | 2 | 35 | 4,597 | 36 (0.8%) | 23 | 27 |
| Haiku 4.5 | 3 | 26 | 1,353 | 22 (1.6%) | 19 | 9 |
| Sonnet 5.5 | 0 | 16 | 2,485 | 15 (0.6%) | 13 | **0** |
| Sonnet 5.5 | 1 | 18 | 3,870 | 32 (0.8%) | 29 | **1** |

On Haiku's FAR bucket that is about one or two flags a nightly run. Every Sonnet flag but one is a
tidal place out-rating an inland or differently-tided neighbour, which a guard on the served
rating would wrongly catch: a guard must compare `sky_rating`.

## Cells that disagree most (`top-cells.sql`, inland cells first)

| cell (lat, lng) | a member | model | groups | ≥2 | ≥3 |
|---|---|---|---|---|---|
| 54.396, −2.160 | Angram & Thwaite Barns (Swaledale) | Haiku | 196 | 34 | 6 |
| 54.413, −3.056 | Cathedral Cavern (Little Langdale) | Haiku | 169 | 30 | 3 |
| 54.054, −2.132 | Janet's Foss (Malham) | Haiku | 179 | 27 | 3 |
| 55.547, −2.182 | Cheviot (College Valley) | Haiku | 190 | 15 | 3 |
| 54.648, −2.158 | Low Force | Haiku | 194 | 14 | 2 |
| 55.167, −1.618 | Bothal Weir | Haiku | 200 | 14 | 1 |
| 55.008, −2.375 | Highshield Crags (Hadrian's Wall) | Haiku | 179 | 10 | 2 |
| 54.396, −2.160 | Angram & Thwaite Barns | Sonnet 5.5 | 46 | 2 | 0 |

The worst served-rating cells overall are coastal (Flamborough 49 of 187, Craster 32 of 89, Holy
Island 29 of 176, all Haiku), where tide accounts for some of the gap.

## Cost of the FAR bucket (`far-cost.sql`, `since=2026-10-08`, nine nightly runs)

| model | T+ | calls | avg cost a call | avg input | avg cache read | avg output | total |
|---|---|---|---|---|---|---|---|
| Haiku 4.5 | 2 | 1,361 | $0.000982 | 659 | 5,346 | 154 | $1.336 |
| Haiku 4.5 | 3 | 395 | $0.000972 | 644 | 5,360 | 153 | $0.384 |
| Sonnet 5.5 | 0 | 3,649 | $0.002787 | 815 | 7,754 | 235 | $10.170 |
| Sonnet 5.5 | 1 | 5,419 | $0.002804 | 814 | 7,741 | 234 | $15.196 |

FAR today: 1,756 calls over nine days, about **195 calls and $0.19 a day**. Sonnet 5.5 pays
**$0.0028 a call** for the same sky prompt (2.85× Haiku; its tokeniser counts about 45% more cached
input and its adaptive thinking adds output). FAR on Sonnet 5.5: about **$0.55 a day, +$0.36 a day,
about $11 a month**. Near-term Sonnet today costs about $2.82 a day, so the whole nightly sky batch
would rise by about 12%.
