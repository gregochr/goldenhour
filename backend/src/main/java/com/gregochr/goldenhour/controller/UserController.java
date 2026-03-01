package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.PasswordResetResult;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserService userService;
    private final UserEmailService userEmailService;

    /**
     * Returns all registered users without exposing password hashes.
     *
     * @return 200 with a list of user summary maps
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> users = userService.listAllUsers().stream()
                .map(this::toSummary)
                .toList();
        return ResponseEntity.ok(users);
    }

    /**
     * Creates a new user account.
     *
     * @param body map containing {@code username}, {@code password}, and {@code role}
     * @return 201 with the created user summary, or 400 if the username already exists
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

        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
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
            return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(created));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
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
     * @param id   the user's primary key
     * @param body map containing {@code email}
     * @return 200 on success, or 400 if the email is missing, invalid, or user not found
     */
    @PutMapping("/{id}/email")
    public ResponseEntity<Object> setEmail(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email field required"));
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid email address: " + email));
        }
        try {
            userService.setEmail(id, email.trim());
            return ResponseEntity.ok(Map.of("message", "Updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
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
     * Converts an {@link AppUserEntity} to a safe summary map that excludes the password.
     *
     * @param user the entity to convert
     * @return map with id, username, role, enabled, and createdAt
     */
    private Map<String, Object> toSummary(AppUserEntity user) {
        LocalDateTime createdAt = user.getCreatedAt();
        return Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail() != null ? user.getEmail() : "",
                "role", user.getRole().name(),
                "enabled", user.isEnabled(),
                "createdAt", createdAt != null ? createdAt.toString() : "");
    }
}
