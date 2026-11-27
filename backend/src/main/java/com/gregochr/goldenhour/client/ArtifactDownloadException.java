package com.gregochr.goldenhour.client;

/**
 * An artifact download that must be refused on its own merits (an unusable or non-https location, or a body larger
 * than the cap), as opposed to a transport failure. The message never contains the URL, which is a signature.
 */
public class ArtifactDownloadException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the download was refused, without the URL
     */
    public ArtifactDownloadException(String message) {
        super(message);
    }
}
