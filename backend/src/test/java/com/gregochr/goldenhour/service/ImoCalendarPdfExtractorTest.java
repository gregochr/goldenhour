package com.gregochr.goldenhour.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PDFBox wrapper's bounds, with PDFs generated in the test: a readable one, an encrypted one, a corrupt one and
 * one with more than the page limit.
 */
class ImoCalendarPdfExtractorTest {

    private static byte[] pdf(int pages, String password) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Page " + (i + 1) + " Leonids (013 LEO)");
                    content.endText();
                }
            }
            if (password != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", password,
                        new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("a readable PDF's text comes back with newline-separated lines")
    void readable() throws IOException {
        String text = ImoCalendarPdfExtractor.extract(pdf(2, null));

        assertThat(text).contains("Page 1 Leonids (013 LEO)").contains("Page 2 Leonids (013 LEO)");
        assertThat(text).doesNotContain("\r");
    }

    @Test
    @DisplayName("exactly the page limit is read; one more is refused before any text is extracted")
    void pageLimit() throws IOException {
        assertThat(ImoCalendarPdfExtractor.extract(pdf(ImoCalendarPdfExtractor.MAX_PAGES, null)))
                .contains("Page 60");

        assertThatThrownBy(() -> ImoCalendarPdfExtractor.extract(pdf(ImoCalendarPdfExtractor.MAX_PAGES + 1, null)))
                .isInstanceOf(IOException.class).hasMessageContaining("61 pages");
    }

    @Test
    @DisplayName("a PDF encrypted with a user password is an IOException")
    void encrypted() throws IOException {
        byte[] locked = pdf(1, "secret");

        assertThatThrownBy(() -> ImoCalendarPdfExtractor.extract(locked)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a corrupt PDF, and bytes that are not a PDF at all, are IOExceptions")
    void corrupt() throws IOException {
        byte[] good = pdf(1, null);
        byte[] truncated = java.util.Arrays.copyOf(good, good.length / 3);
        byte[] junk = "%PDF-1.7 this is not a document".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> ImoCalendarPdfExtractor.extract(truncated)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ImoCalendarPdfExtractor.extract(junk)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an unchecked exception from PDFBox is reported as an IOException too")
    void uncheckedBecomesIoException() {
        assertThatThrownBy(() -> ImoCalendarPdfExtractor.extract(new byte[0], pdf -> {
            throw new IllegalStateException("pdfbox blew up");
        })).isInstanceOf(IOException.class).hasMessageContaining("pdfbox blew up")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the fixtures are extracted once and are plausible calendars")
    void fixturesExtracted() {
        assertThat(ImoCalendarFixtures.TEXT_2026).contains("(013 LEO)");
        assertThat(ImoCalendarFixtures.TEXT_2027).contains("(013 LEO)");
    }
}
