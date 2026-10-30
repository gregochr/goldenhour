package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.service.BriefingDigestService;
import com.gregochr.goldenhour.service.BriefingRoleShaper;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.LitePickShaper;
import com.gregochr.goldenhour.service.PickScope;
import com.gregochr.goldenhour.util.Authorities;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for the daily briefing — a zero-Claude-cost pre-flight check
 * that reports weather and tide conditions across all enabled colour locations.
 *
 * <p>Serves the cached briefing result. The cache is populated by the scheduled
 * briefing refresh job every 2 hours. Also provides admin endpoints for briefing
 * model comparison testing.
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService briefingService;
    private final BriefingDigestService briefingDigestService;
    private final BriefingRoleShaper briefingRoleShaper;
    private final LitePickShaper litePickShaper;

    /**
     * Constructs a {@code BriefingController}.
     *
     * @param briefingService          the service that manages the briefing cache
     * @param briefingDigestService    the service that projects the briefing into the digest
     * @param briefingRoleShaper       the shaper that strips Pro-only tide data for LITE readers
     * @param litePickShaper           the shaper that strips ratings outside a Lite reader's sub-region picks
     */
    public BriefingController(BriefingService briefingService,
            BriefingDigestService briefingDigestService,
            BriefingRoleShaper briefingRoleShaper, LitePickShaper litePickShaper) {
        this.briefingService = briefingService;
        this.briefingDigestService = briefingDigestService;
        this.briefingRoleShaper = briefingRoleShaper;
        this.litePickShaper = litePickShaper;
    }

    /**
     * Returns the most recent daily briefing, or 204 No Content if no briefing
     * has been generated yet.
     *
     * <p>Calls the API-facing variant of the cached-briefing accessor so the
     * Gate 2 honesty filter is applied to any region with zero Claude-scored
     * locations before the response leaves the service layer.
     *
     * <p><b>The body is role-shaped.</b> The tide curve and the per-location tide match are Pro
     * features (docs/engineering/tide-pro-gate-plan.md §7), so a reader who is neither PRO_USER nor
     * ADMIN gets the briefing with them removed. The check is fail-closed: anything that is not
     * positively PRO or ADMIN is served as LITE. The strip happens only here, at the HTTP edge, so
     * the digest and Ask keep the full data. A role variant is not personal data: the body depends
     * on the role alone, and the ETag is hashed from the body, so the two variants never share one.
     *
     * <p>The best-bet picks are Pro too (docs/engineering/pro-features-gate-plan.md §8): LITE gets
     * every {@code window.pick} removed, {@code bestBets} empty and {@code bestBetModel},
     * {@code bestBetStatus} and {@code bestBetsWithdrawn} null.
     *
     * <p>The tide at the light is Pro and Admin too (docs/engineering/tide-light-pro-plan.md): LITE
     * gets the spring and king topics without their alignment sentence, run row, resolved light and
     * aligned counts, no offset from the light on the window tide or the per-location facts, no
     * aligned light on a window badge, and the big-seas gap and aligned flags removed. PRO and ADMIN
     * are served the service object itself, byte-identical.
     *
     * <p><b>Enforcement (docs/engineering/sub-regions-plan.md S3).</b> With
     * {@code photocast.sub-regions.enforce} on, a caller who is not positively PRO_USER or ADMIN is also
     * shaped by their sub-region picks by {@link LitePickShaper}, after the live overlays: every slot outside
     * the picks is {@code gated} and loses its ratings, the region and window best and mean stars go, the
     * bluebell hot topic loses its ratings, and {@code allowedLocationIds} is served. That body is sent
     * {@code Cache-Control: no-store} (no ETag): it reveals the reader's picks. Unshaped bodies are unchanged.
     *
     * @param auth the caller's authentication
     * @return the cached briefing response with honesty filter applied, shaped for the caller's role
     */
    @GetMapping
    public ResponseEntity<DailyBriefingResponse> getBriefing(Authentication auth) {
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return ResponseEntity.noContent().build();
        }
        boolean pro = Authorities.hasRole(auth, UserRole.PRO_USER)
                || Authorities.hasRole(auth, UserRole.ADMIN);
        PickScope scope = litePickShaper.scopeFor(auth);
        DailyBriefingResponse byRole = pro ? briefing : briefingRoleShaper.forLite(briefing);
        return LitePickShaper.respond(litePickShaper.shapeBriefing(byRole, scope), scope);
    }

    /**
     * Returns a flat, bounded, chronological projection of the briefing's solar windows, or 204 No
     * Content when no briefing has been generated yet.
     *
     * <p>The same forecast {@link #getBriefing(Authentication)} serves, with the tree removed — for clients that
     * cannot afford to walk it. Bearer with no role gate, by inheritance from {@code /api/**} →
     * {@code .authenticated()}, but <b>role-shaped</b> like the briefing: the best-bet picks are Pro
     * (docs/engineering/pro-features-gate-plan.md §8), so a reader who is not positively PRO_USER
     * or ADMIN gets each window without its {@code pick}, {@code headline}, {@code regionName},
     * {@code locationName} and {@code locationId}, which are the pick's. The check is fail-closed.
     * It carries no per-user data (a role variant is not personal data), and the ETag is hashed
     * from the body, so the two variants never share a validator.
     *
     * @param limit how many windows to return, clamped into
     *              [1, {@value BriefingDigestService#MAX_LIMIT}]
     * @param auth the caller's authentication
     * @return the digest, shaped for the caller's role, or 204 when there is no briefing
     */
    @GetMapping("/digest")
    public ResponseEntity<BriefingDigestResponse> getDigest(
            @RequestParam(defaultValue = "" + BriefingDigestService.DEFAULT_LIMIT) int limit,
            Authentication auth) {
        BriefingDigestResponse digest = briefingDigestService.digest(limit);
        if (digest == null) {
            return ResponseEntity.noContent().build();
        }
        boolean pro = Authorities.hasRole(auth, UserRole.PRO_USER)
                || Authorities.hasRole(auth, UserRole.ADMIN);
        PickScope scope = litePickShaper.scopeFor(auth);
        return LitePickShaper.respond(litePickShaper.shapeDigest(pro ? digest : liteDigest(digest), scope), scope);
    }

    private static BriefingDigestResponse liteDigest(BriefingDigestResponse digest) {
        return new BriefingDigestResponse(digest.generatedAt(), digest.windows().stream()
                .map(BriefingDigestResponse.Window::withoutPick).toList());
    }

    /**
     * Triggers an immediate briefing refresh and waits for it. Admin-only.
     *
     * <p>Refused with 409 while a refresh is already running — typically the pipeline's own,
     * at the tail of a cycle. Queuing a second build behind it would only repeat the one the
     * admin is about to get anyway.
     *
     * @return 200 once the refresh is complete, or 409 Conflict if one was already running
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> runBriefing() {
        if (!briefingService.refreshBriefingIfIdle()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "A briefing refresh is already running."));
        }
        return ResponseEntity.ok(Map.of("status", "Briefing refresh complete."));
    }
}
