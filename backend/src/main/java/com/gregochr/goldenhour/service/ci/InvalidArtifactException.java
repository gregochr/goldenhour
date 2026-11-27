package com.gregochr.goldenhour.service.ci;

/**
 * A CI usage artifact that fails validation. The artifact is attacker-influenced (a same-repository pull request
 * controls the workflow), so the message is always a fixed reason chosen by the reader and never echoes any file
 * content.
 */
public class InvalidArtifactException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param reason a fixed, content-free reason
     */
    public InvalidArtifactException(String reason) {
        super(reason);
    }
}
