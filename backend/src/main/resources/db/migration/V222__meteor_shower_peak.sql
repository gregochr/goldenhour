-- V222: the IMO peak night and rate of each meteor shower, per calendar year (meteor-showers-plan Phase 2).
--
-- A shower's peak moves by a day or two between years (the Geminids are the night of 13-14 Dec in 2026, the
-- Orionids 20 Oct), so the dates live here rather than as one MonthDay in code. MeteorPeakCalendar reads this table
-- at start-up and falls back to the code's MonthDay for a year with no row. peak_night is the EVENING date of the
-- night of maximum: a night runs 18:00 to 18:00 UK time (MeteorPeakNight.of). max_utc is the maximum IMO gives, or
-- NULL when it gives a date only. zhr is IMO's figure for that year ("80+" stored as 80).
--
-- Seeded for 2026 and 2027 from the IMO calendars read on 2026-11-07. source_sha256 is NULL for those rows; the
-- Phase 4 refresh job writes it. The column is VARCHAR(64), not CHAR(64): Postgres reports CHAR as bpchar, which
-- Hibernate's ddl-auto validate rejects against a String.
CREATE TABLE meteor_shower_peak (
    shower_code   VARCHAR(3)               NOT NULL,
    calendar_year INTEGER                  NOT NULL,
    peak_night    DATE                     NOT NULL,
    max_utc       TIMESTAMP WITH TIME ZONE,
    zhr           INTEGER                  NOT NULL,
    source_url    VARCHAR(255)             NOT NULL,
    source_sha256 VARCHAR(64),
    fetched_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_meteor_shower_peak PRIMARY KEY (shower_code, calendar_year),
    CONSTRAINT ck_meteor_shower_peak_sha CHECK (source_sha256 IS NULL OR length(source_sha256) = 64)
);

INSERT INTO meteor_shower_peak
    (shower_code, calendar_year, peak_night, max_utc, zhr, source_url, source_sha256, fetched_at)
SELECT v.shower_code, v.calendar_year, v.peak_night, v.max_utc, v.zhr,
       'https://www.imo.net/files/meteor-shower/cal' || v.calendar_year || '.pdf',
       NULL,
       TIMESTAMPTZ '2026-11-07 00:00:00+00'
FROM (VALUES
    ('QUA', 2026, DATE '2026-01-03', TIMESTAMPTZ '2026-01-03 21:00:00+00', 80),
    ('LYR', 2026, DATE '2026-04-22', TIMESTAMPTZ '2026-04-22 19:40:00+00', 18),
    ('ETA', 2026, DATE '2026-05-05', NULL, 50),
    ('SDA', 2026, DATE '2026-07-30', NULL, 25),
    ('PER', 2026, DATE '2026-08-12', TIMESTAMPTZ '2026-08-13 03:00:00+00', 100),
    ('DRA', 2026, DATE '2026-10-08', TIMESTAMPTZ '2026-10-09 01:00:00+00', 5),
    ('ORI', 2026, DATE '2026-10-20', NULL, 20),
    ('STA', 2026, DATE '2026-11-05', NULL, 7),
    ('NTA', 2026, DATE '2026-11-12', NULL, 5),
    ('LEO', 2026, DATE '2026-11-17', TIMESTAMPTZ '2026-11-17 23:45:00+00', 15),
    ('GEM', 2026, DATE '2026-12-13', TIMESTAMPTZ '2026-12-14 14:00:00+00', 150),
    ('URS', 2026, DATE '2026-12-22', TIMESTAMPTZ '2026-12-22 22:00:00+00', 10),
    ('QUA', 2027, DATE '2027-01-03', TIMESTAMPTZ '2027-01-04 03:25:00+00', 80),
    ('LYR', 2027, DATE '2027-04-22', TIMESTAMPTZ '2027-04-23 01:40:00+00', 18),
    ('ETA', 2027, DATE '2027-05-05', TIMESTAMPTZ '2027-05-06 09:00:00+00', 50),
    ('SDA', 2027, DATE '2027-07-30', NULL, 25),
    ('PER', 2027, DATE '2027-08-12', TIMESTAMPTZ '2027-08-13 09:00:00+00', 110),
    ('DRA', 2027, DATE '2027-10-08', TIMESTAMPTZ '2027-10-09 07:00:00+00', 5),
    ('ORI', 2027, DATE '2027-10-21', NULL, 20),
    ('STA', 2027, DATE '2027-11-06', NULL, 7),
    ('NTA', 2027, DATE '2027-11-13', NULL, 5),
    ('LEO', 2027, DATE '2027-11-17', TIMESTAMPTZ '2027-11-18 06:00:00+00', 15),
    ('GEM', 2027, DATE '2027-12-14', TIMESTAMPTZ '2027-12-14 20:00:00+00', 150),
    ('URS', 2027, DATE '2027-12-22', TIMESTAMPTZ '2027-12-23 04:00:00+00', 10)
) AS v (shower_code, calendar_year, peak_night, max_utc, zhr);
