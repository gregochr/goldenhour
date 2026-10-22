# Handoff: Postcode capture + auth screen tidy-up

Repo: `gregochr/goldenhour` (main @ 519162b at time of writing). Frontend is React 19 + Vite + Tailwind 4; backend Spring Boot 4.

## Overview
The app needs the user's home postcode for light times (every account) and drive times (PRO_USER/ADMIN). Today the postcode can only be set in **Settings**; registration and sign-in never ask for it. This bundle:

1. Adds a postcode step to registration and a one-time prompt on sign-in.
2. Fixes mobile-browser issues on the auth and Settings screens, before the iOS/iPadOS apps.
3. Fixes several auth issues: consent default, password reset, friction, and visual drift from the rest of the app.

Native-only work (Xcode Associated Domains, App Attest, iPad layout tuning, Sign in with Apple) is **out of scope**.

## About the design files
`Postcode Capture.html` is a **design reference built in HTML**, not production code. Rebuild it in the existing React components using the existing Tailwind tokens and classes (`card`, `btn-primary`, `btn-secondary`, `plex-*` colours, `BrandLockup variant="auth"`). Frames 1–4 are the new screens. Frames 5–8 compare the current screens with the suggested ones.

## Fidelity
High-fidelity for layout, copy and tokens. **One known deviation:** the mockup's inputs copy the current auth input style. Implement them to the unified input style in task 13 instead.

## Before you start: check these first
- **Forgot password:** is there a password-reset flow anywhere (backend or frontend)? `LoginPage.jsx` has no link to one.
- **Account deletion:** is there a delete-account endpoint and UI?
- **Set-password Turnstile:** how does `AuthController` validate the Turnstile token on `setPasswordForNewUser`? This affects task 11.
- **Login with Turnstile unavailable:** how does the backend handle a sign-in whose Turnstile failed to load ("we'll verify your sign-in another way")? Task 9 mirrors it.
- **Unique emails:** is `app_user.email` unique and case-insensitively matchable? This affects task 12.

Read `CLAUDE.md` before touching settings or the database. The settings columns on `app_user` are `updatable = false` and are written through dedicated paths. Follow that pattern.

---

## Tasks

### A. Postcode capture

**1. Registration: new `HOME` step** (`RegisterPage.jsx`)
- Add `HOME` to `STEPS`, between `SET_PASSWORD` and `SUCCESS`. After `setPasswordForNewUser` → `completeRegistration(data)`, go to `HOME` instead of `SUCCESS`.
- The user is authenticated at this point, so reuse `lookupPostcode` and `saveHome` from `api/settingsApi.js`. No backend change is needed for the basic flow.
- Copy (verbatim):
  - Heading: **Where's home?**
  - Sub: *Your postcode sets the sunrise and sunset times shown at the top of the app.*
  - Field label: **Home postcode**. Placeholder: *Enter UK postcode*. Button: **Look up** (shows *Looking up…* while busy).
  - After lookup, replace the field with a confirmation row: ✓ `{placeName}` with `{postcode}` beneath it in mono 11px, and a **Change** text button that restores the field.
  - Error: *Invalid postcode* (the same string Settings uses).
  - Pro line: a small `PRO` tag followed by *Also gives you drive times from home to every location.* Shown to every role, because new accounts default to LITE. **No upgrade link** (App Store rules).
  - Primary: **Save and continue**, disabled until a lookup succeeds. Secondary text button: **Skip for now**.
- Save calls `saveHome(postcode, lat, lon, null)`. Pass a null radius so the column stays NULL, as `UserSettingsModal` does. Save and Skip both go to `SUCCESS`.
- The `SUCCESS` step and its 2s reload stay, now after `HOME`.

**2. Sign-in: one-time prompt when no postcode is saved**
- After a successful login, if `homePostcode == null` and the prompt hasn't been dismissed, show frame 4 before the app shell. Same card layout as the auth screens.
- Copy:
  - Heading: **Set your home postcode**
  - Sub: *You haven't saved one yet. It sets your light times, and you can change it any time in Settings.*
  - Field, lookup, confirmation and error behave as in task 1.
  - Buttons: **Save** and **Not now**.
- Store the dismissal **server-side**, so it carries across devices and the iOS app. Add a nullable `postcode_prompt_dismissed_at` timestamp on `app_user` (new Flyway migration), set by a no-body `PUT /api/user/settings/postcode-prompt-dismissed`. Copy the existing `PUT /coming-up-seen` endpoint and its service method (`markComingUpSeen`). Include the field in `UserSettingsResponse`.
- Saving a postcode also ends the prompt for good, because `homePostcode` is no longer null.
- Existing users without a postcode see the prompt once. That's intended.

