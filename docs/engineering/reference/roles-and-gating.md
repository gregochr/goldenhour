# Roles, product strategy, role gating UI pattern, planned features

> Moved verbatim from `CLAUDE.md` on 2026-10-22 to keep the always-loaded file small.
> Read the section you need; this file is not loaded automatically.

## Roles

| Role | Permissions |
|------|-------------|
| `ADMIN` | All endpoints + Operations tab |
| `PRO_USER` | Forecast, outcomes, past-window ratings, locations, re-runs |
| `LITE_USER` | Read-only forecast; may also **record** outcomes (`POST /api/outcome`); sees the Map's rating block locked (`PUT /api/outcome/ratings` is PRO/ADMIN) |

> "Read-only" scopes to the *forecast* surface, not the whole API. Recording an outcome is deliberately
> open to any authenticated user: `actual_outcome` is the evidence base for the calibration gate, and
> observations are the scarce input, so restricting who may contribute one works against the thing the
> table exists for. Enforcement lives on the endpoints that *read* premium detail, not on this write.
>
> ⚠️ **The Map tab's per-reader rating is the other way round, on purpose.** `PUT /api/outcome/ratings` (V177 `outcome_rating`, the
> "How was it?" block) is `@PreAuthorize` **PRO_USER and ADMIN**: a LITE write is a 403 whatever the client does, and the client shows
> LITE the block locked (see *Role gating — UI pattern*). `POST /api/outcome` stays open to every role — it is the OLD calibration
> input and has no UI. The two feed the same gate (*Calibration gate*); a LITE account's own `GET /api/outcome/ratings` answers `[]`
> (no role gate on the read). `UserRole`'s javadoc no longer calls outcome recording a LITE right in general. The optional `seen` tag on that PUT (V187) rides the same gate: a LITE tag is a 403.

> **Ask PhotoCast is open to every role; the allowance is the gate** (LITE 3 typed questions a UK day, PRO and ADMIN 30 —
> `GET /api/briefing` has no role variance and Ask reads the same assembly, so every role sees the same ratings in Ask as on the
> Plan tab, and the one PRO-only thing Ask can carry, an aurora RATING — a star, summary, alert level or Kp from `aurora_forecast_result` or `AuroraStateCache`, which `GET /api/aurora/forecast/results` serves to PRO_USER and ADMIN only — is withheld by the same rule: `AskUserContext.canSeeAurora()` (ADMIN or PRO_USER; a user-less Ready conversation never) is derived once from the role and handed to `rank_night_spots` (anyone else gets `auroraAvailable: false` and "aurora ratings are a Pro feature"), `list_windows` (no aurora window), the validator, the thread re-join and the Ready freshness re-check, so a LITE asker is never offered, served or re-served an aurora pick, even from a forged thread reference; a night answer is never stored in the typed cache, so one asker's aurora prose cannot reach another; and the client never calls the aurora endpoint for LITE. The Plan's aurora alert-level hot topic is not a rating: it rides `GET /api/briefing` to every role and Ask's `get_hot_topics` serves it to every role, as before. Ready answers need no allowance.
> `maxDriveMinutes` filters on the asker's OWN stored drive times, so a LITE account with none (the refresh button is Pro-only in
> the UI) gets an explanatory empty result, not an error; nothing is added to give LITE drive times. "Close to home" is `nearHome` (straight-line miles from the saved home, no drive times needed), so that LITE account still gets a local answer if it has a home postcode. "Pro: 30 a day" is text with
> `ProPill`, never a link.

