package com.gregochr.goldenhour.exception;

/**
 * Thrown when a credential change is refused because the account's credentials moved since the caller's
 * session was issued: the access token's {@code cv} claim no longer equals the row's
 * {@code credentials_version}, or the account stopped being usable meanwhile.
 *
 * <p>{@link com.gregochr.goldenhour.controller.GlobalExceptionHandler} answers it with a 401 and the
 * message below, which the client's existing session-expired path already handles. It exists for a
 * stolen or superseded session that finishes its BCrypt hash AFTER the legitimate change committed and
 * would otherwise overwrite the new password, revoke the legitimate replacement tokens and be issued
 * fresh ones (login-registration plan §2 #13).
 */
public class StaleCredentialsException extends RuntimeException {

    /** The client-safe sentence every stale-session refusal carries. */
    public static final String MESSAGE = "Your session has changed. Sign in again.";

    /**
     * Constructs the exception with the standard client-safe message.
     */
    public StaleCredentialsException() {
        super(MESSAGE);
    }
}
