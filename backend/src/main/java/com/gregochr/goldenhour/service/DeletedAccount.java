package com.gregochr.goldenhour.service;

/**
 * What a deletion hands back to its caller so the confirmation email can be sent AFTER the deleting
 * transaction has committed (login-registration plan §2 #7): the login name and the STORED address, both
 * read from the locked row, never from the request.
 *
 * @param username the deleted account's login name
 * @param email    the deleted account's stored address, or {@code null} when it had none
 */
public record DeletedAccount(String username, String email) {
}
