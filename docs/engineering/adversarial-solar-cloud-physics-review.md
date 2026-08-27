> **Provenance.** Produced by OpenAI Codex (cloud) on 2026-08-27, commissioned as an adversarial
> cross-vendor review of the solar-cloud sampling geometry in
> `docs/engineering/cloud-approach-veto-fix.md` §8 and `DirectionalSamplingGeometry`. The reviewer
> had the physics brief only — no repo access, no CLAUDE.md, no prior findings. Text below is
> verbatim. **Adjudication is in `cloud-approach-veto-fix.md` §10**: every quantitative claim was
> independently re-derived and reproduces exactly; the C5/C6/C1 corrections were accepted into §8;
> the C7/C8 refutations were contested with the §9 measured results (both were pre-registered
> questions this program had already answered over 33k rows). Note the reviewer's own environment
> blocked outbound reference checks (proxy 403s), so its citations were verified on this side
> against known editions/DOIs — all are standard, real works.

# Adversarial review of the solar-cloud sampling geometry

**Date:** 2026-08-27
**Scope:** UK locations at 50--59 degrees N; operational inputs and thresholds in the
current 113 km / 226 km directional-cloud design.  This is a physics review, not a
validation of the final photographic rating.  In particular, ERA5 cloud fraction is an
analysed grid-box quantity, not a measurement of whether a particular ray was blocked.

## Executive finding

The algebra behind the *last-light* corridors is sound, but several physical
interpretations attached to it are not.  The strongest failures are:

1. **C6 is refuted.** Astronomical near-horizon refraction is not adequately represented
   by one terrestrial effective-Earth-radius multiplier, and even the proposed multiplier
   moves the fixed 113 km point from the geometric boundary to outside the 4 km corridor.
2. **C5 is partly refuted.** A 1 km top can begin blocking an 8 km canvas 0.185 degrees
   before last light, not merely at the 226 km point's final 0.080 degrees; and the quoted
   160--515 km range improperly combines the near edge for a 2 km top with the far edge
   for a 3 km top.  The correct ranges are 159--479 km and 124--515 km respectively.
3. **C7 is refuted as a parcel identity.** An observer-relative displacement made with a
   single surface-wind vector is, at best, a persistence heuristic.  Boundary-layer shear,
   evolving winds, cloud formation/dissipation, and position error readily exceed one
   11 km forecast cell within three hours.
4. **C8 is refuted as a literal probe of the swept solar horizon.** From solar altitude
   +6 to -6 degrees the sunset azimuth moves about 14--35 degrees over the stated latitude
   and seasonal envelope.  A symmetric three-point mean is neither a time sweep nor a
   gap detector, and can turn 90/0/90 into 60%, exactly on an operational boundary.

Those defects do **not** justify replacing 113 or 226 km by more precise constants.  Real
cloud-top uncertainty alone moves a horizon distance by tens of kilometres, while
refraction and extinction are state dependent.  Keep 113 and 226 km as labelled features
pending outcome validation, but stop describing either as a binary physical gate.  A new
319 km point would add information about nominal 8 km canvas geometry; **342--349 km is
false precision**, not a universally “refracted” location.

## 1. Independent derivation of C1--C6

### 1.1 Ray geometry and the general blocking condition

Let `x` increase toward the Sun, `R = 6371 km`, canvas height be `h`, and positive
`delta` mean geometric solar depression in radians.  In the accepted parabolic model,

```text
g(x) = h - delta*x + x^2/(2R).
```

Differentiation, rather than any claimed numerical result, gives

```text
g'(x) = -delta + x/R,
x_min = R*delta,
g_min = h - R*delta^2/2.
```

The surface-grazing last ray therefore satisfies

```text
delta_end = sqrt(2h/R),       x_c = sqrt(2Rh).
```

An opaque cloud whose **top** is `H` intersects a ray wherever `g(x) <= H`.
At an arbitrary depression its boundary roots are

```text
x = R*delta +/- sqrt(R^2*delta^2 - 2R(h-H)),
```

