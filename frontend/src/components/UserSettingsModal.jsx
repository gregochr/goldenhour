import {
  useState, useEffect, useLayoutEffect, useCallback, useRef, useId,
} from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal';
import {
  getSettings, lookupPostcode, saveHome, refreshDriveTimes, saveMapColourPreferences,
} from '../api/settingsApi';
import { resolveMode } from '../utils/scoreRamp.js';
import { formatRelativeAge } from '../utils/relativeTime.js';
import { colourAfterRead, createColourSaveQueue, saveColourInTurn } from '../utils/colourSaveQueue.js';

const ROLE_LABELS = {
  ADMIN: { text: 'Admin', cls: 'bg-red-900/40 border-red-500/50 text-red-300' },
  PRO_USER: { text: 'Pro', cls: 'bg-amber-900/40 border-amber-500/50 text-amber-300' },
  LITE_USER: { text: 'Lite', cls: 'bg-sky-900/40 border-sky-500/50 text-sky-300' },
};

/**
 * Settings modal — user profile, home postcode, and drive time management.
 */
/**
 * Slider fallback when the user has never chosen a radius. Display only — the value is not sent
 * unless the user actually moves the control, so the server keeps deciding what null means.
 */
const DEFAULT_RADIUS_MILES = 22;

/**
 * The lines this dialog moves focus ONTO — the new home, the refresh's status line, its "last
 * calculated" line, its error. They are text, not controls: `tabIndex={-1}` keeps them out of the
 * Tab order, and an indicator is drawn only for a keyboard landing, in the gold the dialog's
 * buttons use.
 *
 * <p>⚠️ An OUTLINE, not the buttons' `ring`, for two measured reasons. Forced-colours mode (Windows
 * High Contrast) removes every `box-shadow`, and a ring IS one, so the landing showed no focus there
 * at all; the mode keeps an outline and repaints it in a system colour. And a ring with its 2px
 * offset stood 4px proud of the line — exactly the `mt-1` between "Last calculated" and the Refresh
 * button above it, so it sat ON the button's edge. The outline stands 2px proud, and the
 * `-mx-1 px-1` pair gives the text room inside it without moving the text.
 *
 * <p>⚠️ Text landings only. Two landings are buttons — "Back to settings" (`.btn-secondary`) and
 * Refresh (`.btn-primary`) — and keep their class's ring, a box-shadow under `outline-none`, so in
 * forced colours they land with no indicator. Every `.btn-*` in the app shares that, so it is fixed
 * in those classes or not at all — not here.
 */
const LANDING_TARGET = '-mx-1 px-1 rounded-sm focus-visible:outline-2 focus-visible:outline-plex-gold';

/**
 * A button that says it is busy WITHOUT `disabled`. Measured in Chromium, WebKit and Firefox: a
 * focused button that becomes `disabled` loses focus — at once in Chromium, within one to four
 * frames in the other two — so the reader who pressed it is dropped on `<body>`, and a failed
 * request leaves them there. `aria-disabled` keeps it focusable; these copy `.btn-primary`'s own
 * `disabled:` treatment, and the handler refuses a second press.
 *
 * <p>⚠️ So it looks as it did in ordinary rendering, and not in two cases. Forced-colours mode
 * repaints a `disabled` button's text and border in the system's GrayText and leaves an
 * `aria-disabled` one in ButtonText (measured in Chromium), so there the busy state shows only as
 * the dimming. And the button KEEPS the focus a mouse press gave it — Chromium focuses a pressed
 * button, Safari does not — so `.btn-primary`'s ring, a `focus:` rule rather than `focus-visible:`,
 * stays drawn through the request where `disabled` used to take the focus, and the ring, away.
 */
const BUSY_BUTTON = 'aria-disabled:opacity-40 aria-disabled:cursor-not-allowed aria-disabled:hover:bg-plex-gold';

