# Pending registration lifecycle — plan

Owner request, 2026-10-24. One PR. Revised after two adversarial reviews (data-safety/concurrency and
email/ops/config); their findings are folded in below and listed in §6.

## 1. Problem

- Self-registration creates a **pending** `app_user` row at once (`enabled = false`, `password = ''`, the
  shape `UserService.createPendingUser` writes; `LockedAccount.pending()` is the test). The verification
  link lives 24 hours (`VerificationTokenIssuer.TOKEN_EXPIRY_HOURS`).
- The early-access cap (`RegistrationService.register`, `photocast.registration.max-users`, default 3)
  counts **every non-admin row**, pending ones included. Nothing removes a pending row except a
  re-registration with the same address, so an abandoned sign-up holds a place for ever and the next
  reader sees "Early access is full".
- New sign-ups are `PRO_USER` (`RegistrationProperties.defaultRole`). The owner wants `LITE_USER`.

## 2. What ships

### 2.1 LITE by default, cap 10
- `RegistrationProperties.defaultRole` → `"LITE_USER"`; `maxUsers` default `3 → 10` (owner,
  2026-10-24); javadocs to match. `application-example.yml`: `default-role: LITE_USER`, `max-users: 10`.
  `application-dev.yml` stays 100.
- Existing accounts keep their role (no data change). No frontend or reference-doc copy promises Pro at
  sign-up (checked: `RegisterPage.jsx`, `roles-and-gating.md`).
- **Owner action** (not in the PR): if the production host's `application.yml` sets
  `photocast.registration.default-role` or `max-users`, change them there (LITE_USER, 10); a host value
  wins over the repo defaults.

### 2.2 The hold window and its reminders

| When | Action |
|---|---|
| sign-up | the existing verification email (gains one line: "We'll hold your place for 7 days.") |
| age ≥ 96 h (72 h left) | **first reminder**, with a FRESH link |
| age ≥ 144 h (24 h left) | **final reminder**, with a FRESH link |
| final reminder sent ≥ 24 h ago | **purge** (§2.5) |

The original link is long dead by day 4, so every reminder mints a new one through
`VerificationTokenIssuer.issue(userId)` (row lock, stored address; deletes the account's earlier
UNVERIFIED links only, so a verified-but-unactivated token survives and a reader on the set-password page
is not broken).

**The backlog is never emailed** (owner, 2026-10-24). Every account already pending when V190 runs is
backfilled by the migration as if its final reminder had gone out at `created_at + 144 h`
(`verification_reminders_sent = 2`, `final_reminder_sent_at = created_at + INTERVAL '144 hours'`), so the
sweep sends it nothing and purges it once it is 7 days old — at once for the old ones filling the cap
today. The unexpired-token rule (§2.5) still protects any of them that is mid-flow. The 144 h in the
migration is a one-off literal tied to the defaults at deploy; later config changes do not touch it.

**No age-only backstop.** A purge always follows a final reminder that was actually sent. A mailer that
is down holds places until it recovers; that is harmless, because a dead mailer also stops every new
sign-up from verifying, and the run's INFO line counts the failed sends. The one exception is a sender
that is not configured at all (`mailSender == null`, local/dev): there the sweep purges at `age ≥ hold`
with no reminders.

Config (`RegistrationProperties`, documented in `application-example.yml` under `photocast.registration`):
- `pending-hold-hours: 168`
- `first-reminder-hours-left: 72`
- `final-reminder-hours-left: 24`