which exist only once

```text
delta >= delta_on = sqrt(2(h-H)/R)       (h > H).
```

At canvas last light this reduces to the claimed compact result

```text
x = sqrt(2Rh) +/- sqrt(2RH).
```

This derivation also exposes the model's important omission: a point is not a corridor.
The equation says which `x` can intersect a ray, but a gridded cloud fraction at one `x`
does not say whether an opaque cloud occupies the rest of the slanted ray footprint.

### 1.2 Numerical results

All angles below are geometric and all heights use the same spherical datum.

| Canvas `h` | Cloud top `H` | onset depression | last-light depression | last-light corridor |
|---:|---:|---:|---:|---:|
| 0 km (observer) | 1 km | n/a | 0 degrees | 0--112.9 km |
| 4 km | 1 km | 1.758 degrees | 2.030 degrees | 112.9--338.6 km |
| 8 km | 1 km | 2.686 degrees | 2.871 degrees | 206.4--432.2 km |
| 8 km | 2 km | 2.487 degrees | 2.871 degrees | 159.6--478.9 km |
| 8 km | 3 km | 2.270 degrees | 2.871 degrees | 123.8--514.8 km |

Rounded centres are 225.8 km for 4 km and 319.3 km for 8 km.  Direct substitution at
`x = 226 km`, `h = 8 km`, `H = 1 km` gives blocking onset
`delta = (h + x^2/(2R) - H)/x = 2.791 degrees`: only 0.080 degrees before last
light **at that one point**.  Optimising over all `x`, however, gives 2.686 degrees,
or 0.185 degrees before last light.  Confusing the point result with the entire
corridor is the main error in C5.

### 1.3 Claim-by-claim attack

#### C1 -- geometric boundary for direct light

At geometric sunset set `h = delta = 0`; then `g=x^2/(2R)`.  Solving `g=1 km`
gives 112.9 km.  Before sunset (`delta < 0` under the stated convention), the ray
rises faster and the boundary is closer.  Thus 113 km is the farthest geometric
intersection for a 1 km top and the central numerical derivation is correct.

The word “irrelevant” is too strong.  The Sun has a roughly 0.5-degree disk,
refraction shifts and distorts its lower-altitude image, cloud tops are not fixed at
1 km, and light reaching a photographed cloud need not first reach the observer.
A 2 km top moves the geometric horizon to 159.6 km; a 3 km top moves it to
195.5 km.  Therefore cloud at 150--200 km is irrelevant only to the idealised
**observer-bound, centre-of-Sun ray with a 1 km ceiling**, not to direct low sunlight
in the operational weather category “low cloud.”

**Error magnitude:** 0.1 km in the stated 1 km idealisation, but +47 to +83 km for
2--3 km tops.  **Material:** yes, because the provider's low-cloud category is not a
1 km cloud-top measurement; a cloudy 113 km mean can cross the 20/40/60% rules while
the actual blocking layer is elsewhere.  It does not prove that 150--200 km cloud
should become an escalatory input.

#### C2 -- last-light canvas corridors

The differentiation and quadratic roots above independently reproduce C2 to rounding:
113--339 km for (`h=4`, `H=1`) and 206--432 km for (`h=8`, `H=1`).

**Error magnitude:** under 0.5 km from rounding.  **Material:** no under the accepted
geometry.  The material caveat is semantic: these are corridors for the last
geometrically direct ray, not footprints of all photographically useful illumination.

#### C3 -- interpretations of 226 and 319 km

Geometrically, 226 km is 0.2 km from the 4 km centre and 20.3 km inside the near
edge of the 8 km/1 km corridor.  It is reasonable to call that near-edge, not centre,
sampling.  A 319 km sample is the nominal 8 km tangent point.

The proposed 342--349 km “with refraction” follows only after assuming the disputed
constant `k`.  It is not a physical correction applicable to all near-horizon optical
profiles.  Moreover, 8 km is a nominal height, whereas WMO temperate-region high cloud
spans a broad altitude range.