export default function UserSettingsModal({
  onClose, onDriveTimesRefreshed, startSettingsRead, onHomeSaved, onDriveTimesRecalculated,
  onColourSaved, colourSaveQueue = null, focusField = null, restoreFocusFallback = null,
}) {
  const [settings, setSettings] = useState(null);
  // The read on opening starts through the newest callback without the read re-running when the
  // callback's identity changes — it is made once, from a mount effect.
  const startSettingsReadRef = useRef(startSettingsRead);
  useEffect(() => { startSettingsReadRef.current = startSettingsRead; }, [startSettingsRead]);
  // Focused once settings have loaded, not on mount: the input is disabled for a LITE user and
  // the section only becomes meaningful with the payload in hand.
  const postcodeRef = useRef(null);
  const [loading, setLoading] = useState(true);
  const [postcode, setPostcode] = useState('');
  // Null until settings load, then the saved value or the 22-mile default. Kept separate from
  // the postcode's lookup/save cycle because the radius can be changed on its own.
  // Null until settings load. NOT defaulted to 22 here: a client-side default gets written back
  // on the next save and would silently override a server-configured one for a user who never
  // touched the slider.
  const [radius, setRadius] = useState(null);
  const [radiusSaving, setRadiusSaving] = useState(false);
  const [radiusError, setRadiusError] = useState(false);
  const [radiusChosen, setRadiusChosen] = useState(false);
  // `resolveMode(undefined)` — i.e. `DEFAULT_MODE` — until settings load, for the same reason
  // `useReaderSettings`' `mapColourScale` state seeds from `getMode()`: before the fetch resolves, "not
  // loaded yet" and "loaded and genuinely never chosen" read the same way, and both now mean
  // `'temp'`. Calling through `resolveMode` rather than hardcoding the literal is what keeps this
  // radio and the map it sits beside from ever being seeded to two different defaults again.
  const [mapColourScale, setMapColourScale] = useState(() => resolveMode(undefined));
  const [colourSaving, setColourSaving] = useState(false);
  const [colourError, setColourError] = useState(false);
  const [lookupResult, setLookupResult] = useState(null);
  const [lookupError, setLookupError] = useState(null);
  const [lookingUp, setLookingUp] = useState(false);
  const [saving, setSaving] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshResult, setRefreshResult] = useState(null);
  const [refreshError, setRefreshError] = useState(null);
  // Tracks the postcode that drive times were last calculated for.
  // Initialised from settings if drive times have been calculated before.
  const [driveTimesPostcode, setDriveTimesPostcode] = useState(null);
  // Current timestamp for relative "X min ago" labels. Read via an effect (not
  // during render) so the render stays pure (react-hooks/purity), and refreshed
  // every minute so the label stays current.
  const [now, setNow] = useState(null);

  // ── Where focus goes when a step of this dialog removes the control the reader pressed ─────────
  //
  // Five of this dialog's steps take the focused control away: a save that lands removes its own
  // Save button with the lookup result it sat in; the drive-time refresh swaps the whole body for a
  // spinner, then the spinner for a result — or, when it fails, back for the body; and "Back to
  // settings" swaps the result for the body. Each left focus on `<body>` INSIDE an open dialog — the
  // next Tab walked the page behind the backdrop, and a screen reader lost its place. So each such
  // step names where the reader lands instead, and the effect below moves them there once the new
  // content is in the DOM.
  //
  // ⚠️ Only when focus has actually been orphaned — `<body>`, the document, or this dialog's own
  // root — never when the reader has moved on: a Tab to the radius slider while a save is still in
  // flight is a choice, and the save landing must not take it back. That is `Modal`'s rule for a
  // dialog that stays open — "a reader who Tabbed out into the page while the top layer was up has
  // chosen where they are" — widened to the document, where `Modal` records a lost focus measured
  // landing, and to this dialog's root, where its opening frame or a press on blank space leaves a
  // reader: a container, not a place to work from. It is deliberately NOT `useDialogFocus`'s close
  // rule, which also pulls a reader back from outside a layer still claiming modality. That rule is
  // for a dialog being destroyed; here the layer claiming modality is this dialog, still open.
  //
  // A list, not one target, because a target can be absent from the content it expects (no
  // "last calculated" line without a timestamp) or refuse the focus; the dialog's own root is the
  // last resort, which is where an open would have put them.
  const [focusRequest, setFocusRequest] = useState(null);
  // The first element of whichever of the three views is showing — the settings body, the spinner,
  // the result — so the effect can find the dialog root without `Modal` handing out its ref.
  const viewRef = useRef(null);
  const homeLineRef = useRef(null);
  const refreshBtnRef = useRef(null);
  const refreshStatusRef = useRef(null);
  const refreshDismissRef = useRef(null);
  const driveCalcRef = useRef(null);
  const refreshErrorRef = useRef(null);
  const refreshResultId = useId();
  const refreshErrorId = useId();
  // Layout, not passive: the removal and the landing then happen in one task, so there is no frame
  // in which a keypress reaches `<body>`. Pinned by "lands the reader in the same commit that
  // removes the Save button": the page's own layout effect in that commit runs after this one and
  // already sees the landing, which a passive effect here would not yet have made.
  useLayoutEffect(() => {
    if (!focusRequest) return;
    const dialog = viewRef.current?.closest('[role="dialog"]') ?? null;
    const focused = document.activeElement;
    const orphaned = !focused || focused === document.body
      || focused === document.documentElement || focused === dialog;
    if (!orphaned) return;
    for (const ref of focusRequest) {
      const el = ref.current;
      if (!el) continue;
      el.focus();
      // Confirmed, not assumed: `focus()` is a silent no-op on a disabled control.
      if (document.activeElement === el) return;
    }
    dialog?.focus();
  }, [focusRequest]);

  // The page's one line of map-colour saves (`App` owns it, so it outlives this opening), or this
  // dialog's own where no page hands one down. See `handleMapColourChange` and `colourSaveQueue.js`.
  const [ownColourSaveQueue] = useState(createColourSaveQueue);
  const colourQueue = colourSaveQueue ?? ownColourSaveQueue;
  // This dialog's newest colour choice: only its outcome may set the status beside the radios.
  const colourChoice = useRef(0);

  /**
   * A choice an earlier opening left in the line, shown by this one (see `fetchSettings`): the
   * status beside the radios follows it as the opening it was made in would have — "Saving…" until
   * it lands, the error if it fails — until the reader chooses here, which takes the status over.
   */
  const followCarriedColour = useCallback(async () => {
    const mine = colourChoice.current;
    setColourSaving(true);
    const outcome = await colourQueue.newest;
    if (mine !== colourChoice.current) return;
    setColourSaving(false);
    setColourError(outcome === 'failed');
  }, [colourQueue]);

  const fetchSettings = useCallback(async () => {
    // Numbered by the page as it is ASKED, so an answer that lands after a newer one — this
    // dialog closed before it answered, then reopened and saved in — is dropped there.
    const report = startSettingsReadRef.current?.();
    // The line's landings so far: a colour save landing after this is newer than the answer.
    const landedWhenAsked = colourQueue.landed;
    try {
      const data = await getSettings();
      setSettings(data);
      if (data.homePostcode) setPostcode(data.homePostcode);
      setRadius(data.localRadiusMiles ?? DEFAULT_RADIUS_MILES);
      // Whether the value came from the SERVER or is just the slider's display fallback. Without
      // this the first postcode save wrote 22 into the column V136 deliberately leaves NULL,
      // converting "never chosen" into "chose 22" and overriding the server-side default for a
      // user who never touched the slider.
      setRadiusChosen(data.localRadiusMiles != null);
      // A choice from an earlier opening still in the line is what the server is about to hold, and
      // a save that landed while this read was out may be newer than the answer — the server can
      // take a read before a save commits, and it geocodes on every GET, so the read is the slow
      // one. Either outranks the answer, as the page's own order rule has it (`useReaderSettings`).
      setMapColourScale(colourAfterRead(colourQueue, landedWhenAsked) ?? resolveMode(data.mapColourScale));
      if (colourQueue.pending != null) followCarriedColour();
      if (data.driveTimesCalculatedAt && data.homePostcode) {
        setDriveTimesPostcode(data.homePostcode);
      }
      // A fresh read of the server, and how a home or its drive times changed elsewhere reach the
      // page. Nothing in THIS opening of the dialog can be saved until it has landed — the form is
      // not drawn before — but a save from an earlier opening can be, which is why it is numbered.
      report?.(data);
    } catch {
      // Settings fetch failed — modal will show skeleton state
    } finally {
      setLoading(false);
    }
  }, [colourQueue, followCarriedColour]);

  // Land on the field the caller opened the dialog for. The map's "centre on home" control, with
  // no postcode saved, is a signpost — it exists to say where the missing setting is — so it must
  // put the cursor there rather than leave the reader to find it among four sections.
  useEffect(() => {
    if (loading || focusField !== 'postcode') return;
    postcodeRef.current?.focus();
    postcodeRef.current?.select();
  }, [loading, focusField]);

  useEffect(() => {
    function tick() {
      setNow(Date.now());
    }
    tick();
    const id = setInterval(tick, 60_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    (async () => {
      await fetchSettings();
    })();
  }, [fetchSettings]);

  const handleLookup = async () => {
    // `lookingUp` as well: the button is `aria-disabled` rather than `disabled` while a lookup is
    // out (see BUSY_BUTTON), and Enter in the field reaches here too — a second press must not
    // start a second lookup racing the first for the result slot.
    if (!postcode.trim() || lookingUp) return;
    setLookingUp(true);
    setLookupError(null);
    setLookupResult(null);
    try {
      const result = await lookupPostcode(postcode.trim());
      setLookupResult(result);
    } catch {
      setLookupError('Invalid postcode');
    } finally {
      setLookingUp(false);
    }
  };

  const handleSave = async () => {
    // `saving` as well: the button stays focusable while the save is out (see BUSY_BUTTON).
    if (!lookupResult || saving) return;
    setSaving(true);
    try {
      // null when the user has never chosen one, so the column stays NULL and the server keeps
      // deciding what the default means.
      const updated = await saveHome(lookupResult.postcode, lookupResult.latitude,
        lookupResult.longitude, radiusChosen ? radius : null);
      // The save does not geocode, so its response names no place; the lookup it saved does, from
      // the same postcodes.io answer the settings read would give.
      const saved = { ...updated, homePlaceName: updated?.homePlaceName ?? lookupResult.placeName ?? null };
      setSettings(saved);
      setLookupResult(null);
      // Reported from here, not from the close: this continuation runs even if the dialog was
      // closed while the save was in flight, so a save that lands late still reaches the page.
      onHomeSaved?.(saved);
      // The Save button leaves with the lookup result it sat in. The line naming the new home is
      // what the press just did, so that is where the reader lands — or the field, if no line can
      // be drawn from what the server sent back.
      setFocusRequest([homeLineRef, postcodeRef]);
    } catch {
      // Save failed — leave lookup result visible for retry
    } finally {
      setSaving(false);
    }
  };

  /**
   * Persists the radius on its own.
   *
   * Requires a saved home, because the radius is measured FROM it — the field is disabled without
   * one. Sends the stored postcode back unchanged rather than inventing a radius-only endpoint:
   * the two settings belong to the same record and the same screen.
   */
  const handleRadiusCommit = async (next) => {
    if (!settings?.homePostcode || settings.homeLatitude == null) return;
    // Moving the slider IS choosing.
    setRadiusChosen(true);
    setRadiusSaving(true);
    setRadiusError(false);
    try {
      const updated = await saveHome(settings.homePostcode, settings.homeLatitude,
        settings.homeLongitude, next);
      // Merge rather than replace: saveHome's response carries no resolved place name (it does
      // not geocode), so assigning it wholesale blanked the "Durham, County Durham" line the
      // user was looking at.
      setSettings((prev) => ({ ...prev, ...updated, homePlaceName: updated?.homePlaceName
        ?? prev?.homePlaceName }));
      // Trust the server's echo over the local value — it clamps to 10-50.
      if (updated?.localRadiusMiles != null) setRadius(updated.localRadiusMiles);
    } catch {
      // Surfaced, not swallowed. A silent failure left the slider showing a value that was never
      // persisted, so the setting looked applied and the panel disagreed with it.
      setRadiusError(true);
    } finally {
      setRadiusSaving(false);
    }
  };

  /**
   * Persists the map colour preferences. Both fields are sent together — the endpoint has no
   * partial-update idiom, unlike `saveHome`'s radius, because nothing else shares this request.
   *
   * <p>Does not call `scoreRamp.setMode` itself. It reports the saved scale (`onColourSaved`), from
   * the save's own response, and `App`'s `useReaderSettings` wires `setMode` from it — the one place
   * a settings answer reaches the ramp, so Plan and Map can never disagree. Nothing is read again
   * after the save, so there is no second request to fail and leave the ramp on the old scale.
   *
   * <h3>⚠️ One save at a time, and the radios stay enabled</h3>
   *
   * <p>The radios used to sit in a `<fieldset disabled>` while a save was out. That serialised the
   * saves, and it dropped the reader: a focused radio whose fieldset becomes disabled loses focus
   * (measured — at once in Chromium, a frame later in WebKit and Firefox), so a keyboard reader
   * arrowing between the two scales was on `<body>` after the first press. It is the failure the
   * radius slider's own comment warns about, and the fix is the slider's: stay enabled, and say
   * "Saving…" in the live status beside the control.
   *
   * <p>But enabled radios let a second choice arrive while the first is still saving, and two
   * requests in flight can commit in either order — the server could end on the choice the reader
   * moved AWAY from. So every choice joins one line of saves for the whole page
   * (`colourSaveQueue.js`): one save at a time, in the order chosen, a choice overtaken by a newer
   * one skipped when its turn comes. The line belongs to `App`, not to this dialog, because a
   * closed dialog's saves keep running — a line per opening let a closed dialog's waiting choice go
   * out after a reopened dialog's newer one. The status and error beside the radios are this
   * dialog's newest choice's — or, until the reader chooses here, those of a choice an earlier
   * opening left in the line (`followCarriedColour`).
   */
  const handleMapColourChange = async (nextScale) => {
    setMapColourScale(nextScale);
    setColourError(false);
    setColourSaving(true);
    colourChoice.current += 1;
    const mine = colourChoice.current;
    const outcome = await saveColourInTurn(colourQueue, nextScale, {
      save: saveMapColourPreferences,
      onSaved: (updated, scale) => {
        setSettings((prev) => ({ ...prev, ...updated, homePlaceName: updated?.homePlaceName
          ?? prev?.homePlaceName }));
        // From the save's own continuation, as a saved home is: a save that lands after the dialog
        // has closed still reports. Only the scale — the response's home fields are left out, so a
        // colour save can never stand in for an answer about the home. Every save that lands
        // reports: it names what the server holds from that moment, and the line sends one at a
        // time, so the reports arrive in the order the choices were made.
        onColourSaved?.(updated?.mapColourScale ?? scale);
      },
    });
    // A newer choice made here owns the status now.
    if (mine !== colourChoice.current) return;
    setColourSaving(false);
    setColourError(outcome === 'failed');
  };

  /**
   * Recalculates the drive times, through three views of this one dialog: the settings body, a
   * spinner while the calculation runs, then the result — or the body again, with the error.
   *
   * <p>Each view replaces the last whole, so each carries its own landing (see `focusRequest`): the
   * spinner's status line, so the wait is announced rather than silent; the result's "Back to
   * settings", described by the count it reports; on a failure the Refresh button again, described
   * by the error, so a retry is one press away; and the error line itself should that button be
   * unable to take focus.
   */
  const handleRefresh = async () => {
    setRefreshing(true);
    setRefreshResult(null);
    setRefreshError(null);
    setFocusRequest([refreshStatusRef]);
    try {
      const result = await refreshDriveTimes();
      setRefreshResult(result);
      setSettings((prev) => prev ? { ...prev, driveTimesCalculatedAt: result.calculatedAt } : prev);
      setDriveTimesPostcode(settings?.homePostcode ?? null);
      onDriveTimesRefreshed?.();
      // The stamp alone. The server measured from the home it has stored, and `settings` here is
      // this render's copy, which a postcode save landing under the spinner has already overtaken.
      onDriveTimesRecalculated?.(result.calculatedAt);
      setFocusRequest([refreshDismissRef]);
    } catch (err) {
      const status = err?.response?.status;
      if (status === 429) {
        setRefreshError(err.response?.data?.message || 'Drive times were refreshed recently. Please wait before trying again.');
      } else if (status === 400) {
        setRefreshError('Set a home location first.');
      } else {
        setRefreshError('Something went wrong — please try again.');
      }
      setFocusRequest([refreshBtnRef, refreshErrorRef]);
    } finally {
      setRefreshing(false);
    }
  };

  /**
   * Back from the refresh's result to the settings body — the button and the backdrop both. The
   * Refresh button is disabled by now (the drive times match the postcode), so the landing is the
   * line the refresh just changed, "Last calculated: Just now".
   */
  const dismissRefreshResult = () => {
    setRefreshResult(null);
    setFocusRequest([driveCalcRef, refreshBtnRef]);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') handleLookup();
  };

  const roleBadge = settings?.role ? ROLE_LABELS[settings.role] : null;
  const hasHome = settings?.homePostcode != null;
  const isPro = settings?.role === 'ADMIN' || settings?.role === 'PRO_USER';

  const normalise = (pc) => (pc || '').replace(/\s+/g, '').toLowerCase();
  const postcodeChanged = hasHome && normalise(settings.homePostcode) !== normalise(driveTimesPostcode);

  // Verbose style ("Just now" / "5 min ago"); tiers live in the shared helper, which adds the
  // days tier this stamp needs — it only moved on a button press, so "744h ago" was reachable.
  const formatCalcTime = (iso) => (now == null ? null : formatRelativeAge(iso, { verbose: true, now }));

  // ⚠️ The three views below share ONE `Modal` (same type, same place in the tree — the dialog
  // root keeps its identity, which is right), so without a `key` on each view's first element React
  // reconciles one view's nodes INTO the next by position. Found by this component's own test: the
  // spinner's focused status `<p>` survived as the result's `<p>` — still focused, its `tabindex`
  // stripped. jsdom leaves focus there; the browsers do not (measured: removing `tabindex` from a
  // focused element sends focus to `<body>` at once in Chromium, within a few frames in WebKit and
  // Firefox), so the reader was on `<body>` again while `focusRequest`'s effect, having seen focus
  // still on the reused node, stood down. The keys make every view change a replacement, which is
  // what the landings assume.

  // Blocking spinner during drive time refresh. Its status line is the one thing in it that can
  // hold focus, and it is where the reader lands — see `handleRefresh`.
  if (refreshing) {
    return (
      <Modal
        label="Calculating drive times"
        restoreFocusFallback={restoreFocusFallback}
        data-testid="settings-modal"
      >
        <div key="refresh-spinner" ref={viewRef} className="flex flex-col items-center gap-3 py-6">
          <div className="w-8 h-8 border-2 border-plex-gold border-t-transparent rounded-full animate-spin" />
          <p
            ref={refreshStatusRef}
            tabIndex={-1}
            className={`text-sm text-plex-text ${LANDING_TARGET}`}
            data-testid="settings-refresh-status"
          >
            Calculating drive times…
          </p>
          <p className="text-xs text-plex-text-muted">Please wait — this takes a few seconds</p>
        </div>
      </Modal>
    );
  }

  // Success flash after refresh
  if (refreshResult) {
    return (
      <Modal
        label="Drive times updated"
        onClose={dismissRefreshResult}
        restoreFocusFallback={restoreFocusFallback}
        data-testid="settings-modal"
      >
        <div key="refresh-result" ref={viewRef} className="flex flex-col items-center gap-3 py-6">
          <span className="text-3xl">&#x2705;</span>
          <p id={refreshResultId} className="text-sm text-plex-text">
            Done — {refreshResult.locationsUpdated} locations updated
          </p>
          {/* Where the reader lands, and it carries the count as its description — so landing on
              it says what happened, and one press goes back. */}
          <button
            ref={refreshDismissRef}
            className="btn-secondary text-xs mt-2"
            onClick={dismissRefreshResult}
            aria-describedby={refreshResultId}
            data-testid="settings-refresh-dismiss"
          >
            Back to settings
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal
      label="Settings"
      onClose={onClose}
      restoreFocusFallback={restoreFocusFallback}
      data-testid="settings-modal"
    >
      <div key="settings" ref={viewRef} className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-plex-text">Settings</h2>
        <button onClick={onClose} className="text-plex-text-muted hover:text-plex-text text-lg" aria-label="Close">&times;</button>
      </div>

      {loading ? (
        <p className="text-sm text-plex-text-muted py-4">Loading…</p>
      ) : settings ? (
        <div className="flex flex-col gap-5">
          {/* Profile */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Profile</h3>
            <div className="flex flex-col gap-1 text-sm">
              <div className="flex items-center gap-2">
                <span className="text-plex-text">{settings.username}</span>
                {roleBadge && (
                  <span className={`text-xs px-2 py-0.5 rounded-full border ${roleBadge.cls}`} data-testid="settings-role-badge">
                    {roleBadge.text}
                  </span>
                )}
              </div>
              {settings.email && <span className="text-plex-text-secondary">{settings.email}</span>}
            </div>
          </section>

          {/* Home location — deliberately ungated.

              The postcode used to sit behind the same Pro gate as the drive times it feeds, on the
              reasoning that it exists FOR those drive times. It no longer does: the masthead's
              light rule is drawn from this postcode too, and its empty state nudges the reader
              here to set one. A nudge that lands on a control the reader cannot operate is a dead
              end, and it would leave the band permanently dim for exactly the accounts the nudge
              is written for. So the split moved one level down — light times free, drive times and
              the local radius Pro — and it is enforced on those two controls individually.

              Nothing was unlocked on the backend by this: `UserSettingsController` has only ever
              carried `@PreAuthorize("isAuthenticated()")`, so `PUT /home` was already open to any
              account. The gate was frontend-only. */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Home location</h3>
            <div>
              {hasHome && !lookupResult && (
                // Focusable only by the save that draws it (see `handleSave`), never by Tab.
                <p
                  ref={homeLineRef}
                  tabIndex={-1}
                  className={`text-sm text-plex-text mb-2 ${LANDING_TARGET}`}
                  data-testid="settings-home-current"
                >
                  <span className="inline-block w-2 h-2 rounded-full bg-green-500 mr-1.5" />
                  {settings.homePlaceName || settings.homePostcode}
                </p>
              )}
              <div className="flex gap-2">
                <input
                  type="text"
                  value={postcode}
                  onChange={(e) => setPostcode(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Enter UK postcode"
                  ref={postcodeRef}
                  className="flex-1 px-3 py-1.5 text-sm bg-plex-bg border border-plex-border rounded-lg text-plex-text placeholder:text-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                  data-testid="settings-postcode-input"
                />
                {/* Truly disabled only while the field is empty — a state the reader cannot reach
                    from ON this button. Busy is `aria-disabled` (see BUSY_BUTTON). */}
                <button
                  className={`btn-primary text-sm ${BUSY_BUTTON}`}
                  onClick={handleLookup}
                  disabled={!postcode.trim()}
                  aria-disabled={lookingUp || undefined}
                  data-testid="settings-lookup-btn"
                >
                  {lookingUp ? 'Looking up…' : 'Look up'}
                </button>
              </div>
              {lookupError && (
                <p className="text-sm text-red-400 mt-1" data-testid="settings-lookup-error">{lookupError}</p>
              )}
              {lookupResult && (
                <div className="mt-2 flex items-center gap-3" data-testid="settings-lookup-result">
                  <div>
                    <p className="text-sm text-plex-text">
                      <span className="inline-block w-2 h-2 rounded-full bg-green-500 mr-1.5" />
                      {lookupResult.placeName}
                    </p>
                    <p className="text-xs text-plex-text-muted">
                      {lookupResult.latitude.toFixed(4)}, {lookupResult.longitude.toFixed(4)}
                    </p>
                  </div>
                  <button
                    className={`btn-primary text-sm ml-auto ${BUSY_BUTTON}`}
                    onClick={handleSave}
                    aria-disabled={saving || undefined}
                    data-testid="settings-save-home-btn"
                  >
                    {saving ? 'Saving…' : 'Save'}
                  </button>
                </div>
              )}

              {/* Local radius — directly beneath the postcode it is measured from, and still Pro.
                  Nothing on the page reads it now: the map's ⌂ used to frame this radius, until
                  map-tab-v2 P11 made the ⌂ a scope reset; the server's Close to home endpoint
                  still does. It carries its own greying now that the section around it is open,
                  per the role-gating pattern. */}
              <div
                className={`mt-4${!isPro ? ' opacity-45 pointer-events-none' : ''}`}
                data-testid="settings-local-radius"
              >
                <label
                  htmlFor="local-radius"
                  className="block text-sm text-plex-text mb-1"
                >
                  Local radius
                  <span className="text-plex-text-muted ml-2 text-xs">
                    How far counts as close to home.
                  </span>
                </label>
                <div className="flex items-center gap-3">
                  <input
                    id="local-radius"
                    type="range"
                    min="10"
                    max="50"
                    step="2"
                    value={radius ?? DEFAULT_RADIUS_MILES}
                    // NOT disabled while saving: toggling disabled mid-interaction blurs the
                    // slider and drops the keyboard focus a user is still arrow-keying with.
                    disabled={!isPro || !settings?.homePostcode}
                    onChange={(e) => setRadius(Number(e.target.value))}
                    // Commit on release, not on every drag frame: the slider spans 21 stops and
                    // a PUT per stop would hammer the endpoint for one decision.
                    onMouseUp={(e) => handleRadiusCommit(Number(e.target.value))}
                    onTouchEnd={(e) => handleRadiusCommit(Number(e.target.value))}
                    onKeyUp={(e) => handleRadiusCommit(Number(e.target.value))}
                    className="flex-1"
                    data-testid="settings-radius-slider"
                  />
                  <span
                    className="font-mono text-sm text-plex-text w-16 text-right"
                    data-testid="settings-radius-value"
                  >
                    {radius ?? DEFAULT_RADIUS_MILES} miles
                  </span>
                  {/* Feedback WITHOUT disabling the slider: toggling `disabled` mid-interaction
                      blurs the control and drops the focus of a user still arrow-keying it. */}
                  <span
                    className="font-mono text-xs text-plex-text-muted w-12"
                    aria-live="polite"
                    data-testid="settings-radius-status"
                  >
                    {radiusSaving ? 'Saving…' : ''}
                  </span>
                </div>
                {!settings?.homePostcode && (
                  <p className="text-xs text-plex-text-muted mt-1">
                    Set a home postcode first — the radius is measured from it.
                  </p>
                )}
                {radiusError && (
                  <p className="text-xs text-red-400 mt-1" data-testid="settings-radius-error">
                    Could not save the radius. Try again.
                  </p>
                )}
              </div>
            </div>
          </section>

          {/* Drive Times */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Drive times</h3>
            <div className={!isPro ? 'opacity-45 pointer-events-none' : undefined}>
              <button
                ref={refreshBtnRef}
                className="btn-primary text-sm w-full"
                onClick={handleRefresh}
                disabled={!isPro || !hasHome || !postcodeChanged}
                // A failed refresh lands the reader back here; the error is what they need to hear.
                aria-describedby={refreshError ? refreshErrorId : undefined}
                data-testid="settings-refresh-drive-btn"
              >
                Refresh drive times
              </button>
              {!hasHome && (
                <p className="text-xs text-plex-text-muted mt-1">Set a home location first.</p>
              )}
              {settings.driveTimesCalculatedAt && (
                <p
                  ref={driveCalcRef}
                  tabIndex={-1}
                  className={`text-xs text-plex-text-muted mt-1 ${LANDING_TARGET}`}
                  data-testid="settings-drive-calc-time"
                >
                  Last calculated: {formatCalcTime(settings.driveTimesCalculatedAt)}
                </p>
              )}
              {refreshError && (
                <p
                  id={refreshErrorId}
                  ref={refreshErrorRef}
                  tabIndex={-1}
                  className={`text-sm text-red-400 mt-1 ${LANDING_TARGET}`}
                  data-testid="settings-refresh-error"
                >
                  {refreshError}
                </p>
              )}
            </div>
          </section>

          {/* Map Colours — deliberately ungated. Reading the map is not a Pro feature, unlike the
              drive times and local radius above; every account should be able to try the scale. */}
          <section>
            <h3 className="text-xs font-medium text-plex-text-muted uppercase tracking-wide mb-2">Map colours</h3>
            <div className="flex flex-col gap-2">
              {/* NOT disabled while saving — the radius slider's rule, for the same measured reason:
                  a focused radio in a fieldset that becomes disabled loses focus, and the reader
                  arrowing between the two scales is dropped on `<body>` after the first press.
                  `handleMapColourChange` queues a choice made mid-save instead. */}
              <fieldset className="flex flex-col gap-1.5">
                <legend className="text-sm text-plex-text mb-1">Colour scale</legend>
                <label className="flex items-center gap-2 text-sm text-plex-text cursor-pointer">
                  <input
                    type="radio"
                    name="map-colour-scale"
                    value="verdict"
                    checked={mapColourScale === 'verdict'}
                    onChange={() => handleMapColourChange('verdict')}
                    data-testid="settings-map-colour-verdict"
                  />
                  Verdict — red means don&rsquo;t go, green means go
                </label>
                <label className="flex items-center gap-2 text-sm text-plex-text cursor-pointer">
                  <input
                    type="radio"
                    name="map-colour-scale"
                    value="temp"
                    checked={mapColourScale === 'temp'}
                    onChange={() => handleMapColourChange('temp')}
                    data-testid="settings-map-colour-temp"
                  />
                  Temperature — cold blue through gold to hot orange-red
                </label>
              </fieldset>
              <span
                className="font-mono text-xs text-plex-text-muted"
                aria-live="polite"
                data-testid="settings-colour-status"
              >
                {colourSaving ? 'Saving…' : ''}
              </span>
              {colourError && (
                <p className="text-xs text-red-400" data-testid="settings-colour-error">
                  Could not save the colour preference. Try again.
                </p>
              )}
            </div>
          </section>
          {!isPro && (
            <p className="text-center text-plex-text-secondary" style={{ fontSize: '13px' }}>
              Your postcode sets your light times. Upgrade to Pro for personalised drive times.
            </p>
          )}
        </div>
      ) : (
        <p className="text-sm text-red-400 py-4">Failed to load settings.</p>
      )}
    </Modal>
  );
}

UserSettingsModal.propTypes = {
  onClose: PropTypes.func.isRequired,
  onDriveTimesRefreshed: PropTypes.func,
  /**
   * Called as the dialog's own read of `GET /api/user/settings` starts, once per opening; it
   * returns the function the read's answer is reported through when it lands. `App`'s
   * `useReaderSettings` numbers the read by when it was asked and drops its answer if a newer one
   * has been applied by then; otherwise a home, or its drive times, changed elsewhere — on another
   * device, by the nightly drive-time job, or by a save whose response was lost — reach the page
   * here, and only a real change moves anything.
   */
  startSettingsRead: PropTypes.func,
  /**
   * Called with the settings a successful postcode save leaves — its response, carrying the
   * lookup's place name the save itself does not resolve. Not on a close, a failed save, a radius
   * save (which nothing on the page reads) or a colour save. `useReaderSettings` compares it with
   * its record, so re-saving the same postcode moves nothing.
   */
  onHomeSaved: PropTypes.func,
  /** Called with a successful drive-time recalculation's new stamp, and at no other time. */
  onDriveTimesRecalculated: PropTypes.func,
  /** Called with the saved scale, from a successful map-colour save's own response, and at no other time. */
  onColourSaved: PropTypes.func,
  /**
   * The page's one line of map-colour saves, from `createColourSaveQueue`. `App` owns it so it
   * outlives each opening of this dialog, and ends it when the reader signs out. Without one, the
   * dialog keeps a line of its own, which nothing ends — a caller rendering this dialog on its own
   * owns the session question too.
   */
  colourSaveQueue: PropTypes.shape({
    tail: PropTypes.object, latest: PropTypes.number, outstanding: PropTypes.number,
    pending: PropTypes.string, newest: PropTypes.object, landed: PropTypes.number, saved: PropTypes.string,
    ended: PropTypes.bool,
  }),
  /** Field to focus once settings load — `'postcode'`, or null to open normally. */
  focusField: PropTypes.oneOf(['postcode']),
  /**
   * Returns the element to hand focus to on close when the control that opened this dialog has
   * gone — `Modal`'s opt-in of the same name. `App` passes one only for the masthead nudge, whose
   * button a saved postcode can replace while this dialog is still open.
   */
  restoreFocusFallback: PropTypes.func,
};
