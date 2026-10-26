package com.gregochr.goldenhour.entity;

/**
 * Application user roles controlling access to protected endpoints.
 */
public enum UserRole {
    /** Full access including user management. */
    ADMIN,
    /** Can add locations and trigger forecast reloads in addition to read access. */
    PRO_USER,
    /**
     * Read-only access to the forecast. Rating how a past sunrise or sunset turned out is
     * Pro/Admin only.
     */
    LITE_USER
}
