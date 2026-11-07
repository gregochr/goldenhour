package com.gregochr.goldenhour.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * Extracts the text of an IMO calendar PDF with Apache PDFBox, with every load bounded: the bytes are already
 * capped by the client, the stream cache is held in memory at 64 MB, more than {@value #MAX_PAGES} pages is
 * refused before any text is read, and extraction stops at page {@value #MAX_PAGES}.
 *
 * <p>Every failure, an encrypted or corrupt file included, surfaces as an {@link IOException}, so the caller has
 * one case to treat as "this PDF is not usable".
 */
final class ImoCalendarPdfExtractor {

    /** The most pages read. The real calendars run to about 35. */
    static final int MAX_PAGES = 60;

    private static final long CACHE_BYTES = 64L * 1024 * 1024;

    private ImoCalendarPdfExtractor() {
    }

    /**
     * Extracts the text of a PDF, lines separated by {@code \n}, in content-stream order.
     *
     * @param pdf the PDF bytes
     * @return the text
     * @throws IOException if the PDF is encrypted, corrupt, too long, or otherwise cannot be read
     */
    static String extract(byte[] pdf) throws IOException {
        return extract(pdf, ImoCalendarPdfExtractor::open);
    }

    /** Opens a PDF; a seam so the "PDFBox threw something unchecked" branch can be proven. */
    @FunctionalInterface
    interface Opener {

        /**
         * Opens the PDF.
         *
         * @param pdf the bytes
         * @return the open document
         * @throws IOException if it cannot be read
         */
        PDDocument open(byte[] pdf) throws IOException;
    }

    static String extract(byte[] pdf, Opener opener) throws IOException {
        try (PDDocument document = opener.open(pdf)) {
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new IOException("PDF has " + document.getNumberOfPages() + " pages; the limit is " + MAX_PAGES);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setSortByPosition(false);
            stripper.setStartPage(1);
            stripper.setEndPage(MAX_PAGES);
            return stripper.getText(document);
        } catch (RuntimeException e) {
            throw new IOException("PDF could not be read: " + e, e);
        }
    }

    private static PDDocument open(byte[] pdf) throws IOException {
        return Loader.loadPDF(pdf, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(CACHE_BYTES).streamCache);
    }
}