**3. "Use my location" (optional link under the postcode field, in tasks 1, 2 and Settings)**
- Text button: **Use my location**. Calls `navigator.geolocation.getCurrentPosition` with `{ enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 }`.
- New backend endpoint `POST /api/user/settings/home/reverse` with body `{ latitude, longitude }`. Call postcodes.io `GET /postcodes?lon={lon}&lat={lat}&limit=1` through `PostcodesIoClient` (add a `reverseLookup` method) and return the same `PostcodeLookupResult` shape as `/home/lookup`. Results land in the same confirmation row.
- Errors:
  - Permission denied: *Location access is off. Enter your postcode instead.*
  - No UK postcode found: *We couldn't find a UK postcode for your location.*
  - Timeout or unavailable: *Couldn't get your location. Enter your postcode instead.*
- Hide the link when `navigator.geolocation` is missing.
- Rate-limit the endpoint the same way as `/home/lookup`, if that has a limit.

### B. Mobile-browser fixes (auth + Settings)

**4. Postcode input attributes** (every postcode field, including `UserSettingsModal`)
`autoComplete="postal-code" autoCapitalize="characters" autoCorrect="off" spellCheck={false} inputMode="text" enterKeyHint="search"`. Never `inputMode="numeric"`.

**5. 16px inputs on small screens.** iOS Safari zooms into any input under 16px. Every auth input (and the Settings postcode field) is `text-sm` (14px). Use `text-base sm:text-sm`, or 16px everywhere if that looks fine.

**6. Card position and viewport height on phones.** Auth wrappers use `min-h-screen flex items-center justify-center`. Change to `min-h-dvh`. On phones, align to the top with comfortable padding (e.g. `items-start pt-12 sm:items-center sm:pt-0`) so the keyboard doesn't cover the buttons.

**7. Safe areas.** Add `env(safe-area-inset-top/bottom/left/right)` padding to the auth page wrappers and the app's top bar and bottom-anchored controls. Check that `<meta name="viewport">` includes `viewport-fit=cover`.

**8. Enter key and autofocus.**
- Enter in a postcode field runs the lookup (Settings already does this: `handleKeyDown`).
- Put the cursor in the postcode field automatically only on devices with a fine pointer: `window.matchMedia('(pointer: fine)').matches`. This stops phones popping the keyboard up.

