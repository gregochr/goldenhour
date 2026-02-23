package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.UserService;
import lombok.RequiredArgsConstructor;
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

    private final UserService userService;

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
        String roleStr = body.get("role");

        if (username == null || username.isBlank()
                || password == null || password.isBlank()
                || roleStr == null || roleStr.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "username, password, and role are required"));
        }

        UserRole role;
        try {
            role = UserRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid role: " + roleStr));
        }

        try {
            AppUserEntity created = userService.createUser(username, password, role);
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
                "role", user.getRole().name(),
                "enabled", user.isEnabled(),
                "createdAt", createdAt != null ? createdAt.toString() : "");
    }
}
