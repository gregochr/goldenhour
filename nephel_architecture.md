# Nephel Platform Architecture

## Overview

**Nephel** is an atmospheric forecasting platform that evaluates weather conditions by altitude and location using AI. The platform powers specialized applications for different domains, starting with landscape photography and outdoor walking.

Core concept: One powerful forecasting engine, multiple market entry points.

---

## Platform Structure

### Three Repositories

#### 1. **Nephel** (Core Platform)
- **Purpose:** Atmospheric forecasting engine
- **Responsibilities:**
  - Weather data pipeline (Open-Meteo API integration)
  - Altitude-aware condition evaluation
  - AI evaluation layer (Claude API)
  - User authentication & session management
  - Data persistence
  - Shared utilities & core logic
- **Technology:** Java (Spring Boot)
- **Deployment:** Mac Mini (shared backend)

#### 2. **Nephel-Photography**
- **Purpose:** Photography-focused specialization
- **Extends:** Nephel core
- **Responsibilities:**
  - Sunrise/sunset color potential forecasting
  - Golden hour quality scoring
  - Camera equipment recommendations
  - Visibility & lighting conditions
  - Split-toning/post-processing guidance based on predicted conditions
  - **Aurora potential** (personal feature) – combines AuroraWatch alerts with clear sky forecasts & comfort metrics
- **Technology:** Java (Spring Boot, inheritance from Nephel)
- **Deployment:** Mac Mini (separate server instance)
- **App Name:** **PhotoCast** | Powered by Nephel and AI

**Aurora Feature Notes:**
- Currently gated to `chrisgrgory` user only (personal use)
- Uses AuroraWatch UK API (non-commercial personal use)
- Moscow: Could Have (expand to other users only after resolving commercial licensing with AuroraWatch UK)
- Feature disabled for all other users by default

#### 3. **Nephel-Walking**
- **Purpose:** Fell walking & hill walking specialization
- **Extends:** Nephel core
- **Responsibilities:**
  - Wainwright & fell forecasts by elevation
  - Haiku AI summaries ("Good walking day? Yes, but icy on tops—extra layers needed")
  - Hazard detection (windchill, icing, visibility, UV)
  - Layer & kit recommendations
  - Trail-specific condition warnings
- **Technology:** Java (Spring Boot, inheritance from Nephel)
- **Deployment:** Mac Mini (separate server instance)
- **App Name:** [TBD] | Powered by Nephel and AI

---

## Runtime Architecture

### Deployment Model

**Current (MVP):**
- **Nephel Core Server:** Single shared instance
- **PhotoCast Server:** Separate instance extending Nephel
- **Walking App Server:** Separate instance extending Nephel
- **Host:** Mac Mini (Durham)

**Future Scaling:**
- **Phase 2+:** Migrate to AWS (or equivalent cloud provider)
- **Architecture remains unchanged:** Three servers still running independently
- **Cloud benefits:** Auto-scaling, geographic distribution, higher availability
- **Migration path:** Containerize (Docker) for easier cloud deployment
- **No code changes required:** Java inheritance model is cloud-agnostic

### Server Communication
- Photography & Walking servers call Nephel core for baseline forecasting
- Each specialization adds domain-specific AI evaluation
- Shared auth/session layer via Nephel core

---

## Data Sources

### Primary
- **Open-Meteo API** – Global weather data by altitude/location (free, no auth)
- **Claude API** – AI evaluation & summarization (Sonnet 4.5)

### Secondary
- **WorldTides API** – Tidal data (integrated, available for coastal features)
- **AuroraWatch UK API** – Geomagnetic activity alerts for UK/Ireland (free, non-commercial)

### Geography
- **No geographic constraints** – all APIs are global
- **Launch focus:** Lake District (photography), UK fells (walking)
- **Future expansion:** Anywhere with outdoor use cases

---

## Domains

- **nephel.app** – Primary platform domain
- **nephel.com** – Secondary/backup domain
- **Status:** Both available for registration

---

## Launch Sequence

### Phase 1: Platform & Photography
1. Build Nephel core platform
2. Deploy PhotoCast (photography app first)
3. Validate forecasting accuracy with real users
4. Iterate based on photographer feedback

### Phase 2: Walking Specialization
1. Extend Nephel with walking-specific logic
2. Launch walking app (second entry point)
3. Cross-promote between apps

