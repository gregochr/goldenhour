#!/usr/bin/env bash
#
# Generate a prompt regression test fixture from real atmospheric data.
#
# Usage:
#   ./generate-regression-fixture.sh <lat> <lon> <date> <sunset_hour_utc> <azimuth> <location_name>
#
# Example (Copt Hill, 5 March 2026 sunset):
#   ./generate-regression-fixture.sh 54.84 -1.47 2026-03-05 17 260 "Copt Hill"
#
# The script:
#   1. Fetches observer-point weather + air quality from Open-Meteo (past_days)
#   2. Calculates 50 km solar/antisolar offset coordinates
#   3. Fetches directional cloud data at both offset points
#   4. Outputs a Java test fixture (AtmosphericData constructor) ready to paste
#
set -euo pipefail

if [ $# -lt 6 ]; then
    echo "Usage: $0 <lat> <lon> <date> <sunset_hour_utc> <azimuth_deg> <location_name>"
    echo "Example: $0 54.84 -1.47 2026-03-05 17 260 \"Copt Hill\""
    exit 1
fi

LAT=$1
LON=$2
DATE=$3
HOUR=$4
AZIMUTH=$5
LOCATION=$6

# Calculate days ago for past_days parameter
TODAY=$(date +%Y-%m-%d)
DAYS_AGO=$(python3 -c "
from datetime import date
d1 = date.fromisoformat('$TODAY')
d2 = date.fromisoformat('$DATE')
print((d1 - d2).days)
")

if [ "$DAYS_AGO" -lt 0 ]; then
    echo "Error: date $DATE is in the future"
    exit 1
fi

echo "// === Prompt Regression Fixture: $LOCATION, $DATE, ${HOUR}:00 UTC ==="
echo "// Generated $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "// Observer: $LAT, $LON | Azimuth: ${AZIMUTH}deg | past_days: $DAYS_AGO"
echo ""

# Fetch observer-point forecast
FORECAST=$(curl -s "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility,wind_speed_10m,wind_direction_10m,precipitation,weather_code,relative_humidity_2m,surface_pressure,shortwave_radiation,boundary_layer_height,temperature_2m,apparent_temperature,precipitation_probability&wind_speed_unit=ms&timezone=UTC&past_days=$DAYS_AGO&forecast_days=1")

# Fetch air quality
AIR_QUALITY=$(curl -s "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$LAT&longitude=$LON&hourly=pm2_5,dust,aerosol_optical_depth&timezone=UTC&past_days=$DAYS_AGO&forecast_days=1")

# Calculate offset points using Haversine forward formula
OFFSETS=$(python3 -c "
import math, json

lat, lon = $LAT, $LON
azimuth = $AZIMUTH
R = 6371000
d = 50000

def offset_point(lat, lon, bearing_deg, dist_m):
    lat_r, lon_r = math.radians(lat), math.radians(lon)
    br = math.radians(bearing_deg)
    dr = dist_m / R
    new_lat = math.asin(math.sin(lat_r)*math.cos(dr) + math.cos(lat_r)*math.sin(dr)*math.cos(br))
    new_lon = lon_r + math.atan2(math.sin(br)*math.sin(dr)*math.cos(lat_r), math.cos(dr) - math.sin(lat_r)*math.sin(new_lat))
    return round(math.degrees(new_lat), 4), round(math.degrees(new_lon), 4)

solar = offset_point(lat, lon, azimuth, d)
antisolar = offset_point(lat, lon, (azimuth + 180) % 360, d)
print(json.dumps({'solar_lat': solar[0], 'solar_lon': solar[1], 'antisolar_lat': antisolar[0], 'antisolar_lon': antisolar[1]}))
")

SOLAR_LAT=$(echo "$OFFSETS" | python3 -c "import json,sys; print(json.load(sys.stdin)['solar_lat'])")
SOLAR_LON=$(echo "$OFFSETS" | python3 -c "import json,sys; print(json.load(sys.stdin)['solar_lon'])")
ANTISOLAR_LAT=$(echo "$OFFSETS" | python3 -c "import json,sys; print(json.load(sys.stdin)['antisolar_lat'])")
ANTISOLAR_LON=$(echo "$OFFSETS" | python3 -c "import json,sys; print(json.load(sys.stdin)['antisolar_lon'])")

echo "// Solar offset (50km @ ${AZIMUTH}deg): $SOLAR_LAT, $SOLAR_LON"
echo "// Antisolar offset (50km @ $(( (AZIMUTH + 180) % 360 ))deg): $ANTISOLAR_LAT, $ANTISOLAR_LON"

# Fetch directional cloud data
SOLAR_CLOUD=$(curl -s "https://api.open-meteo.com/v1/forecast?latitude=$SOLAR_LAT&longitude=$SOLAR_LON&hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high&wind_speed_unit=ms&timezone=UTC&past_days=$DAYS_AGO&forecast_days=1")
ANTISOLAR_CLOUD=$(curl -s "https://api.open-meteo.com/v1/forecast?latitude=$ANTISOLAR_LAT&longitude=$ANTISOLAR_LON&hourly=cloud_cover_low,cloud_cover_mid,cloud_cover_high&wind_speed_unit=ms&timezone=UTC&past_days=$DAYS_AGO&forecast_days=1")

# Extract values at the target hour and output Java fixture
python3 -c "
import json

forecast = json.loads('''$FORECAST''')
aq = json.loads('''$AIR_QUALITY''')
solar_cloud = json.loads('''$SOLAR_CLOUD''')
antisolar_cloud = json.loads('''$ANTISOLAR_CLOUD''')

target = '${DATE}T${HOUR}:00'
h = forecast['hourly']
a = aq['hourly']
sc = solar_cloud['hourly']
ac = antisolar_cloud['hourly']

fi = next(i for i, t in enumerate(h['time']) if t == target)
ai = next(i for i, t in enumerate(a['time']) if t == target)
si = next(i for i, t in enumerate(sc['time']) if t == target)
asi = next(i for i, t in enumerate(ac['time']) if t == target)

print('''
AtmosphericData data = new AtmosphericData(
        \"$LOCATION\",
        LocalDateTime.of(${DATE//\-/, }, $HOUR, 0),
        TargetType.SUNSET,
        %d,      // lowCloudPercent (observer)
        %d,      // midCloudPercent (observer)
        %d,      // highCloudPercent (observer)
        %d,      // visibilityMetres
        new BigDecimal(\"%s\"),  // windSpeedMs
        %d,      // windDirectionDegrees
        new BigDecimal(\"%s\"),  // precipitationMm
        %d,      // humidityPercent
        %d,      // weatherCode
        %d,      // boundaryLayerHeightMetres
        new BigDecimal(\"%s\"),  // shortwaveRadiationWm2
        new BigDecimal(\"%s\"),  // pm25
        new BigDecimal(\"%s\"),  // dustUgm3
        new BigDecimal(\"%s\"),  // aerosolOpticalDepth
        %s,      // temperatureCelsius
        %s,      // apparentTemperatureCelsius
        %d,      // precipitationProbability
        new DirectionalCloudData(
                %d, %d, %d,   // solar horizon: Low, Mid, High
                %d, %d, %d),  // antisolar horizon: Low, Mid, High
        null, null, null, null, null, null);  // no tide data
''' % (
    h['cloud_cover_low'][fi], h['cloud_cover_mid'][fi], h['cloud_cover_high'][fi],
    int(h['visibility'][fi]),
    h['wind_speed_10m'][fi], int(h['wind_direction_10m'][fi]),
    h['precipitation'][fi],
    int(h['relative_humidity_2m'][fi]),
    int(h['weather_code'][fi]),
    int(h['boundary_layer_height'][fi]),
    h['shortwave_radiation'][fi],
    a['pm2_5'][ai], a['dust'][ai], a['aerosol_optical_depth'][ai],
    h['temperature_2m'][fi], h['apparent_temperature'][fi],
    int(h['precipitation_probability'][fi]),
    sc['cloud_cover_low'][si], sc['cloud_cover_mid'][si], sc['cloud_cover_high'][si],
    ac['cloud_cover_low'][asi], ac['cloud_cover_mid'][asi], ac['cloud_cover_high'][asi],
))
"

echo ""
echo "// TODO: Set score ceilings based on your actual observation:"
echo "// assertTrue(result.rating() <= ??);"
echo "// assertTrue(result.fierySkyPotential() <= ??);"
echo "// assertTrue(result.goldenHourPotential() <= ??);"