**Error magnitude:** geometric claims under 1 km; the proposed refracted point is
23--30 km beyond the geometric centre, but profile uncertainty can be comparable or
larger.  **Material:** 226 versus 319 km can sample different synoptic regimes and can
flip the >=30 percentage-point strip rule.  The 342 versus 349 choice is not material
relative to meteorological and refractive uncertainty.

#### C4 -- 113 km and a 4 km canvas

At `x=sqrt(2R*1 km)`, the ray height is
`g=4 km-delta*x+1 km`.  Setting `g=1 km` gives
`delta=4/112.9=2.030 degrees`, equal to 4 km canvas last light.  The zero-width
result is algebraically exact for a point cloud at an exact distance, exact heights,
and a point Sun.

“Cannot meaningfully” is not established by that identity.  Move the cloud merely
10 km farther, give it a finite horizontal width, raise its top, or integrate over the
solar disk and a finite exposure interval, and the window becomes nonzero.  For example,
optimally located 1 km cloud can start shadowing a 4 km canvas at 1.758 degrees,
0.272 degrees before last light.

**Error magnitude:** zero in the point calculation; up to 0.272 degrees (about a
minute-scale to several-minute interval, depending on season) when C4 is generalised
from the 113 km point to the actual 1 km blocking corridor.  **Material:** the exact
113 km sample should not be sold as a 4 km underlighting blocker.  But this does not
move a current threshold because the sample already acts primarily as the direct-light
gap gate.

#### C5 -- red phase and cloud-top sensitivity

Geometry alone contains no definition of “red” and cannot derive a universal final
0.4-degree burn phase.  Colour depends on wavelength-dependent extinction and
scattering, aerosol, ozone, the observer-to-canvas path, and camera response.

Even granting 0.4 degrees as a heuristic, the claim overstates the restriction.  A
1 km cloud top anywhere in its optimal corridor can block from 2.686 to 2.871 degrees,
the final 0.185 degrees, while 226 km blocks only the final 0.080 degrees.  A 2 km top
can block the final 0.385 degrees; a 3 km top, the final 0.601 degrees.  The quoted
“160--515 km” is not one corridor: it splices the 2 km near edge (159.6) to the 3 km
far edge (514.8).  The two valid corridors are approximately 160--479 and 124--515 km.

**Error magnitude:** factor 2.3 in angular duration (0.185 versus 0.080 degrees) if the
226 km result was generalised to all 1 km cloud; 35.9 km error in the near edge of the
3 km corridor and 35.9 km in the far edge of the 2 km corridor.  **Material:** yes for
interpreting 226 km as a cirrus-underlighting veto; no current rule allows far cloud to
escalate, which is the safe operational choice.  The error does not justify changing
the >=30 pp softening threshold.

#### C6 -- constant refraction

Replacing curvature `1/R` by `1/(kR)` does algebraically multiply every distance above
by `sqrt(k)`.  For `k=1.15--1.20`, the multiplier is 1.072--1.095, as claimed.
But two conclusions fail:

* The effective-radius construction is a terrestrial-ray approximation derived from a
  local refractivity gradient.  Astronomical refraction near the apparent horizon is
  strongly nonlinear, depends on the whole pressure/temperature/humidity profile, and
  can show ducting or looming under inversions.  A single `k` is not equivalent to the
  standard near-horizon astronomical refraction correction.
* Even within the claimed model, membership changes.  The 4 km/1 km corridor becomes
  about 121--363 km (`k=1.15`) to 124--371 km (`k=1.20`), so the fixed 113 km point is
  no longer on or inside it.  The categorical statement that no sample changes corridor
  is therefore false.

Standard refraction tables put ordinary astronomical refraction at the horizon near
34 arcminutes, while Bennett/Auer--Standish treatments explicitly avoid a constant
linear correction close to the horizon.  The solar radius is about 16 arcminutes, so
ordinary refraction is not a small perturbation relative to the disk or to C5's
0.080--0.185-degree windows.

