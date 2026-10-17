package com.gregochr.goldenhour.service;

/**
 * What a credential change left behind, read as a scalar projection under the user-row lock AFTER the
 * change (login-registration plan §2 #13, §2 #14 rule 1).
 *
 * <p>Every caller that mails or answers afterwards uses THIS, never the entity it resolved before the
 * BCrypt hash: an email change that committed during the hash would otherwise send an admin's temporary
 * password to the old, possibly compromised, mailbox.
 *
 * @param userId             the account's primary key
 * @param username           the login name
 * @param email              the STORED address as it stands now, or null
 * @param credentialsVersion the account's credential version after the change
 */
public record CredentialChangeOutcome(Long userId, String username, String email, long credentialsVersion) {
}
