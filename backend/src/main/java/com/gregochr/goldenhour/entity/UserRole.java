package com.gregochr.goldenhour.entity;

/**
 * Application user roles controlling access to protected endpoints.
 */
public enum UserRole {
    /** Full access including user management. */
    ADMIN,
    /** Can add locations and trigger forecast reloads in addition to read access. */
    PRO_USER,
    /** Read-only access — forecast viewing and outcome recording only. */
    LITE_USER
}