**Validation fails fast** (`IllegalStateException` at startup, the posture of `SlotAtmosphereCleanupJob`,
a job that deletes data — never a silent fallback). Rules: every value a positive multiple of 24 (the
email copy speaks in days); `final ≥ TOKEN_EXPIRY_HOURS`; `first − final ≥ TOKEN_EXPIRY_HOURS` (so the
sweep's own first-reminder link has expired before the final is due and never defers it, §2.4);
`first < hold`.

### 2.3 Recording what was sent

Migration **V190** (V189 is the latest on `main` today; re-check before writing):

```sql
ALTER TABLE app_user ADD COLUMN verification_reminders_sent INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN final_reminder_sent_at TIMESTAMP;
-- The backlog is never emailed (§2.2): every account pending NOW is marked as already reminded.
UPDATE app_user SET verification_reminders_sent = 2,
       final_reminder_sent_at = created_at + INTERVAL '144 hours'
WHERE enabled = false AND password = '' AND role <> 'ADMIN';
```

plus the scheduler row (same shape as V162): `job_key 'pending_registration_sweep'`, display name
`Pending Registration Sweep`, description `Reminds never-verified sign-ups 3 days and 1 day before their
place lapses, and deletes them after the final reminder`, `CRON`, `'0 20 * * * *'` (hourly at :20 UTC),
`ACTIVE`.

`final_reminder_sent_at` is zone-less `TIMESTAMP` on purpose: `created_at` (V10) and the token
`expires_at` are zone-less and written with `LocalDateTime.now()` in the JVM default zone; every
comparison in this feature stays in that one frame.

`AppUserEntity` mapping — **both columns `updatable = false, insertable = false`** (column-scoped writes):
- `@Column(name = "verification_reminders_sent", nullable = false, updatable = false, insertable = false,
  columnDefinition = "INTEGER NOT NULL DEFAULT 0") private int verificationRemindersSent;` — a primitive
  `int` (prod runs `ddl-auto: validate`, so the Java type must match INTEGER) with a `columnDefinition`
  DEFAULT, the `credentials_version` precedent (`AppUserEntity.java:245`): local H2 (`ddl-auto: update`)
  and test H2 (`create-drop`) build the column from the entity, and without the default every insert
  fails NOT NULL.
- `@Column(name = "final_reminder_sent_at", updatable = false, insertable = false) private LocalDateTime
  finalReminderSentAt;`
- **Local H2 note** (dev-and-testing): an existing local H2 file gains the columns via `update` because
  of the DEFAULT; if it ever refuses, delete `backend/data/goldenhour.mv.db`. Local profiles never run
  Flyway, so the scheduler row is absent locally and the job never fires there — run it with a test.

The ONLY writers are two conditional repository updates (row count is the answer):

```sql
-- advance (claim a stage)
UPDATE app_user SET verification_reminders_sent = :next,
       final_reminder_sent_at = CASE WHEN :next = 2 THEN :now ELSE final_reminder_sent_at END
WHERE id = :id AND verification_reminders_sent = :expected AND enabled = false AND password = ''
-- revert (a failed send)
UPDATE app_user SET verification_reminders_sent = :expected, final_reminder_sent_at = :previousFinalAt
WHERE id = :id AND verification_reminders_sent = :next
```

### 2.4 The sweep

Three pieces, so every transactional method is called **through a Spring proxy** (never `this.x()`,
which would run `lockAccount` outside a transaction and drop the lock at once):

- `PendingRegistrationSweepJob` (`service/`) — the scheduled entry; registered with
  `dynamicSchedulerService.registerJobTarget("pending_registration_sweep", this::sweep)` like
  `RefreshTokenCleanupService`. Not transactional itself.
- `PendingRegistrationWriter` (`service/`, separate `@Component`) — the claim and revert transactions.
- `UserService.purgeAbandonedPending(Long id, LocalDateTime now)` — the purge (§2.5), next to the two
  existing delete paths so all three share one sequence.

**Clock.** The job, the writer and `VerificationTokenIssuer` take a `Clock` that is
`Clock.systemDefaultZone()` in production (a package-private constructor takes a fixed one in tests), and
read `LocalDateTime.now(clock)`. Not the app's `Clock` bean: that is UTC (`AppConfig.java:185`), while the
columns are written in the JVM default zone. The issuer's `expiresAt` must use the same injected clock so
deferral and purge tests compare one "now". Boundary tests must not assume the JVM zone is UTC.

One run:
1. **Select** candidate ids, one query: `enabled = false AND password = '' AND role <> 'ADMIN' AND
   created_at <= now − (hold − first)` — ids only, no entities held.
2. **Per account, isolated** (a throw is logged at WARN with the id and the loop carries on). Decide:
   - **purge** if `sent = 2 AND final_reminder_sent_at <= now − final`, or (sender not configured
     — `UserEmailService.isConfigured()`, new) `age ≥ hold`;
   - else **final reminder** if `age ≥ hold − final AND sent < 2` (backlog: 0 → 2 directly);
   - else **first reminder** if `age ≥ hold − first AND sent < 1`;
   - else nothing.
3. **Reminder**:
   a. `writer.claim(id, expected, next, now)` — one transaction: `lockAccount`; stop if not pending;
      **defer** (stop, no write) if the account holds an UNEXPIRED, UNVERIFIED token (a reader's or an
      admin's resend — minting would kill the link they just got; the sweep's own earlier link has
      expired by §2.2's validation). Otherwise advance (stop on 0 rows) and `verificationTokenIssuer
      .issue(id)` (joins the transaction). Returns the issued address/username/raw token and the
      previous `final_reminder_sent_at`. Commit.
   b. After commit, `userEmailService.sendVerificationReminderEmail(...)` **synchronously**.
   c. On `false`: `writer.revert(id, expected, next, previousFinalAt, rawTokenHash)` — one transaction:
      the revert update AND delete the token just minted, by its hash (otherwise that live token would
      make every later run defer for 24 h and the final reminder would be lost). The next hourly run
      retries. A crash between (a) and (b) loses that one reminder; accepted.
   The row lock is never held across SMTP (activation takes the same lock).
4. One INFO line per run: candidates, first sent, final sent, deferred, failed sends, purged, errors.
   Addresses and usernames only at DEBUG through `LogSanitizer`.

Overlap: `triggerNow` from the Ops tab can overlap a scheduled run (no mutex). Safe: the advance CAS
admits one claimant per stage, and the purge is a conditional delete under the row lock.

### 2.5 Purge (`UserService.purgeAbandonedPending`)

One `@Transactional` method, the same order as the admin delete (user row first): `lockAccount`; stop if
not pending; stop if the account holds ANY unexpired verification token (verified or not — a reader
mid-flow keeps the place until that link expires); `purgeUserRows(id)` (`user_drive_time` has no ON
DELETE rule, V67, so skipping it would make the delete fail every hour for ever); `deletePendingById(id)`
(conditional; row count is the answer); then `EmailClaims.release(storedEmail)`. Tokens, refresh tokens
and the other children cascade. No email on purge. Returns whether a row went.

**Admin-cycled shape** (documented, tested): an admin enable of a pending account keeps `password = ''`
and a later disable passes a null password (`UserService.java:186`), so the row returns to the pending
shape and is reminded/purged on its age. That is the intended outcome — it never had a password — but
the test pins it.

### 2.6 Email

- `UserEmailService` gains `RegistrationProperties` in its constructor (update its test wiring).
- New **synchronous** `sendVerificationReminderEmail(String email, String username, String rawToken,
  boolean finalReminder, LocalDateTime placeLapsesAt)` returning `boolean` — true only when
  `mailSender.send` returned; false when the sender is unconfigured or any exception is caught (logged
  at WARN). Not `@Async`.
- **SMTP timeouts** — today none are set and Jakarta Mail waits for ever; this is the first send on a
  scheduler thread (pool of 5). Add to `application-prod.yml`, `application-example.yml` and
  `application-local.yml`:
  `spring.mail.properties.mail.smtp.connectiontimeout: 10000`, `timeout: 10000`, `writetimeout: 10000`.
- One template `verification-reminder-email.html` (brand fragments as `verification-email.html`), copy
  switched on `finalReminder`; the day counts derive from config (hours / 24):
  - first — subject `PhotoCast — your place is held for 3 more days`; body: you started signing up,
    your early-access place is held until <date>, button "Verify My Email", "This link expires in 24
    hours."
  - final — subject `PhotoCast — last day to claim your place`; body: your place is released tomorrow.
  - both end: "Changed your mind? Do nothing — the place lapses on its own and the sign-up is deleted."
    Account emails, sent regardless of `marketing_email_opt_in`.
- `verification-email.html` gains "We'll hold your place for 7 days." (`holdDays` variable from config,
  set in `sendVerificationEmail`).

### 2.7 Not in scope
- Waitlist notification when a place frees (owner handles it from the Waitlist view).
- The cap still counts pending rows — by design; the purge is what frees places.
- Any frontend change.

## 3. Tests (`docs/engineering/test-improvement-standards.md`)

- `PendingRegistrationSweepJobTest` (fixed clock, writer/user service/email mocked where the unit
  demands): each stage at exactly 96 h / 144 h and one minute before; purge at exactly final-sent + 24 h
  and one minute before; a 30-day account with `sent = 0` (created after deploy, mailer was down) → final reminder, NOT purge;
  unconfigured sender → purge at 168 h with no send; already-sent stage not resent; a failed send calls revert with the minted token's hash; one
  account throwing does not stop the others.
- `PendingRegistrationWriterTest` / repository tests (H2): advance CAS 0 rows when `expected` mismatches
  or the row is no longer pending; `final_reminder_sent_at` set only for `next = 2`; revert restores
  both columns and deletes only the minted token; deferral when an unexpired unverified token exists and
  NOT when only an expired one does; admin row and a disabled account WITH a password hash never
  selected; the admin-cycled pending shape IS selected.
- `UserService.purgeAbandonedPending`: deletes a pending row with a `user_drive_time` child; refuses an
  activated row (returns false); refuses while any unexpired token exists; releases the email claim.
- `VerificationTokenIssuerTest`: `expiresAt` from the injected clock.
- `UserEmailServiceTest`: reminder returns false with no sender and on a `MailException`, true on send;
  both copies carry the fresh URL and the right subject.
- `EmailTemplateRenderingTest`: add both reminder copies; add `holdDays` to the `verification-email`
  case; fix its "five templates" javadoc.
- `RegistrationPropertiesTest` (new): `new RegistrationProperties()` defaults (LITE_USER, 10, 168/72/24); every validation rule rejects its bad case.
- Testcontainers integration test for V190 (existing pending rows get 2 and `created_at + 144 h`;
  existing active and admin rows get 0 and NULL; seeded job row present;
  `ddl-auto: validate` passes) — proven in CI only.

## 4. Docs
- `docs/engineering/reference/platform.md`: Auth paragraph (hold window, reminders, purge,
  LITE default, cap 10); Dynamic scheduler list gains the job.
- `docs/engineering/reference/database-migrations.md`: V190.
- `docs/engineering/reference/configuration.md`: the three new keys and the SMTP timeouts.
- `docs/engineering/reference/dev-and-testing.md`: the local H2 note (§2.3).
- `changelog.d/20261024-pending-registration-lifecycle.md`.

## 5. Owner decisions
1. Backlog at deploy: **no emails** (owner, 2026-10-24). Pending accounts that exist when V190 runs are
   purged silently once 7 days old (§2.2).
2. Production `application.yml` `default-role` / `max-users` — check on the host (§2.1).

## 6. Review findings folded in
SMALLINT vs `int` under `validate`; missing `columnDefinition` default for H2; synchronous send with no
SMTP timeouts; backlog silently purged (now purge only 24 h after a final reminder was sent; an
age-only backstop was considered and dropped, §2.2); failed send left a live
token that deferred the retry 24 h (now deleted on revert); purge skipped `purgeUserRows`
(`user_drive_time` FK); proxy self-invocation (separate writer class); config fallback replaced by fail
fast; token `expiresAt` used an uninjectable clock; LITE test proved nothing (mocked); template test
omitted; admin-cycled pending shape undocumented; Rewind rationale corrected (the `Clock` bean is UTC,
the columns are default-zone).
