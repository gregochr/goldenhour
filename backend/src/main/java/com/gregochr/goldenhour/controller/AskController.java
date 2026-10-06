package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ask.AskErrorCode;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskRefusal;
import com.gregochr.goldenhour.service.ask.AskRequest;
import com.gregochr.goldenhour.service.ask.AskService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ask PhotoCast's reader-facing endpoints (plan §2.9): {@code GET /api/ask/ready} (B3) and the typed
 * {@code POST /api/ask} (B4).
 *
 * <p>Bearer, with <b>no role gate</b>, by inheritance from {@code SecurityConfig}'s
 * {@code /api/**} → {@code .authenticated()}: every role sees the same ratings in Ask as on the
 * Plan tab, and a Ready answer is the same for everyone, so a gate would deny nothing. It is pinned
 * across LITE, PRO, ADMIN and anonymous in {@code AskControllerTest}. While
 * {@code photocast.ask.enabled} is false every endpoint here is 404, so a switched-off feature has
 * no surface.
 */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskProperties properties;
    private final AskReadyService readyService;
    private final RegionRepository regionRepository;
    private final AskService askService;

    /**
     * Constructs the controller.
     *
     * @param properties       the Ask settings (the {@code enabled} flag)
     * @param readyService     serves the Ready answers
     * @param regionRepository validates a region scope
     * @param askService       answers typed questions
     */
    public AskController(AskProperties properties, AskReadyService readyService,
            RegionRepository regionRepository, AskService askService) {
        this.properties = properties;
        this.readyService = readyService;
        this.regionRepository = regionRepository;
        this.askService = askService;
    }

    /**
     * A typed question. Runs the guards in the plan's order ({@link AskService}); every refusal is
     * {@code {"error","code"}} (plan §2.9's table), rendered by the shared {@code AskRefusal} handler.
     * Never ETag-filtered: a POST is not a revalidatable read, and the answer is personal.
     *
     * @param request the question, optional window, regions and view
     * @param auth    the asker
     * @return 200 with the answer; 404 while Ask is switched off; the error table's 400, 429, 502 or
     *         503 otherwise
     */
    @PostMapping
    public ResponseEntity<?> ask(@RequestBody(required = false) AskRequest request,
            Authentication auth) {
        if (!properties.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(askService.ask(auth, request));
    }

    /**
     * A body that cannot be read (malformed JSON, a wrong type) is the same {@code INVALID} refusal as
     * any other bad question, so the one error shape holds. Controller-local, so it wins over the
     * application-wide handler, which has no {@code code}. The message is fixed: Jackson's own names
     * internal types and echoes caller-supplied values.
     *
     * @param ex the unreadable-body exception
     * @return 400 {@code {"error","code":"INVALID"}}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadableBody(HttpMessageNotReadableException ex) {
        if (!properties.isEnabled()) {
            // The body is read before the method runs, so the flag-off 404 is answered here too.
            return ResponseEntity.notFound().build();
        }
        AskRefusal refusal = new AskRefusal(AskErrorCode.INVALID, "The request body could not be read.");
        return ResponseEntity.status(refusal.code().status()).body(refusal.body());
    }

    /**
     * The Ready questions of a scope that are still true against live data, each with its answer
     * (plan §2.4). A question whose stored answer no longer agrees with the live forecast is left
     * out whole, never served with stale prose.
     *
     * <p>User-independent, so it is ETag-revalidated ({@code HttpCachingConfig}); the query string
     * does not affect the match, since the filter reads the request URI.
     *
     * @param scope {@code all} (the default), or the id of an enabled region
     * @return 200 with the questions; 400 for a scope that is neither {@code all} nor an enabled
     *         region's id; 404 while Ask is switched off
     */
    @GetMapping("/ready")
    public ResponseEntity<?> getReady(@RequestParam(name = "scope", defaultValue = "all") String scope) {
        if (!properties.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String wanted = scope.strip();
        if (wanted.toLowerCase(Locale.ROOT).equals("all")) {
            return ResponseEntity.ok(readyService.serve(AskReadyService.ALL, Set.of()));
        }
        Optional<RegionEntity> region = regionById(wanted);
        if (region.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "The scope must be 'all' or the id of an enabled region."));
        }
        return ResponseEntity.ok(readyService.serve(String.valueOf(region.get().getId()),
                Set.of(region.get().getName())));
    }

    /** The enabled region with this id, or empty for a non-number, an unknown id or a disabled one. */
    private Optional<RegionEntity> regionById(String text) {
        long id;
        try {
            id = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return regionRepository.findById(id).filter(RegionEntity::isEnabled);
    }
}
