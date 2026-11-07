package com.gregochr.goldenhour.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The two committed IMO calendar PDFs (src/test/resources/imo), extracted once through the real PDFBox extractor
 * and held in statics so every test class that needs the text pays for it once per JVM.
 */
final class ImoCalendarFixtures {

    /** The text of cal2026.pdf. */
    static final String TEXT_2026 = extract("/imo/cal2026.pdf");

    /** The text of cal2027.pdf. */
    static final String TEXT_2027 = extract("/imo/cal2027.pdf");

    private ImoCalendarFixtures() {
    }

    /**
     * The raw bytes of a committed fixture.
     *
     * @param resource the classpath resource, e.g. {@code /imo/cal2026.pdf}
     * @return the bytes
     */
    static byte[] bytes(String resource) {
        try (InputStream in = ImoCalendarFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extract(String resource) {
        try {
            return ImoCalendarPdfExtractor.extract(bytes(resource));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