**9. Turnstile load-failure fallback on registration and Set password.** `LoginPage` handles `onLoadFail` (it shows *Security check unavailable — we'll verify your sign-in another way.* and allows the submit). `RegisterPage` doesn't, so a blocked Turnstile (ad-blockers, some in-app browsers) is a dead end. Mirror the login behaviour on both forms, front and back.

### C. Auth fixes

**10. Marketing opt-in unticked by default.** `RegisterPage.jsx`: `useState(true)` → `useState(false)` for `marketingOptIn`. Required under UK PECR/GDPR. Update `RegisterPage.test.jsx`.

**11. Registration form changes**
- **Remove "Confirm email"** (field, state, `emailsMatch`, error). The verification link already confirms the address.
- **Email first,** then username. Username hint: *3–30 letters, numbers, - or _*.
- **Say why the button is disabled.** While terms are unticked, show *Agree to the terms to continue* under Create account (12px, `text-plex-text-secondary`, centred).
- **Remove the second Turnstile** on Set password, in the frontend and backend. The single-use email token already proves a real person. Keep rate limiting on the endpoint.

**12. Sign-in changes**
- **Forgot password?** link, right-aligned on the Password label row (12px, `text-plex-gold`). If no reset flow exists, build one:
  - Request form (email only; always answers *If that email has an account, we've sent a reset link.*).
  - Email with a single-use token. Reuse the `email_verification_token` pattern, or a sibling table, with a 1h expiry and the same rate limit as resend (3 per 5 min).
  - `/reset?token=…` page that reuses the Set password form and checklist.
  - Invalidate refresh tokens on reset.
- **Username or email.** Label: **Username or email**. If the identifier contains `@`, look up by email (case-insensitive) in `AuthenticationService`, otherwise by username. Error copy stays generic.
- **Session-expired message.** Move it inside the card, left-aligned, above the heading.

**13. Make the auth screens match the rest of the app**
- **Headings.** Replace `text-sm font-semibold` card headings with Newsreader serif: `font-serif font-semibold text-[22px] leading-tight tracking-[-0.015em] text-plex-text`. Optionally add a mono kicker above, using `font-mono text-[10px] uppercase tracking-[0.2em] text-plex-text-muted`. **Not coral:** `index.css` confines coral to the logo lockup.
- **One input style** shared by auth and Settings. Extract a `.field` class in `index.css`'s components layer: `w-full bg-plex-bg border border-plex-border rounded-lg px-3 py-2 text-base sm:text-sm text-plex-text placeholder:text-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold`. The error state swaps the border to the error token. Use it in `LoginPage`, `RegisterPage`, `UserSettingsModal` and the new screens.
- **Status colours as tokens.** Add `--color-plex-error` (about `#F07A62`; check ≥4.5:1 on `#221A15`) and `--color-plex-ok` (`#8AAE72` is fine as a value, but keep it a separate token from `--color-verdict-go`, because `index.css` reserves verdict colours for forecast meaning). Replace raw `red-400` / `red-700` / `amber-400` / `green-400` / `green-500` in the auth screens and `UserSettingsModal`. Leave the role badges unless you want to retune them too.
- **Password checklist.** Met rules: ✓ in `plex-ok`. Unmet rules: ○ in `text-plex-text-secondary`, not ✗ in muted ink.
- **Hint text.** Use `text-plex-text-secondary`, not `text-plex-text-muted`, for 12px helper text: muted is about 42% ink, which is borderline for 4.5:1.
- **Alignment.** Left-align the session-expired, Check your email, Verify, Waitlist and Success cards. The logo is deliberately left-aligned, and centred cards clash with it.

**14. Pro copy without an upgrade link.** In `UserSettingsModal`, change *Your postcode sets your light times. Upgrade to Pro for personalised drive times.* to *Your postcode sets your light times. Pro accounts also get drive times from home.* Also check other "Upgrade" copy for links to a web purchase.

### D. Server and website prep for the native apps

**15. Account deletion** (if it doesn't already exist). Required in-app for the App Store.
- `DELETE /api/user/account`, which re-asks for the password.
- Delete or anonymise the user's rows: drive times, refresh tokens, Ask usage, settings.
- **Delete account** button at the bottom of Settings (`btn-secondary`), behind a confirm dialog that asks for the password.
- Send a confirmation email.

**16. `apple-app-site-association` file**
- Serve `/.well-known/apple-app-site-association` (JSON, `Content-Type: application/json`, no redirect, no extension) from the app's domain.
- Include `webcredentials.apps: ["<TEAMID>.<bundle id>"]` and `applinks.details` covering `/verify*` and `/reset*`.
- Leave TEAMID and bundle ID as placeholders until they exist.

**17. App-friendly email links.** Make sure verification and reset emails link to clean paths (`https://<app domain>/verify?token=…`, `/reset?token=…`) that work in a browser today, so the iOS app can claim them later with no email change.

**18. Privacy answers.** Add `docs/app-store-privacy.md` listing the data collected:
- Email, username: account.
- Home postcode and lat/lon: coarse location, linked to the user, used for app functionality.
- Marketing consent.
- Usage logs.

Also update `landing/privacy.html` if location isn't mentioned.

---

## Design tokens (from `frontend/src/index.css` `@theme`)
- bg `#181210` · surface `#221A15` · surface-light `#2A2019` · border `#3A2C23` · border-light `#4A3A2E`
- text / gold `#F2E7D3` · gold-light `#FBF4E6` · text-secondary `rgba(242,231,211,.66)` · text-muted `rgba(242,231,211,.42)`
- coral `#E8593F` (logo lockup only) · verdict go `#8AAE72` / marginal `#E0A542` / standdown `#C8452F` (forecast meaning only)
- Fonts: IBM Plex Sans (body), Newsreader (serif), IBM Plex Mono (labels)
- Card: `bg-plex-surface rounded-xl border border-plex-border p-4`, 16px gap. Auth column `max-w-sm` (384px).
- `PRO` tag: mono 10px, 0.12em tracking, uppercase, 1px `border-light`, 3px radius, padding 1px 5px.

## Tests
- Update `RegisterPage.test.jsx`: new step order, no confirm email, unticked marketing by default, single Turnstile.
- Update `LoginPage.test.jsx`: forgot-password link, identifier label, Turnstile fallback.
- Add tests for the `HOME` step: lookup, change, skip, save with a null radius.
- Add tests for the sign-in prompt: shown when the postcode is null and not dismissed; hidden after Not now (server-backed); hidden after save.
- Backend:
  - The dismissal endpoint.
  - The reverse-lookup endpoint.
  - Email-or-username login.
  - The reset-token lifecycle (single use, expiry, rate limit).
  - Account deletion.
- `AppSettingsRoutes.test.jsx` counts the routes into Settings. The sign-in prompt isn't one of them, but check it doesn't open while the Settings dialog is open.

## Files
- `Postcode Capture.html`: frames 1–8 with notes.