**Error magnitude:** 8--11 km displacement of the 113 km boundary under the claim's
own `k`; refraction is roughly 0.57 degrees in apparent elevation under a standard
atmosphere and is profile-variable.  **Material:** yes for C4/C5 timing and boundary
language.  It need not move the 113/226 km features because cloud-field uncertainty
is already larger, but it invalidates deterministic membership and the 342--349 km
prescription.

## 2. Ranked missing physics

The ranking is by likelihood of changing one of the app's cloud-fraction decisions,
not by intellectual interest.

### 1. Cloud vertical geometry and optical thickness -- **material, very high confidence**

“Low/mid/high cloud cover” is a model diagnostic, not a top-height field.  WMO's
temperate-region convention classifies low clouds principally by base/genera, with low
bases generally below 2 km, middle around 2--7 km, and high around 5--13 km; convective
low genera can have tops far above the low étage.  Blocking is controlled by the highest
optically thick part along the incident ray, whereas visible underlighting is controlled
by cloud-base height, base optical properties, and holes along both Sun--cloud and
cloud--camera paths.

* **Magnitude:** changing `H` from 1 to 2 or 3 km moves the observer horizon from 113
  to 160 or 196 km and changes the 8 km corridor from 206--432 to 160--479 or 124--515 km.
* **Threshold impact:** plainly capable of changing all 20/40/50/60% classifications
  because the app may sample a different cloud regime.  This is the largest reason not
  to harden the far sample or invent a precise refracted distance.

### 2. Forecast representativeness, 3-D structure, and evolution -- **material, high confidence**

A grid-box fractional cover does not encode the continuous clear slot required along a
grazing ray.  Fronts and marine stratocumulus have sharp real edges but parameterised
cloud fraction is smoothed by grid resolution, interpolation, ensemble uncertainty, and
sub-grid overlap assumptions.  A cone mean destroys topology: 90/0/90 and 60/60/60 both
become 60 although one includes a central solar gap.

* **Magnitude:** the supplied empirical mode (~68 pp error in false-cloud cases) dwarfs
  the 20 and 30 pp operational differences; an 11 km positional error is enough to cross
  a front or coast.
* **Threshold impact:** directly material.  The bimodality is consistent with a sharp-edge
  displacement/smearing mixture: forecasts are accurate in the easy/open-edge mode and
  catastrophically cloudy when a modelled edge is misplaced.  Marine-layer and
  stratocumulus biases are also plausible.  It does **not** uniquely validate that story:
  comparing two model products, point interpolation, and the mismatch between cloud
  fraction and ray blockage can create the same modes.  Because 226 km is geometrically
  exact only for a nominal 4 km canvas, the data validate “forecast low-cloud fraction at
  this coordinate,” not “the underlighting corridor.”

### 3. Near-horizon refraction and profile variability -- **material near boundaries, high confidence**

Pressure, temperature gradients, humidity, inversions, and marine boundary layers curve
rays differently; super-refraction and ducting are precisely associated with stable
near-surface profiles common over water.  Astronomical refraction also varies rapidly
with apparent altitude, so translating it into one `sqrt(k)` spatial stretch is not
generally valid.

* **Magnitude:** ordinary horizon refraction is about 0.57 degrees, already 3 times the
  entire 1 km/8 km optimal blocking interval and 7 times the 226 km interval; anomalous
  profiles add variability.
* **Threshold impact:** it can change whether 113 or 226 km lies just inside a nominal
  ray corridor, but cloud-height and forecast errors dominate.  Treat as uncertainty,
  not a deterministic distance correction.

### 4. Grazing-path extinction -- **material to whether a “burn” exists, high confidence**