> **Account deletion is open to every role except ADMIN, and the postcode prompt is for every role** (login-registration series,
> 2026-10-17). `DELETE /api/user/account` is `isAuthenticated()` with a password re-check, so a LITE reader deletes their own
> account as freely as a PRO one; an ADMIN's attempt is a 409 (another admin deletes an admin through `DELETE /api/users/{id}`).
> `postcodePromptDue` has no role term, so the sign-in postcode prompt and the registration `HOME` step reach LITE, PRO and
> ADMIN alike — saving a home postcode has always been open to every account, and the Pro line beside it ("Also gives you drive
> times from home") is text shown to every role. The server does not gate drive times by role either: only the Settings refresh
> button is Pro-only in the UI — `POST /drive-times/refresh` has no role gate and `DriveTimeRefreshJob` considers every enabled account with a home.

---


## Product Strategy

See `docs/product/` for detailed reference documents.

**Differentiators**: Claude-generated "why" explanation | AOD+PM2.5 aerosol proxy | Cloud approach risk detection (temporal trend + upwind sampling) | Location type-specific UI | Outcome recording feedback loop

**Freemium split**: LITE gets basic scores, 3-day horizon, blurred metrics. PRO gets enhanced directional scores, 3-day horizon, full metrics.

### Role gating — UI pattern

Every new UI feature must be assessed for role gating.

- **Admin-only** (Operations tab, health widget, Run Forecast): hidden entirely
- **PRO/ADMIN** (all other premium features): visible but greyed out for LITE users
  - `opacity: 0.45`, `pointer-events: none`, `disabled` on interactive elements
  - Upsell text: "Pro" pill badge (inline) or "Upgrade to Pro" (standalone) — ⚠️ text only, never a link: `/upgrade` is not a route and the tests that pin it (`UserSettingsModal.test.jsx`, `MarkerPopupContent.test.jsx`) give the App Store's rule against pointing at a web purchase as the reason; the auth screens' `ProTag` is text in a `<span>`
- **Exception**: elements too small for visual disable (e.g. marker SVG arcs) stay hidden; upsell goes on the nearest tappable surface (the popup)
- Backend 403 gating is the real enforcement; frontend greying is the UX layer
- **A wildlife hide's hourly comfort rows (2026-10-02 assessment): visible to every role.** They carry no rating and no Claude output, and the API serves them to LITE with no gating, so there is nothing to grey. Note plainly that they run to **T+5 for LITE** too — a deliberate difference from the 3-day horizon that applies to SCORED forecasts, because it is public weather data, not a score
- **Ask PhotoCast: Pro and Admin only (2026-10-24 decision; the client is locked for LITE, `ask-pro-gating-plan.md` F1).** To a LITE reader every Ask entry (phone bar, phone Map peek, tablet and desktop field, and the `/` key) is LOCKED with the drive filter's treatment: `.wf-lens-locked` wrapper, a real `disabled`, a `wf-lens-pro` "Pro" pill outside the grey, no upsell words, no link; nothing opens and no `/api/ask` request is made. The backend refusal (403 for LITE) is the next phase (B1) and is not yet in force. The 2026-10-05 assessment that follows, kept for history, said Ask was usable by every role: **Ask PhotoCast was visible and usable by every role (2026-10-05 assessment).** Ask reads the same briefing assembly as `GET /api/briefing`, which has no role variance, and an aurora RATING (`rank_night_spots`, the aurora window in `list_windows`) is withheld from a LITE asker and a user-less conversation by `AskUserContext.canSeeAurora()`, the API's own PRO/ADMIN rule applied in Ask, so nothing PRO-only can reach LITE through it (the aurora alert-level hot topic is on the briefing for every role and is not a rating); a LITE reader asking about the aurora gets the astro forecast and a one-clause "Pro feature" note in words, and the night card draws no aurora pill for them (there is no figure to grey); the allowance (LITE 3, PRO and ADMIN 30 a day) is the gate, and "Pro: 30 a day" is text with `ProPill`, never a link. A LITE reader with no stored drive times gets an explanation for a drive question, not a greyed control
- **Account deletion and the postcode prompt (2026-10-17 assessment): visible and usable by every role.** Settings → Danger zone is drawn for LITE, PRO and ADMIN alike (an admin's press is the server's 409, shown in the server's words); nothing about either is greyed, because neither is a premium feature. The sign-in postcode prompt and the `HOME` step show the Pro line ("Also gives you drive times from home to every location") to every role, as plain text with no link; Settings shows the "Pro accounts also get drive times from home" sentence to non-Pro readers.
- **The Map tab's rating block ("How was it?", 2026-10-16 assessment): every role sees it; the write is Pro/Admin.** A LITE reader gets the block LOCKED: `How was it?` with a `ProPill`, the five stars dimmed to opacity .45 (`.wf-rate-grid.is-locked`) and "Didn't see it" at FULL opacity (the prototype's lock is on the stars alone), and the line `Rate how the sky turned out.` swapped for `Rating is part of Pro.` on any tap — every tap answers in words and calls nothing, and `PUT /api/outcome/ratings` is `@PreAuthorize` PRO_USER/ADMIN, so a forged call is a 403. ⚠️ **Every control is `aria-disabled`, never `disabled`, and the tap is ACCEPTED rather than `pointer-events: none`** (the "what did you see" chip row, valley-mist-plan M3, is drawn dimmed in the same lock and answers the same sentence) — the one deliberate departure from this section's `opacity: 0.45` + `pointer-events: none` + `disabled` pattern: the design wants the tap to answer, and a `disabled` control that held focus drops it to `<body>` (the Ask field's rule). There is no upgrade route to wire, only the words, as with `ProPill` everywhere else. A LITE client reads `GET /api/outcome/ratings` once (it has no rows) and never writes. The Admin's "All ratings" list is Admin-only and absent for the other roles, as the Operations tab is.

---

## Planned Features

- **Prediction accuracy feedback** — structured post-event feedback per user per evaluation (ACCURATE/SLIGHTLY_OFF/VERY_INACCURATE), admin breakdown by model and days_ahead
- **Web Push notifications** — replace Pushover with W3C Push API + VAPID (`webpush-java`), service worker, iOS Home Screen caveat
- **macOS menu bar widget** — Tauri app reusing React components, menu bar icon with best rating

---