### Phase 3: Optionality
- Additional specializations possible (climbing, trail running, construction, etc.)
- API/partner licensing potential
- Geographic expansion based on demand

---

## Key Design Principles

1. **Nephel is the moat** – The atmospheric forecasting + AI evaluation engine is defensible IP
2. **Specializations are lightweight** – Domain-specific apps are UI & messaging layers on solid platform
3. **Java inheritance model** – Clean code reuse, independent iteration
4. **Single deployment host** – Mac Mini runs all three servers
5. **Global-first thinking** – No geographic lock-in
6. **User-focused summarization** – AI converts raw data into actionable insights (haikus, recommendations)

---

## Technology Stack

- **Language:** Java
- **Framework:** Spring Boot
- **Deployment:** Mac Mini running multiple Spring Boot instances
- **APIs:** Open-Meteo (weather), Claude API (AI), WorldTides (optional)
- **Auth:** TBD (shared across platform)
- **Frontend:** TBD (likely Tauri for desktop, web for browser)

---

## Future Use Cases (Not Planned Yet)

The platform architecture supports:
- **Climbing** – freeze-thaw cycles, wind on crags, visibility
- **Trail running** – footing conditions, heat stress, visibility
- **Paragliding** – thermal conditions, wind patterns
- **Construction/utilities** – weather windows, safety briefings
- **Agriculture** – frost forecasts, spray windows
- **Wildlife photography** – animal behavior + lighting + weather
- **Astronomy** – cloud cover, seeing conditions
- **Fishing** – pressure systems, water conditions

---

## Refactoring Strategy: Extracting Nephel Core

### Key Decisions

#### 1. Artifact Distribution
- **Nephel published as Maven artifact** (`com.gregochr:nephel:1.0.0`)
- PhotoCast and Walking depend on artifact, not shared repo
- Enables versioning, clean boundaries, independent upgrades
- Alternative (git submodule) rejected — too tightly coupled

#### 2. Code Split: What Moves to Nephel
**Nephel owns:**
- Authentication & JWT
- User & session management
- Weather data pipeline (Open-Meteo integration)
- Forecast evaluation engine (Claude API)
- Location & forecast data models
- Job run metrics & API call logging
- Shared utilities
- All base migrations (V1–V21)

**PhotoCast retains:**
- `GoldenHourType`, sunrise/sunset azimuth logic
- Photography-specific evaluation prompts
- Camera equipment recommendations
- Photography-focused UI & components
- PhotoCast-specific endpoints (if any)

**Walking app retains:**
- Elevation-aware hazard logic
- Haiku summaries & layer recommendations
- Trail-specific warnings
- Walking-focused UI & components

#### 3. Database Schema Ownership
- **Nephel owns base schema:** `user`, `location`, `forecast_evaluation`, `job_run`, `api_call_log`, `tide_extreme`, `refresh_token`
- **PhotoCast uses same DB** — no separate tables needed initially
- **Future specializations** (Walking, Climbing) use identical schema
- If a specialization needs custom tables (e.g., gear recommendations, climb logs), those tables live in the same database but can be managed by that app's migrations

#### 4. Extraction Timeline
- **Extract Nephel AFTER PhotoCast stabilizes** (estimated 2–3 weeks)
- **Reasoning:**
  - PhotoCast is still iterating (Aurora feature, UI refinement, etc.)
  - Extracting mid-iteration creates churn and dual-migration headaches
  - Waiting ensures proven patterns and fewer surprises
  - Walking app can then launch with clean, stable platform
- **Sequence:**
  1. Ship PhotoCast to production
  2. Let it stabilize with real user feedback
  3. Extract Nephel as Maven artifact
  4. Rebase PhotoCast on Nephel artifact (minimal code change)
  5. Walking app launches with clean Nephel dependency

### Constraints & Assumptions

- **Java inheritance model** — PhotoCast and Walking extend Spring Boot app, not reuse code via composition
- **Single deployment host** (Mac Mini) for MVP — all three servers run independently
- **Cloud migration later** — Docker containerization planned but not required for Nephel split
- **No geographic constraints** — all APIs are global

## Notes for Development

- Photography app (PhotoCast) launches first – uses existing domain expertise
- Walking app second – validates platform with different use case
- Both apps inherit from Nephel without modifying core
- Each specialization has its own repo for independent iteration
- Platform remains geographically flexible for future expansion
