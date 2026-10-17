package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.AuthAttemptLimiter;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.DeletedAccount;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.Authorities;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Self-service account endpoints for the signed-in reader (login-registration plan §2 #7).
 *
 * <p>Not under {@code /settings}: deleting an account is not a setting. Open to every authenticated role —
 * a LITE reader may delete their own account as freely as a PRO one; the password re-check is the gate.
 */
@RestController
@RequestMapping("/api/user/account")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AccountController {

    /** The 403 body when the typed password does not match. */
    static final String PASSWORD_INCORRECT = "Password is incorrect";

    /** The 400 body when the request carries no password. */
    static final String PASSWORD_REQUIRED = "Password is required";

    /** The 401 body when there is no real signed-in principal. */
    static final String NOT_AUTHENTICATED = "Not authenticated";

    /** Deletion attempts one account may make in a window; the sixth is a 429. */
    static final int DELETE_ATTEMPT_LIMIT = 5;

    /** Prefix of the limiter key, followed by the normalised username. */
    static final String DELETE_KEY_PREFIX = "delete:";

    private final UserService userService;
    private final UserEmailService userEmailService;
    private final AuthAttemptLimiter attemptLimiter;

    /**
     * Permanently deletes the caller's own account, after a password re-check.
     *
     * <p>The order is deliberate: an administrator is refused first (the existing self-delete refusal,
     * extended — it keeps the sole admin from locking the instance out; another admin deletes an admin), a
     * missing password is a 400, and every attempt that reaches the password check is counted on the limiter
     * under {@code delete:<username>} (five per fifteen minutes, then 429) BEFORE the BCrypt comparison, so a
     * stolen session cannot guess the password at the hash's speed. The check is made outside any
     * transaction; {@link UserService#deleteOwnAccount} then deletes under the row lock and re-checks the
     * hash, the credential version and the role by row count, so a session superseded while the password
     * was being checked deletes nothing (401 {@code "Your session has changed. Sign in again."}). The
     * confirmation email is sent here, AFTER that transactional method has returned and committed, to the
     * address read from the locked row.
     *
     * @param authentication the caller's authentication
     * @param body           map containing {@code password}
     * @return 204 on success; 400 for a missing password; 401 when not authenticated or the session is stale;
     *         403 for a wrong password; 409 for an administrator; 429 when the attempt bound is reached
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteAccount(Authentication authentication,
            @RequestBody(required = false) Map<String, String> body) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", NOT_AUTHENTICATED));
        }
        if (Authorities.hasRole(authentication, UserRole.ADMIN)) {
            return adminRefusal();
        }
        String password = body == null ? null : body.get("password");
        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", PASSWORD_REQUIRED));
        }
        String username = authentication.getName();
        String key = DELETE_KEY_PREFIX + AuthAttemptLimiter.normalise(username);
        if (!attemptLimiter.tryAcquire(key, DELETE_ATTEMPT_LIMIT, Window.QUARTER_HOUR)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", AuthController.TOO_MANY_ATTEMPTS));
        }
        Optional<String> verifiedHash = userService.verifyPassword(username, password);
        if (verifiedHash.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", PASSWORD_INCORRECT));
        }
        DeletedAccount deleted;
        try {
            deleted = userService.deleteOwnAccount(username, verifiedHash.get(),
                    AuthController.sessionVersionExpectation());
        } catch (IllegalStateException ex) {
            // The row's role was ADMIN under the lock (promoted while the password was being checked).
            if (UserService.ADMIN_SELF_DELETE_REFUSED.equals(ex.getMessage())) {
                return adminRefusal();
            }
            throw ex;
        }
        userEmailService.sendAccountDeletedEmail(deleted.email(), deleted.username());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<Map<String, String>> adminRefusal() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", UserService.ADMIN_SELF_DELETE_REFUSED));
    }
}