The plane-parallel airmass secant diverges at the horizon; spherical formulas such as
Kasten--Young instead give an optical relative airmass near 38 at zero apparent altitude.
Even a very clear vertical aerosol optical depth of 0.05 gives direct-beam transmission
`exp(-38*0.05) ~= 0.15` before Rayleigh and gas absorption; AOD 0.1 gives about 0.022.
This simple product is illustrative, because a tangent path samples altitude-dependent
density and refraction, but it establishes the scale.  A 300--400 km ray that remains in
the dense/moist boundary layer is not automatically photographically useful direct light.

* **Magnitude:** orders-of-magnitude direct-beam attenuation across realistic aerosol
  states, plus strong wavelength selection.
* **Threshold impact:** material to the meaning of every geometric corridor and to rating
  1--5, but not reducible to a cloud-percentage threshold.  The app needs visibility/AOD
  or empirical outcome calibration rather than another distance constant.

### 5. Scattering and cloud underlighting -- **material to rating, high confidence**

An illuminated base is not a mirror struck by one ray.  Single scattering gives strong
angle and particle-size dependence; multiple scattering within an optically thick cloud
diffuses and reddens the field, while aerosol/Rayleigh scattering can illuminate a canvas
after the direct solar beam is geometrically screened.  The outgoing cloud-to-camera path
can itself cross haze or low cloud.  Consequently “low cloud intersects central ray” is
neither necessary nor sufficient for a dark canvas.

* **Magnitude:** potentially the difference between no direct beam and a visibly bright,
  diffusely lit base; no honest universal percentage follows without optical depth and
  phase functions.
* **Threshold impact:** material to photographic rating, but it supports retaining the
  rule that far cloud may soften and may not independently escalate.

### 6. Advection identity (C7) -- **material, high confidence**

For a passive parcel the trajectory is the solution of `dr/dt = u(r,z,t)`, an integral
through a time- and space-dependent 3-D wind, not `r(t)=r0+u_surface*t`.  A low cloud is
also not conserved tracer: ascent, entrainment, precipitation, radiative cooling and
surface fluxes form or dissipate it.  The forecast's 10 m wind need not equal cloud-layer
wind at 925/850 hPa.  A modest 5 m/s speed mismatch accumulates 54 km in three hours; a
30-degree directional error on a 108 km leg produces 54 km cross-track error.  Both are
many 11 km grid cells.  Curvature is important near fronts and cyclones even below the
200 km cap.

* **Magnitude:** tens to more than 100 km in 3--6 h, plus complete loss of parcel identity
  through cloud development.
* **Threshold impact:** enough to sample a field differing by >=20 or >=30 pp.  Label the
  uncapped leg an **upstream environmental indicator**, not “the air that will be over the
  observer.”  A proper feature would integrate winds at diagnosed cloud level through
  forecast time and still carry a development probability.

### 7. Terrain and land--sea path -- **site-dependent material, high confidence**

The accepted no-terrain simplification is operationally dangerous even if excluded from
the algebra.  UK westward sightlines can meet Wales, the Pennines, Scottish Highlands,
Ireland, or islands; terrain can exceed 1 km and forces orographic cloud.  Conversely many
coastal paths are over sea, where boundary-layer stratocumulus and refractive profiles
differ from land.

* **Magnitude:** kilometre-scale opaque screening and major cloud-regime changes, larger
  than the assumed 1 km top locally.
* **Threshold impact:** material for affected locations and bearings, potentially flipping
  any cloud rule.  A digital elevation line-of-sight mask is more defensible than changing
  every national sample distance.

### 8. Finite solar disk -- **material only to narrow timing claims, high confidence**

The Sun's angular diameter is about 0.53 degrees.  First/last contact therefore spans a
centre-altitude interval comparable to the 0.4-degree “red phase,” and far larger than
the 0.080-degree 226 km window.  Limb darkening and differential refraction prevent a
binary transition.

* **Magnitude:** +/-0.266 degrees about the centre, versus 0.080 degrees claimed at
  226 km.
* **Threshold impact:** material to C4/C5's zero-width and “final phase” language, but
  unlikely to select a different grid point by itself.  Integrate a disk/interval rather
  than move a threshold.

