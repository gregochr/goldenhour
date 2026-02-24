# Freemium UI Strategy — Breadcrumbs, Not Paywalls

## Principle

Don't hide features behind a wall. Show them existing, then gate access.
The goal is discovery — users should stumble across depth they didn't know they wanted,
not be confronted with an upgrade prompt before they've seen what they're missing.

---

## The Three Breadcrumb Moments

### 1. Obfuscation (blur/lock)

Show the number exists but make it unreadable:

```
Cloud Layer Detail
──────────────────
Low    ███░░░   [blurred]%
Mid    ████░░   [blurred]%
High   █████░   [blurred]%

🔒 Unlock technical metrics — upgrade to Pro
```

**Why it works:** The user can see there's more information. They're curious. The
upgrade prompt feels like a solution, not an intrusion.

**Use for:** Cloud layer breakdown, aerosol metrics, full technical tier

---

### 2. Soft Limits (show N, tease N+1)

Show the first item freely, indicate more exist:

```
Forecast — Next 3 Days
──────────────────────
Today     🔥 78%   Worth going out
Tomorrow  🌅 62%   Reasonable
Day 3     ░░ ??%   Upgrade for 7-day →
```

**Why it works:** They get value immediately (3 days). The limit is logical, not
arbitrary. Photographers naturally want to plan ahead.

**Use for:** Forecast horizon (Lite: 3 days, Pro: 7 days), location count
(Lite: 1 location, Pro: unlimited)

---

### 3. Discovery Moments (see it once, then gate)

Let them use a feature once or twice before asking for upgrade:

```
[First time viewing aerosol data]
"Dust particles detected — these enhance warm sunset reds.
 Learn more about how aerosols affect your forecast →"

[Second time]
"AOD: 0.42 — optimal range for enhanced colour.
 Upgrade to Pro to always see aerosol detail."
```

**Why it works:** They've already seen the value. The upgrade converts because they've
experienced what they'd lose.

**Use for:** Aerosol breakdown, directional hints, peak timing window

---

## Feature Split — Lite vs Pro

| Feature | Lite | Pro |
|---------|------|-----|
| Score (Fiery Sky %, Golden Hour %) | ✓ | ✓ |
| One-line Claude summary | ✓ | ✓ |
| "Why" explanation (3 key factors) | ✓ | ✓ |
| Forecast horizon | 3 days | 7 days |
| Number of locations | 1 | Unlimited |
| Cloud layer breakdown (low/mid/high) | Blurred | ✓ |
| Aerosol metrics (AOD, dust, PM2.5) | Blurred | ✓ |
| Full technical metrics | Blurred | ✓ |
| Directional map hint | — | ✓ |
| Outcome recording | ✓ | ✓ |
| Alerts / notifications | Basic | Configurable |
| Forecast updated timestamp | ✓ | ✓ |
| Historical accuracy stats | — | ✓ |

---

## What Lite Must NOT Do

- Show a paywall on first open
- Block the core score (that's the product's core value)
- Truncate Claude's summary — that's the differentiator vs competitors
- Feel punishing — every Lite interaction should feel complete

## Upgrade Copy Principles

- **Avoid:** "Upgrade to unlock"
- **Use:** "Pro members see this" / "See what's behind the numbers"
- **Never:** Show a prompt on every page load
- **Do:** Surface the prompt at the moment of discovery

## ADMIN Role

Admin users always see everything — no gating. They need full metrics for debugging
forecast quality and user management.
