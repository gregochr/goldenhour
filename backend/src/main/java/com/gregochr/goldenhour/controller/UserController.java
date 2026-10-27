package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.UserSubRegionSummary;
import com.gregochr.goldenhour.service.DeletedAccount;
import com.gregochr.goldenhour.service.EmailClaims;
import com.gregochr.goldenhour.service.PasswordResetResult;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.UserSubRegionService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import com.gregochr.goldenhour.util.EmailValidator;
import com.gregochr.goldenhour.util.UniqueViolation;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for admin user management.
 *
 * <p>All endpoints require the {@code ADMIN} role. Users without this role receive 403.
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserController {

    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);

    /** What the email edit answers for a pending registration, whose old verification link it voided. */
    static final String EMAIL_UPDATED_PENDING =
            "Updated. The old verification link no longer works: resend verification to the new address.";

    private final UserService userService;
    private final UserEmailService userEmailService;
    private final RegistrationService registrationService;
    private final UserSubRegionService userSubRegionService;

    /**
     * Returns all registered users without exposing password hashes.
     *
     * @return 200 with a list of user summary maps
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<AppUserEntity> all = userService.listAllUsers();
        Map<Long, UserSubRegionSummary> standing = userSubRegionService.adminSummaries(all);
        Map<Long, Instant> verificationSent = registrationService.verificationSentAtByUser();
        List<Map<String, Object>> users = all.stream()
                .map(user -> toSummary(user, standing.get(user.getId()), verificationSent.get(user.getId())))
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Creates a new user account.
     *
     * <p>A lost race on the username key or the email reservation surfaces from the service as a
     * {@link DataIntegrityViolationException} and is translated here, outside the rolled-back transaction,
     * to a 409 naming the conflicting field (login-registration plan §5 #6, §2 #14 rule 10).
     *
     * @param body map containing {@code username}, {@code password}, and {@code role}
     * @return 201 with the created user summary, 400 if the username or email already exists, or 409 for
     *         a concurrent duplicate
     */
    @PostMapping
    public ResponseEntity<Object> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String roleStr  = body.get("role");
        String email    = body.get("email");

        if (username == null || username.isBlank()
                || password == null || password.isBlank()
                || roleStr == null || roleStr.isBlank()
                || email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username, password, role, and email are required"));
        }

        if (!EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid email address: " + email));
        }

        UserRole role;
        try {
            role = UserRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid role: " + roleStr));
        }

        try {
            AppUserEntity created = userService.createUser(username, password, role, email.trim());
            userEmailService.sendWelcomeEmail(email.trim(), username, password);
            return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(created, null, null));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (DataIntegrityViolationException ex) {
            return conflict(ex);
        }
    }

    /**
     * Enables or disables a user account.
     *
     * @param id   the user's primary key
     * @param body map containing {@code enabled} (boolean)
     * @return 200 on success, or 400 if the user is not found
     */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<Object> setEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "enabled field required"));
        }
        try {
            userService.setEnabled(id, enabled);
            return ResponseEntity.ok(Map.of("message", "Updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Updates the email address of a user.
     *
     * <p>The edit voids the account's unused password-reset links and every verification link, so a link
     * mailed to the old mailbox cannot be used after the move; for a pending registration the message
     * says the admin must resend verification.
     *
     * @param id   the user's primary key
     * @param body map containing {@code email}
     * @return 200 on success, 400 if the email is missing, invalid, not ASCII, or user not found, or 409
     *         when another account already uses the address under any casing
     */
    @PutMapping("/{id}/email")
    public ResponseEntity<Object> setEmail(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email field required"));
        }
        if (!EmailValidator.isValid(email)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid email address: " + email));
        }
        try {
            boolean pending = userService.setEmail(id, email.trim());
            return ResponseEntity.ok(Map.of("message", pending ? EMAIL_UPDATED_PENDING : "Updated"));
        } catch (IllegalArgumentException ex) {
            if (EmailClaims.EMAIL_TAKEN.equals(ex.getMessage())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
            }
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (DataIntegrityViolationException ex) {
            return conflict(ex);
        }
    }

    /**
     * Changes the role of a user.
     *
     * @param id   the user's primary key
     * @param body map containing {@code role} (ADMIN, PRO_USER, or LITE_USER)
     * @return 200 on success, or 400 if the user is not found or role is invalid
     */
    @PutMapping("/{id}/role")
    public ResponseEntity<Object> setRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String roleStr = body.get("role");
        if (roleStr == null || roleStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "role field required"));
        }
        UserRole role;
        try {
            role = UserRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid role: " + roleStr));
        }
        try {
            userService.setRole(id, role);
            return ResponseEntity.ok(Map.of("message", "Updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Resets a user's password to a server-generated temporary password and forces a
     * password change on next login.
     *
     * <p>The temporary password is returned in the response body exactly once and an email
     * is sent to the user asynchronously. The admin still sees the password in the response
     * as a fallback.
     *
     * @param id the user's primary key
     * @return 200 with {@code {"temporaryPassword": "..."}} on success, or 400 if not found
     */
    @PutMapping("/{id}/reset-password")
    public ResponseEntity<Object> resetPassword(@PathVariable Long id) {
        try {
            PasswordResetResult result = userService.resetPassword(id);
            userEmailService.sendPasswordResetEmail(
                    result.email(), result.username(), result.temporaryPassword());
            LOG.info("Admin reset password for user id={}", id);
            return ResponseEntity.ok(Map.of("temporaryPassword", result.temporaryPassword()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Permanently deletes a user account.
     *
     * <p>The authenticated admin cannot delete their own account. The "account removed" email is sent here,
     * after the service's transaction has committed (login-registration plan §2 #7), to the address read
     * from the locked row; a delete that fails sends nothing.
     *
     * @param id        the user's primary key
     * @param principal the authenticated admin (used to prevent self-deletion)
     * @return 200 on success, 400 if the user is not found, 409 if self-deletion attempted
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteUser(@PathVariable Long id, Principal principal) {
        try {
            DeletedAccount deleted = userService.deleteUser(id, principal.getName());
            userEmailService.sendAccountDeletedEmail(deleted.email(), deleted.username());
            LOG.info("Admin deleted user id={}", id);
            return ResponseEntity.ok(Map.of("message", "User deleted"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Resends a verification email for a pending user. Admin action — bypasses rate limiting.
     *
     * @param id the user's primary key
     * @return 200 on success, 400 if user not found, has no email, or is no longer awaiting verification
     */
    @PostMapping("/{id}/resend-verification")
    public ResponseEntity<Object> resendVerification(@PathVariable Long id) {
        List<AppUserEntity> allUsers = userService.listAllUsers();
        AppUserEntity user = allUsers.stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElse(null);

        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User has no email address"));
        }

        if (!registrationService.adminResendVerification(user)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Account is not awaiting email verification"));
        }
        LOG.info("Admin resent verification email for user id={}", id);
        return ResponseEntity.ok(Map.of("message", "Verification email sent"));
    }

    /**
     * Translates a unique-key violation to a 409 naming the field; any other violation is a bug and is
     * rethrown.
     *
     * @param ex the violation caught around a service call
     * @return the 409 response
     */
    private static ResponseEntity<Object> conflict(DataIntegrityViolationException ex) {
        String message = UniqueViolation.conflictMessage(ex).orElseThrow(() -> ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    /**
     * Converts an {@link AppUserEntity} to a safe summary map that excludes the password.
     *
     * @param user             the entity to convert
     * @param standing         the user's sub-region standing (sub-regions-plan S2, S4b), or null when none was
     *                         computed (a user just created)
     * @param verificationSent when the user's newest unverified link was minted, or null
     * @return map with id, username, role, enabled, createdAt, lastActiveAt, the sub-region fields and
     *         {@code awaitingVerification}
     */
    private Map<String, Object> toSummary(AppUserEntity user, UserSubRegionSummary standing,
            Instant verificationSent) {
        LocalDateTime createdAt = user.getCreatedAt();
        LocalDateTime lastActiveAt = user.getLastActiveAt();
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", user.getId());
        summary.put("username", user.getUsername());
        summary.put("email", user.getEmail() != null ? user.getEmail() : "");
        summary.put("role", user.getRole().name());
        summary.put("enabled", user.isEnabled());
        summary.put("createdAt", createdAt != null ? createdAt.toString() : "");
        summary.put("lastActiveAt", lastActiveAt != null ? lastActiveAt.toString() : "");
        Instant termsAcceptedAt = user.getTermsAcceptedAt();
        summary.put("termsAcceptedAt", termsAcceptedAt != null ? termsAcceptedAt.toString() : null);
        summary.put("termsVersion", user.getTermsVersion());
        summary.put("homePostcode", user.getHomePostcode());
        summary.put("marketingEmailOptIn", user.isMarketingEmailOptIn());
        summary.put("subRegions", standing != null ? standing.names() : List.of());
        summary.put("subRegionStatus", standing != null ? standing.status() : null);
        summary.put("freeUntil", standing != null && standing.freeUntil() != null
                ? standing.freeUntil().toString() : null);
        summary.put("nextChangeOn", standing != null && standing.nextChangeOn() != null
                ? standing.nextChangeOn().toString() : null);
        summary.put("overLimit", standing != null && standing.overLimit());
        // A self-registration still waiting on its verification link: disabled with the empty password marker,
        // the same shape RegistrationService.adminResendVerification acts on. An admin-disabled account holds a
        // password hash, so it is never mistaken for one.
        String password = user.getPassword();
        boolean awaiting = !user.isEnabled() && password != null && password.isEmpty();
        summary.put("awaitingVerification", awaiting);
        summary.put("verificationSentAt",
                awaiting && verificationSent != null ? verificationSent.toString() : null);
        return summary;
    }
}