### 9. Ozone, aerosol vertical profile, and twilight colour -- **material to colour, medium-high confidence**

Rayleigh scattering preferentially removes blue light; aerosols alter extinction and
phase function; ozone's Chappuis absorption modifies orange/red transmission.  During
twilight the effective scattering altitude rises and Earth's shadow/ozone-layer geometry
matters.  Multiple-scattered skylight can dominate after direct illumination ends.

* **Magnitude:** large colour and contrast changes but usually modest shifts of the purely
  geometric shadow boundary.
* **Threshold impact:** material to a “fiery” score and why two nominally identical cloud
  fields photograph differently; not grounds to alter 20/40/60% cloud gates without
  outcome data.  AOD and visibility are more useful covariates.

## 3. Verdicts

| Claim | Verdict | Confidence | Strongest evidence |
|---|---|---:|---|
| C1 | **CONFIRMED-WITH-CAVEAT** | 95% for idealisation; 85% operational caveat | `sqrt(2R*1)=112.9 km`, but 2--3 km tops give 160--196 km. |
| C2 | **CONFIRMED-WITH-CAVEAT** | 99% | Direct minimisation and quadratic roots reproduce both corridors; “last direct ray” is narrower than “useful illumination.” |
| C3 | **CONFIRMED-WITH-CAVEAT** | 95% geometric; 80% recommendation | 226 is the 4 km centre and only 20 km inside the 8 km near edge; refracted 342--349 is model-dependent false precision. |
| C4 | **CONFIRMED-WITH-CAVEAT** | 98% point algebra; 85% interpretation | At exactly 113 km the onset equals 2.030-degree last light, but finite width/disk/top uncertainty destroys literal zero width. |
| C5 | **REFUTED** | 97% | Optimisation gives 0.185, not 0.080 degrees, and 2/3 km corridors are 160--479 / 124--515, not one mixed 160--515 corridor. |
| C6 | **REFUTED** | 98% | Its own `sqrt(k)` moves the 4 km corridor's near edge beyond 113 km; near-horizon astronomical refraction is profile-dependent and nonlinear. |
| C7 | **REFUTED** | 99% | Parcel motion is the integral of 3-D evolving cloud-layer wind; a 5 m/s mismatch gives 54 km error in 3 h. |
| C8 | **REFUTED** as phrased; smoothing role confirmed | 95% | Exact spherical solar geometry gives a 14--35-degree +6 to -6 degree azimuth sweep, while a three-point mean erases gap topology. |

## 4. Operational recommendations

### Distances

* **Keep 113 km**, but rename it the “nominal 1 km geometric horizon feature.”  Do not
  state that cloud beyond it is irrelevant.  Replacing it with 120 or 124 km for constant
  refraction would imply accuracy the atmosphere does not have.
* **Keep 226 km and its non-escalatory role.** It is empirically useful and exactly centres
  the nominal 4 km geometry.  Its observed bimodality argues for edge/forecast uncertainty,
  not for granting it veto power.
* **If request budget permits, add 319 km as a separately validated sixth point**, not as a
  replacement for 226 km and not initially as a scoring input.  Persist it, compare it with
  226 km and outcome/reanalysis data, and stratify by diagnosed canvas height.  Do **not**
  hard-code 342--349 km as “the refracted answer.”

### Cone

Do not merely widen `+/-15 degrees`.  At 113 km, +/-15 degrees is +/-29.2 km cross-track,
already almost three nominal 11 km cells, so it is a useful variance estimator.  But an
unweighted mean is the wrong statistic for a transmissive gap, and the solar azimuth sweep
is seasonal and asymmetric.

Persist **centre, minimum, maximum, and spread** (or clear-bearing fraction) rather than only
the mean.  If the product predicts a period rather than the event instant, calculate bearings
at the actual scored times (for example Sun altitude +6, 0, and -6 degrees) rather than widening
a static cone.  In the stated latitude/declination envelope, direct calculation gives:

