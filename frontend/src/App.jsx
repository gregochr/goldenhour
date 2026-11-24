import React, { lazy, Suspense, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { computeAutoSelection, colourForecastDates } from './utils/conversions.js';
import { normalizeMapTrigger, doorFromTrigger } from './utils/mapDoor.js';
import SessionExpiryBanner from './components/SessionExpiryBanner.jsx';
import AuroraBannerRoot from './components/AuroraBannerRoot.jsx';
import NlcSightingBanner from './components/NlcSightingBanner.jsx';
import PostcodePromptOverlay from './components/auth/PostcodePromptOverlay.jsx';
import SubRegionSignInPrompt from './components/subregions/SubRegionSignInPrompt.jsx';
import { markPostcodePromptDismissed } from './api/settingsApi.js';
import { AuthProvider, useAuth } from './context/AuthContext.jsx';
import { AuroraStatusProvider } from './context/AuroraStatusContext.jsx';
import { WarningsProvider } from './context/WarningsContext.jsx';
import { AuroraViewlineProvider } from './context/AuroraViewlineContext.jsx';
import { useAuroraStatus } from './hooks/useAuroraStatus.js';
import { ukDateStr, ukDateStrOffset, resolveAuroraNight, resolveMapDate } from './utils/mapDates.js';
import { auroraTabOffered } from './utils/auroraTab.js';
import { useForecasts } from './hooks/useForecasts.js';
import { HealthStatusProvider, useHealthIsDown } from './context/HealthStatusContext.jsx';
import HealthStatusPill, { HealthStatusDot, HealthStatusMenuLine } from './components/HealthStatusPill.jsx';
import useAfterFirstPaint from './hooks/useAfterFirstPaint.js';
import useTodaysLight from './hooks/useTodaysLight.js';
import useReaderSettings from './hooks/useReaderSettings.js';
import useModalPresent from './hooks/useModalPresent.js';
import { createColourSaveQueue, keepColourSaveLineOpen } from './utils/colourSaveQueue.js';
import useOperationsRoute from './hooks/useOperationsRoute.js';
import { appNow } from './utils/rewind.js';
import { lastPlanDateStr } from './utils/comingUpHandoff.js';
import { useRewind } from './hooks/useRewind.js';
import { setRewind } from './utils/rewind.js';
import { clearForecastDetailCache } from './api/forecastApi.js';
import RewindPill from './components/RewindPill.jsx';
import WindowFirstShell from './components/WindowFirstShell.jsx';
import PlanErrorBoundary from './components/PlanErrorBoundary.jsx';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from './context/WindowFirstBriefingContext.jsx';
import { AskProvider } from './context/AskContext.jsx';
import { GuideProvider } from './context/GuideContext.jsx';
import { FavouritesProvider } from './context/FavouritesContext.jsx';
import { takeLinkToken } from './utils/oneTimeLinkToken.js';
import { takeInviteToken } from './utils/inviteLinkToken.js';
import ChunkErrorBoundary from './components/shared/ChunkErrorBoundary.jsx';
import { loadSettingsModal, warmSettingsModal } from './utils/settingsModalChunk.js';
import SettingsFallback from './components/SettingsFallback.jsx';
import { bootDialogHistory, whenSettled } from './utils/dialogHistory.js';
import { takeMapRoute, consumeMapRoute } from './utils/mapRoute.js';
import { loadMapChunk, preloadMapChunk } from './utils/mapChunk.js';

// Code-split the heavy subtrees so they stay out of the initial bundle: the Leaflet map stack and
// the admin-only Manage view. They load on demand behind the Suspense boundaries. The map chunk is
// PRELOADED on idle after first paint (`preloadMapChunk` below) for a reader with forecast data, so the
// first open of the map view is instant; the pane itself mounts on that first open
// (`docs/engineering/one-screen-plan.md` D10).
const WindowFirstMapPane = lazy(loadMapChunk);
const ManageView = lazy(() => import('./components/ManageView.jsx'));

// Sign-in and the other auth pages, as ONE route chunk (`components/auth/AuthPages.jsx` re-exports all
// five; five `lazy()` calls on one module fetch it once). A signed-in reader never loads it, except
// the forced password change. `PostcodeCapture` is NOT among them: `PostcodePromptOverlay` imports it
// statically for every signed-in reader.
const authPages = () => import('./components/auth/AuthPages.jsx');
const LoginPage = lazy(() => authPages().then((m) => ({ default: m.LoginPage })));
const RegisterPage = lazy(() => authPages().then((m) => ({ default: m.RegisterPage })));
const ChangePasswordPage = lazy(() => authPages().then((m) => ({ default: m.ChangePasswordPage })));
const ForgotPasswordPage = lazy(() => authPages().then((m) => ({ default: m.ForgotPasswordPage })));
const ResetPasswordPage = lazy(() => authPages().then((m) => ({ default: m.ResetPasswordPage })));

// The settings dialog, fetched the first time it opens. It carries the sub-region picker with it.
// The fallback is null: the dialog is an overlay, so nothing on the page moves while it loads, and
// `settingsOpen` has already told the shell to take its own dialogs down, so the dialog rules hold
// whether or not the chunk has arrived.
const UserSettingsModal = lazy(loadSettingsModal);

/** Page-sized stand-in for an auth page while its chunk loads: same full-height box, no content. */
function AuthFallback() {
  return <div className="min-h-screen" data-testid="auth-fallback" />;
}

/**
 * Suspense boundary for a lazily loaded auth page.
 *
 * @param {object} props
 * @param {React.ReactNode} props.children the lazy page
 */
function AuthSuspense({ children }) {
  return (
    <ChunkErrorBoundary variant="page" label="The sign-in page">
      <Suspense fallback={<AuthFallback />}>{children}</Suspense>
    </ChunkErrorBoundary>
  );
}

AuthSuspense.propTypes = { children: PropTypes.node.isRequired };

/** Lightweight fallback shown while a lazily-loaded view chunk is fetched. */
function ViewFallback() {
  return (
    <div className="flex justify-center py-16">
      <p className="text-plex-text-secondary animate-pulse">Loading…</p>
    </div>
  );
}

/**
 * Auth gate — renders {@link LoginPage} when no token is present,
 * or the main app otherwise. This keeps hooks out of the unauthenticated path.
 *
 * <p>Two emailed links arrive here with a one-time token (read, and stripped from the address,
 * once per page load by {@code takeLinkToken}), and they must not share state. A link
 * on {@code /reset} is a password reset ({@code resetToken}); a token on any other path
 * ({@code /verify}, or the legacy {@code /}) is an email verification ({@code verifyToken}). The
 * reset page is chosen BEFORE the token test, so a reader already signed in on this browser — the
 * usual case, the link is opened on the phone the account lives on — still gets it. The cleanup
 * effect below belongs to the verification token alone: the reset page clears its own token and
 * the URL itself, because an effect that cleared both whenever a stored token coexists with a URL
 * token would unmount the reset page before the reader could submit.
 */
function AuthGate() {
  const { token, mustChangePassword, registrationPending } = useAuth();
  const [showRegister, setShowRegister] = useState(false);
  const [showForgot, setShowForgot] = useState(false);

  // /reset#token= is a reset link (the query form, `?token=`, is still read for links mailed
  // before the fragment form); read once, from the pathname it was opened on.
  const [resetToken, setResetToken] = useState(() => (
    window.location.pathname === '/reset' ? takeLinkToken() : null
  ));

  // Any other path with a token (the /verify#token= link, the older /verify?token= form, or the
  // legacy /?token=) is email verification; cleared once consumed.
  const [verifyToken, setVerifyToken] = useState(() => (
    window.location.pathname === '/reset' ? null : takeLinkToken()
  ));

  // A waitlist invitation (`/register#invite=`, lifted from the address once per page load) opens the
  // registration form with the token. A signed-in browser ignores it (cleared below).
  const [inviteToken, setInviteToken] = useState(() => takeInviteToken());

  // Once authenticated, clear any leftover verify token from URL and state.
  // RegisterPage unmounts before its own cleanup can run, so we handle it here.
  useEffect(() => {
    if (token && verifyToken) {
      window.history.replaceState({}, '', '/');
      // One-time cleanup coupled to the history side effect above: clear the
      // consumed verify token so RegisterPage stops rendering in verify mode.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setVerifyToken(null);
    }
  }, [token, verifyToken]);

  useEffect(() => {
    if (token && inviteToken) {
      // An invitation is for a signed-out reader: once a session exists it must not resurface at sign-out.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setInviteToken(null);
    }
  }, [token, inviteToken]);

  if (resetToken) {
    return (
      <AuthSuspense>
        <ResetPasswordPage
          token={resetToken}
          onDone={() => setResetToken(null)}
          onRequestNewLink={() => {
            setResetToken(null);
            setShowForgot(true);
          }}
        />
      </AuthSuspense>
    );
  }

  // Also ahead of the token test: "Request a new link" on a dead link must reach this page for a
  // reader who is signed in on this browser too. Its "Back to sign in" then lands them in the app.
  if (showForgot) {
    return <AuthSuspense><ForgotPasswordPage onBackToLogin={() => setShowForgot(false)} /></AuthSuspense>;
  }

  // `registrationPending` keeps registration on screen after `completeRegistration` has stored the
  // new session: the `HOME` and Welcome steps run with a token already in place, and without it the
  // gate would swap to the app the moment the token appeared (login-registration plan §2 #2). Both
  // tests need it — the first to enter this branch, the second to pick the SAME `<RegisterPage>`
  // element (same type, same position, no key) so React keeps its instance and its `step` state
  // when `verifyToken` has been cleared by the effect above.
  if (!token || registrationPending) {
    // If there's a verification token in the URL, show RegisterPage in verify mode
    if (verifyToken || registrationPending) {
      return (
        <AuthSuspense>
          <RegisterPage
            verifyToken={verifyToken}
            onBackToLogin={() => {
              window.history.replaceState({}, '', '/');
              setVerifyToken(null);
              setShowRegister(false);
            }}
          />
        </AuthSuspense>
      );
    }
    if (showRegister || inviteToken) {
      return (
        <AuthSuspense>
          <RegisterPage
            inviteToken={inviteToken}
            onBackToLogin={() => {
              setInviteToken(null);
              setShowRegister(false);
            }}
          />
        </AuthSuspense>
      );
    }
    return (
      <AuthSuspense>
        <LoginPage
          onRegister={() => setShowRegister(true)}
          onForgotPassword={() => setShowForgot(true)}
        />
      </AuthSuspense>
    );
  }
  if (mustChangePassword) {
    return <AuthSuspense><ChangePasswordPage /></AuthSuspense>;
  }
  return <RewindGate />;
}

/**
 * Remounts the whole authenticated app whenever an admin sets or clears a rewind
 * (`utils/rewind.js`), so every clock read that happens once at mount — the Map tab's default
 * event, the Plan provider's SWR hydrate, the shell's "a passed window never comes back" assumption
 * — is re-evaluated against the rewound clock rather than patched one site at a time. The cost is a
 * reload-shaped reset of the UI (tab, selection, open dialogs) at the moment of the rewind, which is
 * the one moment the admin expects one: they have just asked for a different page.
 */
function RewindGate() {
  const rewind = useRewind();
  // A rewind is an admin's, for this signed-in page alone: it ends when the authenticated tree does
  // (sign-out, session expiry). Cleared HERE, on unmount, rather than in `AuthContext`'s own
  // sign-out path, because a store write there lands before `setToken(null)` and re-keys this gate
  // while the token is already gone — one remount of the whole app with no Authorization header,
  // every mount fetch a 401, and a spurious session-expired event out of the refresh interceptor.
  useEffect(() => () => setRewind(null), []);
  // The lazily-fetched popup detail is keyed by row id and should be clock-free, but nothing pins
  // that; dropping it on every change is free and keeps a rewound row from outliving the rewind.
  useEffect(() => { clearForecastDetailCache(); }, [rewind?.to]);
  return (
    <AuroraStatusProvider key={rewind?.to ?? 'live'}>
      <AuroraViewlineProvider>
        <HealthStatusProvider>
          <WarningsProvider>
            <AppInner />
          </WarningsProvider>
        </HealthStatusProvider>
      </AuroraViewlineProvider>
    </AuroraStatusProvider>
  );
}

/**
 * Ask PhotoCast's provider, except while an admin's rewind is active.
 *
 * <p>No provider means {@code useAsk()} answers its default — Ask is off — so no entry, sheet or
 * request exists for the whole rewound page; see the call site for why that is the rule.
 *
 * @param {object} props
 * @param {boolean} props.rewound whether a rewind is active
 * @param {React.ReactNode} props.children
 */
function AskWhenLive({ rewound, children }) {
  return rewound ? children : <AskProvider>{children}</AskProvider>;
}

AskWhenLive.propTypes = {
  rewound: PropTypes.bool.isRequired,
  children: PropTypes.node,
};

/**
 * Lifts the dates the briefing SERVES a window for out of the provider, to `AppInner`'s Map date
 * domain. Renders nothing. The server's rendered set is the authority; the client's T+3 cut exists
 * only to drop unserved filler days, so a date the briefing serves is never cut — which matters when
 * the device's UK date is behind the server's and the server's T+3 is the client's T+4. The key is the
 * sorted, comma-joined date list, so a poll that changes nothing does not re-render the app.
 *
 * @param {object} props
 * @param {(dates: string[]) => void} props.onChange receives the served dates, ascending
 */
function ServedDatesReporter({ onChange }) {
  const { upcomingEvents } = useWindowFirstBriefing();
  const key = [...new Set((upcomingEvents ?? []).map((e) => e.date))]
    .sort((a, b) => {
      if (a < b) return -1;
      return a > b ? 1 : 0;
    })
    .join(',');
  useEffect(() => {
    onChange(key ? key.split(',') : []);
  }, [key, onChange]);
  return null;
}

ServedDatesReporter.propTypes = {
  onChange: PropTypes.func.isRequired,
};

/**
 * Inner app component — only rendered when the user is authenticated.
 */
function AppInner() {
  const { isAdmin, role, logout, postcodePromptSuppressed, suppressPostcodePrompt } = useAuth();
  // The admin's rewind, if any. Read ONCE per mount for the two seeds below (`RewindGate` remounts
  // this component on every change, so a mount always sees the rewind it was mounted for) and
  // live for the pill.
  const rewind = useRewind();
  const [showSettings, setShowSettings] = useState(false);
  /**
   * The map view to open at load (one-screen-plan.md D4): the page's own `#map/...` address, read here once (after
   * `takeLinkToken` / `takeInviteToken` had their turn in `main.jsx`) and rewritten to the bare path. Every device opens
   * on This week (owner, 2026-11-23), so with no address nothing opens. Spent by the shell once it has acted on it, so a
   * rewind remount does not replay it. Read through the module's memo, so React's StrictMode double-invoked initialiser
   * sees the same answer.
   */
  const [loadMapRoute] = useState(() => takeMapRoute());
  /**
   * The screen in force, as the shell reports it: {@code 'plan'}, {@code 'map'} (the view is open) or {@code 'operations'}.
   * `App` cannot otherwise learn it (the view's state is shell-internal), and it needs it to lift the session notice over
   * the view, inert its own banners behind it, and tell the guide which page to follow. Defaults to `'plan'`: the shell
   * is on the screen at first render whatever the device.
   *
   * <p>⚠️ A `calc(100dvh - …)` height chain (measured masthead + banner block, each via its own `ResizeObserver`) was
   * tried here first for the old full-frame Map tab and reverted: a live measurement found 16px of page scroll surviving
   * with every banner suppressed, an inter-element MARGIN/gap a `ResizeObserver` on element BOXES structurally cannot
   * see. The map is a fixed VIEW now (`MapScreen`), so no height arithmetic remains.
   */
  const [activeScreen, setActiveScreen] = useState('plan');
  /** Whether the map view is open (the shell reports `'map'` while it is). */
  const mapViewOpen = activeScreen === 'map';
  /**
   * The session notice's slot. While the map view is open the slot is fixed over the page (so the notice stays usable),
   * which would cover the view's header; the view starts below it instead, through one custom property on the root
   * (`--wf-session-banner-h`, read by `.wf-map-screen`'s `top`), kept equal to the slot's measured height.
   */
  const sessionSlotRef = useRef(null);
  useLayoutEffect(() => {
    const slot = sessionSlotRef.current;
    if (!mapViewOpen || !slot) return undefined;
    const root = document.documentElement;
    const apply = () => root.style.setProperty('--wf-session-banner-h', `${slot.offsetHeight}px`);
    apply();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
    observer?.observe(slot);
    return () => {
      observer?.disconnect();
      root.style.removeProperty('--wf-session-banner-h');
    };
  }, [mapViewOpen]);
  const {
    locations, loading: forecastsLoading, error: forecastsError, refresh,
    loadOlderDay, olderDayLoading, loadedPastDays, emptyPastDays,
  } = useForecasts();
  /**
   * Moved after a successful sub-region save (the sign-in prompt, or Settings): the briefing provider
   * refetches the briefing and ratings on the change, and `refresh` reloads the forecasts, so nothing
   * shaped by the old picks outlives the save. The SWR cache was already cleared by the save itself.
   */
  const [subRegionsVersion, setSubRegionsVersion] = useState(0);
  const handleSubRegionsSaved = useCallback(() => {
    setSubRegionsVersion((v) => v + 1);
    refresh();
  }, [refresh]);
  // Defer the long-lived health-status SSE stream until after first paint so it doesn't compete with
  // the critical forecast/briefing fetches during boot.
  const streamsReady = useAfterFirstPaint();
  // The settings dialog's chunk, fetched once the page is idle so opening it is usually instant.
  useEffect(() => { if (streamsReady) warmSettingsModal(); }, [streamsReady]);
  // Only the boolean: the health value itself (with its per-tick `checkedAt`) lives in the provider
  // and the pill, so a health tick does not re-render this component.
  const isDown = useHealthIsDown();

  /** Monotonic counter so repeat taps on the same location re-trigger the handoff. */
  // Nonce -1 is reserved for the rewind seed `mapTabHandoff` below is initialised with, so the
  // first live handoff (nonce 0) can never repeat the seed's nonce and be swallowed by the keyed
  // effect. Not "start at 1": `AppOpenMapTabFromPlan.test.jsx` proves the handoff and tab-request
  // nonces are separate refs by their inequality on the first door, and starting here at 1 made
  // them coincide.
  const handoffNonce = useRef(0);

  /** Briefing evaluation scores lifted from the Plan shell, passed to MapView. */
  const [briefingScores, setBriefingScores] = useState(new Map());
  const handleEvaluationScoresChange = useCallback((scores) => setBriefingScores(scores), []);

  /** Seasonal features lifted from the Plan shell, passed to MapView. */
  const [seasonalFeatures, setSeasonalFeatures] = useState([]);
  const handleSeasonalFeaturesChange = useCallback((features) => setSeasonalFeatures(features), []);

  // Non-null when the settings dialog was opened to land on a particular field: the masthead's "set
  // a postcode" nudge and the map control's "you have no postcode" branch, which both exist to point
  // at exactly that input.
  const [settingsFocus, setSettingsFocus] = useState(null);
  /**
   * The one record of the reader's own settings: the home — for the tick line (through the Plan
   * provider), the map's HOME marker, reach rings and ⌂ control, and the Plan tab's home dot — the
   * Coming up last-seen date, and the map-colour preference, which the hook hands to `scoreRamp`.
   * Read once on mount and kept current by the settings dialog's own answers, never by a read of
   * its own. With it, the two counters the reads derived from the home key on: each moves only
   * when an answer changes what it counts — the home, or its drive times — so the reads keyed on
   * them can drop a request a newer move supersedes. A counter rather than the values themselves:
   * its readers depend on server-side state this component never sees. See the hook.
   */
  const {
    homePlace, homeCoords, comingUpLastSeenDate, setComingUpLastSeenDate, postcodePromptDue,
    homeSettingsVersion, driveTimesVersion, mapColourScale,
    startSettingsRead, homeSaved, driveTimesRecalculated, colourSaved,
    // Threaded to the Map pane on the same route as `mapColourScale` (map-mobile-sheet-plan.md
    // M4) — unread by any UI until M5, which is why nothing here calls `saveTideMode` yet.
    mapTideMode, saveTideMode,
  } = useReaderSettings();
  /**
   * The one-time "set your home postcode" prompt (login-registration plan §2 #3). Raised by the
   * server's {@code postcodePromptDue} on the mount read, as an overlay over the app that is already
   * mounted — never a gate — and only when nothing else is claiming to be the modal: a slow first
   * read lets the reader open Settings, a Plan dialog or Ask first, and a late
   * answer must not put a second {@code aria-modal} over (and the app {@code inert} under) any of
   * them. {@code useModalPresent} reads the DOM because the shell's and Ask's dialogs are not
   * {@code App} state; the prompt waits for the last one to close.
   *
   * <p>{@code promptDone} is set by Save and Not now and lives for one mount of this component (the
   * server's flag, and a reload, are the only durable state — never {@code localStorage}, so a second
   * account on this browser cannot inherit a dismissal). {@code postcodePromptSuppressed} is the
   * auth context's in-memory "said not now this page session", which also covers a registration
   * Skip whose PUT failed. A rewound admin is never prompted: a rewind is a read of the past, not a
   * sign-in. {@code promptShown} latches on the first mount so a condition that flips while the
   * prompt is up (the mount expression alone does not latch) cannot unmount it mid-lookup.
   */
  const modalPresent = useModalPresent();
  const [promptDone, setPromptDone] = useState(false);
  const [promptShown, setPromptShown] = useState(false);
  const promptEligible = postcodePromptDue && !promptDone && !postcodePromptSuppressed
    && !modalPresent && !rewind;
  if (promptEligible && !promptShown) setPromptShown(true);
  const showPostcodePrompt = !promptDone && (promptShown || promptEligible);
  const handlePromptSaved = useCallback((updated) => {
    homeSaved(updated);
    setPromptDone(true);
  }, [homeSaved]);
  const handlePromptNotNow = useCallback(async () => {
    // Before the request: a dismissal whose PUT fails must still keep the prompt away this session.
    suppressPostcodePrompt();
    // Numbered as asked, so the dismissal's answer is dropped if a newer one (the Settings dialog's
    // read, a saved home) has been applied by the time it lands.
    const report = startSettingsRead();
    try {
      report(await markPostcodePromptDismissed());
    } catch {
      // The app must open either way. A failed dismissal means one more prompt at the reader's
      // next page load — the safe direction.
    }
    setPromptDone(true);
  }, [startSettingsRead, suppressPostcodePrompt]);
  /**
   * Where the settings dialog puts focus on close if the element that opened it has gone — a
   * function returning that element, or null. Only the masthead's "set a postcode" nudge supplies
   * one, because only its control is REPLACED by the save it exists for: on the Map tab a saved
   * home swaps the nudge for a non-interactive statement the moment `homeSaved` updates the record,
   * while the dialog is still open, so its recorded opener is detached by the time it closes and
   * focus fell to `<body>`. The cog and the map's ⌂ keep their node through a save (the ⌂ goes from
   * `null` to coordinates, never through the `undefined` that empties it), and their dialogs
   * restore exactly as before.
   *
   * <p>⚠️ That opener is the pressed control only when nothing was covered. Every route into
   * settings closes the dialogs it would open over, in the commit that opens it (`settingsOpen`,
   * below), and a closing dialog hands focus back to ITS opener — which settings then records as its
   * own. So on a covered route, the nudge's included, the close restores to that control, and this
   * is asked only if the control has gone by then. Where a dialog does stay open beneath — an
   * Operations-tab admin `Modal` — `useDialogFocus` declines a successor outside it; the nudge keeps
   * its node there anyway, since only the Map tab swaps it.
   *
   * <p>Held for the life of one dialog and cleared on its close, so a later open from another
   * route can never inherit it. A function in state, hence the updater form wherever it is set.
   */
  const [settingsReturnFocus, setSettingsReturnFocus] = useState(null);
  // The Map's "Choose / Change / Add" sub-region buttons: Settings, scrolled to the picker and with it open.
  // `returnFocus` (optional) finds where focus goes on close when the opener has been taken down (the location sheet).
  const openSubRegionsSettings = useCallback((returnFocus) => {
    setSettingsReturnFocus(() => (typeof returnFocus === 'function' ? returnFocus : null));
    setSettingsFocus('subRegions');
  }, []);
  /**
   * The page's one line of map-colour saves, handed to every opening of the settings dialog. Here
   * rather than in the dialog because the dialog unmounts on close and its saves do not stop: with a
   * line per opening, a closed dialog's waiting choice went out after a reopened dialog's newer one.
   * See `colourSaveQueue.js`.
   *
   * <p>⚠️ Held open only while this page is mounted, which is the signed-in session: signing out
   * unmounts it, and a choice still waiting would otherwise go out under the NEXT account's token.
   */
  const [colourSaveQueue] = useState(createColourSaveQueue);
  useEffect(() => keepColourSaveLineOpen(colourSaveQueue), [colourSaveQueue]);
  /**
   * Today's light at the reader's home, for the window-first masthead's light rule.
   *
   * <p>Resolved here rather than inside the shell so the shell stays a render layer. Keyed on the
   * home counter alone — a drive-time recalculation cannot change the light — so saving a postcode
   * lights the rule without a reload.
   */
  const todaysLight = useTodaysLight(homeSettingsVersion);

  // Seeded with the rewind's own window when there is one: the admin rewound to a particular
  // sunrise or sunset, and the map should open on it rather than on the default the clock would
  // pick (which, pre-dawn, is today's SUNSET — `computeAutoSelection` never chooses a sunrise).
  const [selectedDate, setSelectedDate] = useState(rewind?.focus?.date ?? null);
  /**
   * Whether {@code selectedDate} NAMES A NIGHT — the aurora banner's route — rather than a calendar
   * day. Carried beside the date because `resolveMapDate`'s never-past exemption keys on the
   * selection's provenance and must never infer it from the value: in the small hours the night in
   * progress IS yesterday's date, so a stale solar pick from last evening equals it exactly, and
   * matching on value alone let that stale pick hold the map on a day that was over (Codex, #803).
   *
   * ⚠️ Written only through {@code selectDate} below, never with a bare `setSelectedDate`, so the
   * flag cannot drift from the date it describes.
   */
  const [selectedDateIsNight, setSelectedDateIsNight] = useState(false);
  /**
   * Whether {@code selectedDate} NAMES A PAST SOLAR WINDOW — the Map tab's window control stepped
   * back onto a window that has gone (outcome-rating-plan.md §2 #2). The second provenance flag,
   * on exactly the first's terms: {@code resolveMapDate} refuses a date before today, and a flag
   * set by the pick (never inferred from the value) is the only licence, so a stale past pick
   * without it — after a reload, a handoff, any other route to {@code selectDate} — falls through
   * to the today-forward pick as a stale solar date always did.
   */
  const [selectedDateIsPast, setSelectedDateIsPast] = useState(false);
  /**
   * The one writer for the trio above. {@code isNight} and {@code isPast} default false, so every
   * route that is not explicitly a night or past selection — a Plan door, a grid cell, the Ask
   * handoff, the aurora jump — gets the safe answer without having to know these rules exist. Only
   * `MapView.selectEvRow` can name a past window.
   */
  const selectDate = useCallback((date, { isNight = false, isPast = false } = {}) => {
    setSelectedDate(date);
    setSelectedDateIsNight(isNight);
    setSelectedDateIsPast(isPast);
  }, []);


  const sortedLocations = useMemo(
    () => [...locations].sort((a, b) => a.name.localeCompare(b.name)),
    [locations],
  );
  const visibleLocations = useMemo(
    () => sortedLocations.filter((loc) => loc.enabled !== false),
    [sortedLocations],
  );

  // All dates with a sunrise or sunset forecast for any visible (enabled) location, sorted — the
  // map's forecast-date domain. ⚠️ Not simply every `forecastsByDate` key: a wildlife hide's hourly
  // comfort rows reach a day further than the colour pipeline evaluates, and a date holding only
  // those would otherwise become a Sunrise/Sunset window nothing rates (`colourForecastDates`).
  //
  // ⚠️ Bounded above at the last day This week shows (T+3, `lastPlanDateStr`, the client mirror of
  // backend `PlanHorizon`): `GET /api/forecast` runs to T+5, but nothing rates T+4 or T+5 (Gate 4 scores
  // to T+3), so those rows would only show star chips over an empty field (whole-day-horizon-plan.md §5).
  // Past dates are kept, exactly as before: past windows and the older-day loader live below today. The
  // date is read from the app clock (Rewind-safe) and is a dependency, so a tab left open across UK
  // midnight widens by itself. Night rows are not built from this list and are untouched.
  const lastPlanDate = lastPlanDateStr(ukDateStr());
  //
  // ⚠️ The cut never drops a date the briefing SERVES a window for (`servedDates`, lifted by
  // `ServedDatesReporter`): the server's rendered set is the authority, and a device whose UK date is
  // behind the server's would otherwise list the server's T+3 as a Map row `resolveMapDate` refuses.
  const [servedDates, setServedDates] = useState([]);
  const allDates = useMemo(() => {
    const served = new Set(servedDates);
    return colourForecastDates(visibleLocations).filter((d) => d <= lastPlanDate || served.has(d));
  }, [visibleLocations, lastPlanDate, servedDates]);
  // ⚠️ Two different questions, kept apart. `allDates` answers "which dates get a window"; this
  // answers "did `GET /api/forecast` return ANYTHING for a visible location" — what gates the Map
  // tab and the "open the full map" doors. It must not follow `allDates`: a hides-only forecast (a
  // fresh deploy, a colour run that failed) has hourly rows and no sunrise or sunset, and the Map
  // tab would vanish while the data it can draw exists.
  const hasForecastData = useMemo(
    () => visibleLocations.some((loc) => loc.forecastsByDate.size > 0),
    [visibleLocations],
  );

  // Auto-select the next solar event using forecast data + a 30-min afterglow buffer.
  // Returns null when forecast data isn't loaded yet (fallback to default behaviour).
  const autoSelection = useMemo(
    () => visibleLocations.length === 0 ? null : computeAutoSelection(visibleLocations, appNow()),
    [visibleLocations],
  );

  // Default to today (or the nearest future date) when data loads.
  //
  // The UK calendar, which is the one every forecast date on the wire is keyed to. These were UTC
  // (a day behind for an hour after UK midnight under BST), then the browser's own zone (a day out
  // all day for a reader outside the UK). `ukDateStr` records both measurements.
  const todayStr = ukDateStr();
  const tomorrowStr = ukDateStrOffset(1);

  // The night aurora results are stored under, which in the small hours is YESTERDAY's date — a
  // night runs dusk-to-dawn, so it is not a calendar question and the backend answers it. Only the
  // aurora paths below use it; the colour map keeps its calendar default, because at 02:00 a
  // landscape photographer wants today's sunrise, not last night's sunset.
  const { status: auroraStatus } = useAuroraStatus();
  const auroraNightStr = resolveAuroraNight(auroraStatus);
  // C3 (overlay convergence): a REAL MODERATE/STRONG alert offers the Map tab even with no sunrise or
  // sunset forecast, so the aurora banner's door never has to land on nothing. Never LITE, never a
  // simulation (`utils/auroraTab.js` states why). With forecast data the tab was offered anyway, so
  // this changes nothing for any reader who had one (the pane, and a date that falls through to today, both test `hasForecastData` first).
  const auroraTabOn = auroraTabOffered(auroraStatus, role);
  // The map chunk, fetched once the browser is idle after first paint, for a reader with something to map
  // (one-screen-plan.md D10). The pane itself mounts on the first open of the view; this makes that open instant.
  const mapOffered = hasForecastData || auroraTabOn;
  useEffect(() => (streamsReady && mapOffered ? preloadMapChunk() : undefined), [streamsReady, mapOffered]);

  // ⚠️ Never a PAST date on ANY branch — `resolveMapDate` owns the whole precedence and records
  // what the old shape cost, the same way `normalizeMapTrigger` owns the handoff branch selection
  // below rather than sitting inline here. It replaced a three-branch expression whose "not past"
  // rule sat on the LAST branch only, guarded on the other two by a bare `allDates.includes(...)`
  // that a past date passes — and `autoDate` is frozen at mount, so a tab left open across UK
  // midnight took the stale branch every time and never reached the rule.
  //
  // `?? todayStr` only where there is forecast data but no date to window it on (hides-only): the
  // pane still mounts and needs a date, but `allDates` stays empty, so no window row is drawn.
  const effectiveDate = resolveMapDate({
    selectedDate,
    selectedIsNight: selectedDateIsNight,
    selectedIsPast: selectedDateIsPast,
    autoDate: autoSelection?.date ?? null,
    allDates,
    todayStr,
    // The one "past" date an explicit choice may name — see `handleAuroraViewOnMap` below, which
    // sets it deliberately, and `resolveMapDate`'s own note on the regression this prevents.
    nightDate: auroraNightStr,
  }) ?? (hasForecastData ? todayStr : auroraTabOn ? auroraNightStr : null);

  /**
   * The dialogs the Plan shell owns, closed on request. The shell registers its closer here
   * (`registerCloseDialogs`) because the aurora banner sits OUTSIDE the shell and a press on the Map
   * tab never changes tab, so nothing else would take a Map-hosted four-day sheet down before the
   * window changes under it.
   */
  const closeShellDialogs = useRef(null);
  const registerCloseDialogs = useCallback((closer) => { closeShellDialogs.current = closer; }, []);
  /** The same closer as a function, for the guide's two-deep rule (it takes the shell's dialogs down). */
  const closeAllShellDialogs = useCallback(() => closeShellDialogs.current?.(), []);
  /**
   * The shell's map-view openers, registered here (`registerOpenMap`) because the aurora banner sits OUTSIDE the
   * shell: {@code open(request)} is the shell's `openMap`, {@code close(options)} closes the view.
   */
  const mapViewDoors = useRef(null);
  const registerOpenMap = useCallback((doors) => { mapViewDoors.current = doors; }, []);

  /**
   * Aurora banner "View on map" (overlay convergence C4, `docs/engineering/overlay-convergence-plan.md`
   * section 3 C4 and section 4 #4) - kept and rewritten rather than deleted: the banner is mounted
   * above `<main>` on EVERY tab, so it takes the structured door like every Plan producer - an
   * `aurora` trigger from {@link normalizeMapTrigger}, resolved by {@link openMapTabFromPlan} into a
   * `night` door for {@link resolveAuroraNight}'s night.
   *
   * <p>The banner hands over the Plan's live lens (it reads it from the briefing context it already
   * sits in), so the door imports the reader's rating floor and reach tier exactly as a Plan card's
   * does and does not silently reset them.
   *
   * <p>⚠️ <b>With the map view already open the press changes the window and leaves the lens alone</b>:
   * `inPlace` takes {@link openMapTabFromPlan}'s `source: 'map'` branch (no lens, floor, reach or scope write), which
   * carries the night so `MapView` selects the aurora row and leaves any standing door lens alone. (A map-sourced door
   * that names a place also selects it and flies to it; an aurora door names none.) The shell's own dialogs (a
   * Map-hosted four-day sheet) are closed first, through the closer the shell registered. Pressed with the view closed
   * it hands the map its handoff and asks the shell to show the view (`registerOpenMap`; one-screen-plan.md D2). Only
   * offered when the map is.
   *
   * @param {{minRating: ?number, limitMinutes: ?number}} [lens] the Plan's live lens, from the banner
   */
  const handleAuroraViewOnMap = ({ minRating = null, limitMinutes = null } = {}) => {
    const trigger = {
      ...normalizeMapTrigger({ kind: 'aurora', date: auroraNightStr }),
      minRating,
      limitMinutes,
    };
    if (mapViewOpen) {
      closeShellDialogs.current?.();
      openMapTabFromPlan({ ...trigger, inPlace: true });
    } else {
      openMapTabFromPlan(trigger);
      mapViewDoors.current?.open({});
    }
  };

  /**
   * The handoff the MAP VIEW should act on.
   *
   * <p>⚠️ Found by review and reproduced at 390px (when plan-card taps still opened an overlay). The
   * shell mounts a pane once and then hides it rather than unmounting it, so once the Map tab has
   * been visited its `MapView` is alive for the rest of the session. On a phone `MapView` answers a
   * location handoff with a `BottomSheet`, which is `createPortal(…, document.body)` at
   * `z-index: 10000`, so `display: none` on the panel cannot suppress it: a handoff the reader did
   * not ask for would raise a sheet from a map that is not on screen and lock body scroll.
   *
   * <p>So the view is handed a handoff only when the reader explicitly asks to be taken to it - a
   * door (`openMapTabFromPlan`) and nothing else.
   */
  const [mapTabHandoff, setMapTabHandoff] = useState(() => (rewind?.focus ? {
    // The rewind's window, as a map-sourced handoff: `source: 'map'` is the one shape that selects
    // a window as an EXPLICIT choice (so the auto-selection effect cannot replace it a tick later)
    // while carrying no lens — no floor, tier, scope or camera to overwrite. It opens nothing: every device opens on
    // This week.
    source: 'map',
    eventType: rewind.focus.eventType,
    date: rewind.focus.date,
    locationName: null,
    nonce: -1, // reserved for this seed — see `handoffNonce` above
  } : null));

  /**
   * The location a four-day sheet should be opened for, asked for from the Map tab's selection
   * callout (map-tab-v2-plan.md §3 P9) — the Map door's shape, in reverse. Kept SEPARATE from
   * `mapTabHandoff`, which is the forward direction, so a handoff that arrived on some OTHER channel must not be
   * mistaken for one the reader explicitly asked for.
   */
  const [locationSheetHandoff, setLocationSheetHandoff] = useState(null);

  /**
   * A place or region the reader picked in FIND while on the Map tab (find-and-favourites-plan.md F1): fly
   * there and open its callout, or pan and frame the region. Its own channel, for the reason the two above are
   * kept apart from the others: the Map pane is never unmounted, so only an explicit pick may reach it, and
   * it is nonce'd because the same place can be picked twice running and the second pick must move the map
   * back if the reader has moved it since. Not `mapTabHandoff`: that channel is a DOOR (it carries a window
   * and, from the Plan tab, the Plan's whole lens), and a Find pick keeps the reader's own window and filters.
   * `mapFindOpen` is the shell telling the pane Find is up, so the pane closes its menus (they are not dialogs).
   */
  const [mapFindPick, setMapFindPick] = useState(null);
  const [mapFindOpen, setMapFindOpen] = useState(false);
  const openFindOnMap = (pick) => setMapFindPick({ ...pick, nonce: handoffNonce.current++ });

  /**
   * Open one location's four-day sheet, asked for from the Map tab's selection callout.
   *
   * <p>⚠️ <b>The move off the map is the callout's choice, not this function's.</b> The callout has two routes into the same sheet and they differ only in where
   * the reader ends up: the clamped prose's `Four days here ›` opens it OVER the map, so closing it
   * puts the reader back on the callout they pressed it from (an owner ask — "I'd like it to stay
   * with the map behind, then I can back track on my user journey"), while the actions row's
   * `Open in Plan` names the screen and still goes there. `spot.inPlan` carries which, and
   * `WindowFirstShell`'s handoff effect performs the move ITSELF: it closes the layers and then writes
   * `sheetSpot` in the SAME synchronous body (i.e. the same React batch), which is the whole reason
   * the sheet survives the dialog-clearing. Two channels would put those two writes
   * in two effects and make the outcome depend on their declaration order.
   *
   * @param {{id: *, name: string, regionName: ?string, inPlan: boolean, date: ?string,
   *        targetType: ?string}} spot the sheet's own identity shape
   *        (`id`, `name`, `regionName`), the destination flag, and the window the map is
   *        on (which the shell turns into the sheet's `focusWindowKey`) — all built by
   *        `MapView.jsx`'s caller
   */
  const openLocationSheet = (spot) => {
    setLocationSheetHandoff({ ...spot, nonce: handoffNonce.current++ });
  };

  /**
   * A door from the Plan tab onto the Map tab (doors D2, `plan-to-map-doors-plan.md` §3 D2 task 1)
   * — `door = {date, targetType, region: ?string, minRating: ?number, limitMinutes: ?number,
   * locationName: ?string}`, already carrying the Plan's own lens values (read at the moment of the
   * tap by `WindowFirstShell`'s internal `openMapTab`, never re-read here).
   *
   * <p>Rides the `mapTabHandoff` channel documented above: the Map pane is never
   * unmounted, so a handoff on it must only ever arrive when the reader explicitly asked to be
   * taken there, which a door tap is. A door is told apart from a window change made in place by
   * `source: 'plan'` against `source: 'map'`.
   *
   * <p><b>Origin is deliberately NOT in the payload</b> (plan §2, §4 #1) — it is shared state the
   * Map tab already reads from the SAME `WindowFirstBriefingContext` the Plan tab does, so sending
   * it here would be the increment's own `org`-in-the-URL mistake in reverse: a parameter the map
   * never reads because it already has a truer answer to the same question.
   *
   * <p>⚠️ <b>`door.inPlace` is not a door at all</b> — it is the same footer pressed from a sheet
   * that is already OVER the map, and it takes the other branch below for the reasons stated there.
   */
  const openMapTabFromPlan = (doorOrTrigger) => {
    // Overlay convergence C1: a NORMALISED TRIGGER (`utils/mapDoor.normalizeMapTrigger`, the seven
    // producer shapes - it names its `kind`) is turned into the structured door here
    // (`utils/mapDoor.doorFromTrigger`); a plain door (no `kind`) passes through untouched.
    const door = doorOrTrigger?.kind
      ? doorFromTrigger(doorOrTrigger, {
        locations: visibleLocations,
        briefingScores,
        todayStr,
        tomorrowStr,
        // The served briefing's neighbour-outlier marks. `App` sits OUTSIDE the briefing provider, so
        // the shell (which reads it) rides them on the trigger; absent reads as none marked.
        outlierKeys: doorOrTrigger.outlierKeys,
      })
      : doorOrTrigger;
    // A night door names a night, which `App.resolveMapDate` refuses as a solar date once past -
    // the `isNight` provenance every night selection records. Absent for
    // every solar door, so their call is unchanged.
    if (door.night) selectDate(door.date, { isNight: true });
    else selectDate(door.date);
    if (door.inPlace) {
      // ⚠️ The reader is ALREADY on the Map tab — the four-day sheet was opened over it by the
      // callout's `Four days here ›` peek, and this is its footer's `Show on map → <window>`. A
      // door PAYLOAD here would be actively destructive rather than merely redundant: `MapView`'s
      // landing effect writes `minRating` (and PERSISTS it to `mapFilterMinStars`), writes
      // `limitMinutes`, and — because a sheet door always carries `region: null` — calls
      // `resetToMyArea()`, which flips scope back to My area and refits the camera. So a reader who
      // had just set a 4★ floor and jumped to a region on this very map would have all three
      // silently undone by a press that only ever asked to change the WINDOW. The breadcrumb would
      // then appear over it reading "Where you came from · ← Plan", which is not where they came
      // from.
      //
      // ⚠️ `source: 'map'`, NOT an absent source. A source-less payload is no longer a handoff at
      // all (the overlay hatch's per-field effects, which set `userHasOverriddenEvent` to FALSE and
      // so let `autoEventType` replace the named window a tick later, were deleted in overlay
      // convergence C5, and the pane drops it). The payload rides the STRUCTURED channel, where the
      // event is treated as an explicit selection, and carries no lens fields at all — `MapView` skips the lens/scope/camera block
      // and the landing strip for a map-sourced one.
      setMapTabHandoff({
        source: 'map',
        eventType: door.targetType,
        date: door.date,
        locationName: door.locationName ?? null,
        // The aurora banner pressed on the Map tab itself (overlay convergence C4) names a NIGHT:
        // `MapView` follows its row, and still writes no lens, scope or camera.
        ...(door.night ? { night: door.night } : {}),
        nonce: handoffNonce.current++,
      });
      return;
    }
    // The shell shows the view (`openMap`): this only hands the map its landing, and the view's own opener (the
    // shell's `openMap`, which called this) raises it in the same batch.
    setMapTabHandoff({
      source: 'plan',
      eventType: door.targetType,
      date: door.date,
      region: door.region ?? null,
      minRating: door.minRating ?? null,
      limitMinutes: door.limitMinutes ?? null,
      locationName: door.locationName ?? null,
      // A window door's camera: home and the best place together. ABSENT unless the door asked.
      ...(door.frame ? { frame: door.frame } : {}),
      // The overlay-convergence fields (C1, C4). ABSENT, not null, when the door carries none, so a
      // plain door's payload is exactly what it was and `MapView` reads absence as "not asked".
      ...(door.night ? { night: door.night } : {}),
      ...(door.focusNames?.length ? { focusNames: door.focusNames } : {}),
      ...(door.focusPoints?.length ? { focusPoints: door.focusPoints } : {}),
      ...(door.filterAction ? { filterAction: door.filterAction } : {}),
      ...(door.darkSky ? { darkSky: true } : {}),
      // The hot-topic chip the Map selects on arrival (a Coming up weather chip's door); ABSENT otherwise.
      ...(door.topicType ? { topicType: door.topicType } : {}),
      nonce: handoffNonce.current++,
    });
  };

  /**
   * Operations, the admin's full-screen view (one-screen-plan.md D9): open exactly while the page's address says
   * `#manage...`, for an admin only. A non-admin's `#manage` is cleared from the address and no `ManageView` is ever
   * imported for them. Never a Back layer: its own hashes already give Back, and registering a `#manage` panel with the
   * dialog history is banned (`docs/engineering/reference/plan-tab.md`, "Back").
   */
  const operations = useOperationsRoute({ allowed: isAdmin });

  /**
   * Operations, asked for from the aurora banner: a SIMULATED alert has no map to show (it has no scores and no stored
   * run - `utils/auroraTab.js`), and the place it is made and its scores generated is Operations -> Runs -> Start a run.
   * ADMIN only, because the pane is withheld from everyone else.
   *
   * <p>`ManageView` reads the `#manage/runs/start` hash when it mounts (the view mounts as the address changes), so it
   * lands on the Start a run panel. DEFENSIVE: the banner sits under the map view's inert block, so a reader cannot press
   * it with the view open, but a view that IS open goes first and the hash waits for the dialog-history engine to settle
   * (a hash set while the view's entry is still being unwound would be taken for the engine's own, D4, D9). Tested
   * against the real engine in `AppAuroraBannerDoor.test.jsx`.
   */
  const openOperations = async () => {
    closeShellDialogs.current?.();
    if (mapViewOpen) {
      mapViewDoors.current?.close({ restoreFocus: false });
      await new Promise((resolve) => { setTimeout(resolve, 0); });
      await whenSettled();
    }
    operations.open('manage/runs/start');
  };

  /**
   * Whether the settings dialog is open — the one condition it mounts on below, and what the Plan
   * shell is told. The dialog is a sibling of the shell here, so the shell cannot see it arrive;
   * three routes open it (the masthead cog and nudge, and the Map tab's ⌂ through the map pane),
   * and the ⌂ is the one that never passes through the shell. Hearing this, the shell takes its own
   * dialogs down in the same commit.
   */
  const settingsOpen = Boolean(showSettings || settingsFocus);
  const closeSettings = () => {
    setShowSettings(false);
    setSettingsFocus(null);
    setSettingsReturnFocus(null);
  };
  return (
    // `app-safe` carries the viewport's safe-area insets as padding, so everything in normal flow below is inset
    // without knowing about them (index.css's "Safe areas" block lists the elements that DO have to know, because they
    // touch a viewport edge themselves). One layout on every screen: the map is a fixed VIEW over the shell
    // (`MapScreen`, one-screen-plan.md D11), so the page no longer recasts itself as a viewport-height flex column
    // while the map is showing.
    // Your places: a boolean, never the role, so a Lite reader's provider never calls the endpoint.
    <FavouritesProvider pro={role === 'PRO_USER' || role === 'ADMIN'}>
    <GuideProvider closeShellDialogs={closeAllShellDialogs} activeScreen={activeScreen}>
    <div className="app-safe min-h-screen bg-plex-bg">
      {/* The boundary and the provider wrap BOTH the banner block and <main>, in that order
          (aurora-banner-plan.md §4 D1). The aurora banner frames its field to the reader's
          planning area and breaks a tie by the HOME drive, and both live only in the briefing
          provider. Neither wrapper renders a DOM node (`PlanErrorBoundary` returns `children` when
          healthy, the provider is a bare context provider), so the banner block and <main> stay
          DIRECT children of the root flex column — `App.test.jsx` pins it. The provider is
          mounted INSIDE the boundary, not beside it, so a provider crash is caught the same way a
          shell crash is (§4.1) — the reader always lands on the same fallback rather than a blank
          page. The one behaviour change, stated: a crash now also removes the banner block
          (session expiry, the rewind pill, the run-complete and backend-down notices) behind the
          fallback, which carries its own Sign out and its own <main> (the boundary now sits outside
          this file's <main>, so the fallback supplies the landmark and the inset itself). */}
      <PlanErrorBoundary onSignOut={logout} onCrash={() => setActiveScreen('plan')}>
        {/* `locations` joins `homeSettingsVersion` here because the heat field's catalogue is
            a join across two contracts and only ONE of them carries geography: the briefing
            and scores payloads have no lat/lng and are not to be given any (plan §3). The
            shell already received the same memoised array; the provider needs it to build the
            join once for the strip, the row maps and the Map tab rather than three times. */}
        <WindowFirstBriefingProvider
          homeSettingsVersion={homeSettingsVersion}
          driveTimesVersion={driveTimesVersion}
          subRegionsVersion={subRegionsVersion}
          onOpenSubRegions={openSubRegionsSettings}
          homePlace={homePlace}
          comingUpLastSeenDate={comingUpLastSeenDate}
          setComingUpLastSeenAt={setComingUpLastSeenDate}
          locations={visibleLocations}
        >
          <ServedDatesReporter onChange={setServedDates} />
      <div>
        {/* With the map view open the session notice stays reachable: fixed over the view (z 49, between the view's 48
            and `Modal`'s 50), because a reader about to be signed out must be able to refresh the session. */}
        <div
          ref={sessionSlotRef}
          className={mapViewOpen ? 'fixed top-0 inset-x-0 z-[49]' : undefined}
          data-testid="session-banner-slot"
        >
          <SessionExpiryBanner />
        </div>
        {/* Everything else up here is covered by the view, so it is inert while the view stands (the screen column is
            made inert by the shell; this block and the footer sit outside it). */}
        <div inert={mapViewOpen} data-testid="app-banner-block">
        {isAdmin && rewind && <RewindPill rewind={rewind} />}
        <div className="max-w-4xl mx-auto px-4 mt-4">
          {/* The banner's two doors, each WITHHELD when it would lead nowhere (the dead-control ban): the
              Map tab when it is offered at all (a forecast, or a real alert - `auroraTabOn`), and
              Operations for a SIMULATED alert, which has no map (`utils/auroraTab.js`), for an admin. */}
          <AuroraBannerRoot
            onViewOnMap={hasForecastData || auroraTabOn ? handleAuroraViewOnMap : undefined}
            onOpenSimulated={isAdmin ? openOperations : undefined}
          />
          <div className="mt-2">
            {/* Inert: the window-first Plan has no Map tab to switch to and no route to give it. */}
            <NlcSightingBanner eligible={role === 'PRO_USER' || role === 'ADMIN'} />
          </div>
        </div>

        {isDown && (
          <div
            className="bg-red-900/40 border-b border-red-700 py-3"
            data-testid="backend-down-banner"
            style={{ width: '100%', boxSizing: 'border-box', overflow: 'hidden' }}
          >
            <p className="max-w-4xl mx-auto px-4 text-sm text-red-300 text-center">
              Service is temporarily unavailable. Data shown may be stale.
            </p>
          </div>
        )}
        </div>
      </div>

      <main className="px-4 py-6">
        {/* isDown is passed DOWN rather than applied here: the shell's masthead carries the cog
            and Sign out, and greying the whole subtree would strand a user with no route out of a
            broken app. */}
            {/* Ask PhotoCast's conversation (plan §2.6). Inside the briefing provider (it reads the
                briefing and the HOME reach map from it) and around the shell, so every Ask surface
                the shell draws — and, from F3, the map pane it hands over — sees one conversation.
                ⚠️ NOT MOUNTED UNDER A REWIND: the provider's first act is `GET /api/user/settings/ask`,
                and the axios interceptor stamps the rewound clock on every GET outside `/api/admin/`,
                while a POST sees the real clock (plan §1 #21). Without a provider `useAsk()` is Ask
                "off" and no Ask surface exists. `RewindGate` remounts this whole tree on every change,
                so the choice never has to flip in place. (The shell below is deliberately NOT
                re-indented one level, for the reason the tab row's wrapper in the shell gives.) */}
            <AskWhenLive rewound={Boolean(rewind)}>
            <WindowFirstShell
              mapColourScale={mapColourScale}
              onScreenChange={setActiveScreen}
              registerOpenMap={registerOpenMap}
              loadMapRoute={loadMapRoute}
              onLoadMapRouteHandled={consumeMapRoute}
              onOpenSettings={() => setShowSettings(true)}
              settingsOpen={settingsOpen}
              onSignOut={logout}
              // The dates the reader holds a colour forecast for — the Coming up tab withholds a
              // map door whose date is not among them (a 90-day feed, a few forecast days).
              // ⚠️ Three-valued: `null` means "not known yet" and leaves the doors as they were —
              // while the first fetch is outstanding, or when it failed with nothing on screen (a
              // failed request is not knowledge of an absence); only a settled list can withhold
              // one, and locations left by a cache hydrate or an earlier fetch keep theirs real.
              forecastDates={
                forecastsLoading || (forecastsError && visibleLocations.length === 0) ? null : allDates
              }
              light={todaysLight}
              // The Map pane's own home marker source, reused so the Plan surfaces' home marker and
              // reach rings can never name a different point (field-geography plan §2.1).
              homeCoords={homeCoords}
              // The band's nudge exists to get a postcode saved, so it lands ON that field
              // rather than on the settings screen in general — the same field the map's
              // "you have no postcode" branch opens. It also hands over a way to find its own
              // successor, for the dialog's close (see `settingsReturnFocus`).
              onSetPostcode={(returnFocus) => {
                setSettingsReturnFocus(() => (typeof returnFocus === 'function' ? returnFocus : null));
                setSettingsFocus('postcode');
              }}
              contentDisabled={isDown}
              // The map doors (D2) — withheld under the identical rule that withholds `mapPane`
              // and `onOpenFullMap` below: a door onto no map is what §6 of the matrix plan bans.
              // No door UI ships in this phase (D3/D4 add the buttons), but the shell's own
              // `openMapTab` reads this prop already, so the wiring is live from here on.
              onOpenMapTab={hasForecastData ? openMapTabFromPlan : undefined}
              registerCloseDialogs={registerCloseDialogs}
              // The same admin gate the Operations pane uses, and for the same reason: the role
              // stays here, and the shell renders whatever node it is handed. Withheld for a
              // pilot user, who has no use for a build id or a WorldTides latency. The pill is
              // fed by `HealthStatusProvider`.
              healthPill={isAdmin ? (
                <HealthStatusPill />
              ) : null}
              // The phone masthead's two admin-only readings of the same health data (compact-chrome
              // plan R1): the 8px dot beside ⋯, and the ⋯ menu's status line that opens the panel the
              // dot owns. Same gate, same reason — nothing role-shaped crosses into the shell.
              healthDot={isAdmin ? <HealthStatusDot /> : null}
              healthStatusLine={isAdmin ? <HealthStatusMenuLine /> : null}
              onEvaluationScoresChange={handleEvaluationScoresChange}
              onSeasonalFeaturesChange={handleSeasonalFeaturesChange}
              locations={visibleLocations}
              locationSheetHandoff={locationSheetHandoff}
              // Find's Map picks (a place or a region), and Find's open state for the Map's menus. Withheld
              // with the Map door, under the same rule: a pick onto no map is a control that does nothing.
              onFindOnMap={hasForecastData || auroraTabOn ? openFindOnMap : undefined}
              onFindOpenChange={setMapFindOpen}
              // Withheld when there is nothing to map, which is the same rule the Operations tab
              // follows and §6's ban on controls that open nothing. `hasForecastData` is false
              // whenever `GET /api/forecast` returned no rows for a visible location, and a Map tab
              // onto no data would be a tab onto a blank. (`allDates` can be empty while this is
              // true — a hides-only forecast — and then the pane draws chips and no window.)
              mapPane={hasForecastData || auroraTabOn ? (
                <Suspense fallback={<ViewFallback />}>
                  <WindowFirstMapPane
                    locations={visibleLocations}
                    dates={allDates}
                    selectedDate={effectiveDate}
                    onSelectDate={selectDate}
                    // Without this the pane's event type is whatever it derived at mount — and
                    // because this pane is never unmounted, opening the map at dawn and returning
                    // after sunset would still show the morning's event.
                    autoEventType={autoSelection?.eventType ?? null}
                    handoff={mapTabHandoff}
                    findPick={mapFindPick}
                    findOpen={mapFindOpen}
                    briefingScores={briefingScores}
                    // The Map tab's older-day fetch (outcome-rating-plan.md §2 #2): `useForecasts` owns
                    // the fetch and merges the day into `locations`, so `allDates` above widens by itself.
                    onLoadOlderDay={loadOlderDay}
                    olderDayLoading={olderDayLoading}
                    loadedPastDays={loadedPastDays}
                    emptyPastDays={emptyPastDays}
                    seasonalFeatures={seasonalFeatures}
                    homeCoords={homeCoords}
                    mapColourScale={mapColourScale}
                    mapTideMode={mapTideMode}
                    saveTideMode={saveTideMode}
                    onOpenSettings={() => setSettingsFocus('postcode')}
                    onOpenLocationSheet={openLocationSheet}
                  />
                </Suspense>
              ) : null}
              // The admin gate, in full. The shell takes no role, no `isAdmin` boolean and no prop shaped like one: it
              // draws the ☰ menu's Operations row and the view only for a pane it was handed, so withholding the pane
              // (and the opener) withholds both. The role stays here, where it already lives, and nothing role-derived
              // crosses this boundary (plan §5c).
              operationsPane={isAdmin ? (
                <Suspense fallback={<ViewFallback />}>
                  <ManageView onComplete={refresh} />
                </Suspense>
              ) : null}
              operationsOpen={operations.isOpen}
              onOpenOperations={isAdmin ? () => operations.open() : undefined}
              onCloseOperations={operations.close}
            />
            </AskWhenLive>
      </main>
        </WindowFirstBriefingProvider>
      </PlanErrorBoundary>

      <footer className="border-t border-plex-border px-4 py-4 mt-8" inert={mapViewOpen} hidden={operations.isOpen}>
          <div className="max-w-4xl mx-auto text-center text-xs text-plex-text-muted">
            <div className="flex justify-center gap-4">
              <a
                href="https://www.instagram.com/photocastuk"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="Instagram"
                className="text-plex-text-muted hover:text-plex-gold transition-colors"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="5" ry="5"/><path d="M16 11.37A4 4 0 1 1 12.63 8 4 4 0 0 1 16 11.37z"/><line x1="17.5" y1="6.5" x2="17.51" y2="6.5"/></svg>
              </a>
              <a
                href="https://www.facebook.com/photocast"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="Facebook"
                className="text-plex-text-muted hover:text-plex-gold transition-colors"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M18 2h-3a5 5 0 0 0-5 5v3H7v4h3v8h4v-8h3l1-4h-4V7a1 1 0 0 1 1-1h3z"/></svg>
              </a>
            </div>
          </div>
        </footer>

      {settingsOpen && (
        <ChunkErrorBoundary
          variant="overlay"
          label="Settings"
          onClose={closeSettings}
        >
        <Suspense fallback={<SettingsFallback onBack={closeSettings} />}>
        <UserSettingsModal
          focusField={settingsFocus}
          restoreFocusFallback={settingsReturnFocus}
          onClose={closeSettings}
          // After mount the page hears of changes to the reader's home and colour only from the
          // dialog's answers — its own read on opening, a saved home, a recalculation, a saved
          // colour — and nothing moves on a close. Each save reports from its own continuation,
          // so one still in flight when the dialog closes reports when it lands. See
          // `useReaderSettings`.
          startSettingsRead={startSettingsRead}
          onHomeSaved={homeSaved}
          onDriveTimesRecalculated={driveTimesRecalculated}
          onColourSaved={colourSaved}
          colourSaveQueue={colourSaveQueue}
          onDriveTimesRefreshed={refresh}
          // Account deletion (the dialog's danger zone) ends the session through the same sign-out.
          onSignOut={logout}
          onSubRegionsSaved={handleSubRegionsSaved}
        />
        </Suspense>
        </ChunkErrorBoundary>
      )}

      {/* The zero-picks prompt (sub-regions-plan §4.1): a Lite reader with enforcement on and no picks.
          Renders nothing, and asks nothing for Pro and Admin, while the flag is off. */}
      <SubRegionSignInPrompt userRole={role} suppressed={!!rewind} onSaved={handleSubRegionsSaved} />

      {showPostcodePrompt && (
        <PostcodePromptOverlay onSaved={handlePromptSaved} onNotNow={handlePromptNotNow} />
      )}

    </div>
    </GuideProvider>
    </FavouritesProvider>
  );
}

/**
 * Root application component. Wraps the app in the authentication provider.
 */
export default function App() {
  // Strips a Back marker a previous page load left on this entry and heals `scrollRestoration`. Idempotent: the
  // first dialog registration boots the engine lazily as well, so this is only what makes a reload onto a
  // 'manual' entry heal before any dialog has opened (see `utils/dialogHistory.js`).
  useEffect(() => { bootDialogHistory(); }, []);
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  );
}