| Latitude / solar declination | azimuth change, +6 to 0 degrees | 0 to -6 degrees | total |
|---|---:|---:|---:|
| 50 N / equinox | 7.2 degrees | 7.2 degrees | 14.4 degrees |
| 59 N / winter solstice | 21.5 degrees | 13.6 degrees | 35.1 degrees |
| 59 N / summer solstice | 13.6 degrees | 21.5 degrees | 35.1 degrees |

These values come from the standard horizontal-coordinate relations
`sin(h)=sin(phi)sin(dec)+cos(phi)cos(dec)cos(H)` and the corresponding `atan2`
azimuth, not an assumption about a one-hour interval.  Twilight duration itself varies
strongly at these latitudes.

### Percentage and trend thresholds

* **Do not retune the 30 pp near--far drop from the supplied aggregate alone.** The +2.9 pp
  accurate mode supports its use as a softener, while the ~68 pp failure mode says far-cloud
  escalation is unsafe.  Determine a new threshold by out-of-sample outcome skill or at least
  reliability curves stratified by coast, model lead, cloud regime, and cone spread.
* **Do not retune the 20 pp building threshold from physics.** It is a forecast-change
  classifier, not a physical constant.  Validate it against later analysed fields and actual
  photographic outcomes.  Report absolute change, forecast uncertainty, and whether the same
  grid point remains representative.
* **Do not retune the 20/40/60% mean gates without preserving cone structure first.** A mean
  cannot distinguish an open solar bearing from a uniform partial deck.  Changing its cutoffs
  cannot recover discarded topology.

The honest conclusion is that all proposed kilometre-level refinements lie inside the noise of
cloud height, refraction, forecast displacement, and radiative transfer.  The materially useful
changes are richer sampling/persistence, honest labels, terrain awareness, and validation against
photographic outcomes—not extra significant figures.

## 5. Standard references relied upon

1. W. M. Smart, *Textbook on Spherical Astronomy*, 6th ed., revised by R. M. Green,
   Cambridge University Press (1977): horizontal-coordinate transformations and standard
   astronomical refraction.
2. G. G. Bennett, “The calculation of astronomical refraction in marine navigation,”
   *Journal of Navigation* 35 (1982), 255--259,
   [doi:10.1017/S0373463300022037](https://doi.org/10.1017/S0373463300022037).
3. L. H. Auer and E. M. Standish, “Astronomical refraction: computational method for all
   zenith angles,” *Astronomical Journal* 119 (2000), 2472--2474,
   [doi:10.1086/301325](https://doi.org/10.1086/301325).
4. F. Kasten and A. T. Young, “Revised optical air mass tables and approximation formula,”
   *Applied Optics* 28 (1989), 4735--4738,
   [doi:10.1364/AO.28.004735](https://doi.org/10.1364/AO.28.004735).
5. WMO, *International Cloud Atlas*, “Definitions of clouds,” including cloud levels by
   climatic region and genera spanning levels,
   [WMO Cloud Atlas](https://cloudatlas.wmo.int/en/definitions-of-clouds.html).
6. C. F. Bohren and E. E. Clothiaux, *Fundamentals of Atmospheric Radiation*, Wiley-VCH
   (2006): Beer--Lambert attenuation, single/multiple scattering, and cloud radiative transfer.
7. K. N. Liou, *An Introduction to Atmospheric Radiation*, 2nd ed., Academic Press (2002):
   aerosol, ozone, cloud optical depth, phase functions, and multiple scattering.
8. NOAA/NWS, *Solar Calculator: calculation details*, standard solar-position equations and
   the conventional 0.833-degree apparent sunrise/sunset correction,
   [NOAA solar calculations](https://gml.noaa.gov/grad/solcalc/calcdetails.html).
9. Met Office, “Clouds,” overview of cloud types and the dependence of cloud formation on
   condensation, vertical motion, and atmospheric structure,
   [Met Office weather clouds](https://www.metoffice.gov.uk/weather/learn-about/weather/types-of-weather/clouds).
