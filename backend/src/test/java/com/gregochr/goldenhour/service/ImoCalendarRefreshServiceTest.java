package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.ImoCalendarClient;
import com.gregochr.goldenhour.client.ImoCalendarException;
import com.gregochr.goldenhour.entity.ImoCalendarFetchEntity;
import com.gregochr.goldenhour.entity.ImoCalendarFetchOutcome;
import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.repository.ImoCalendarFetchRepository;
import com.gregochr.goldenhour.repository.MeteorShowerPeakRepository;
import com.gregochr.goldenhour.service.ImoCalendarParser.ParsedPeak;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImoCalendarRefreshService} with a mocked client, repositories and transaction manager and an injected
 * clock. Calendars are small PDFs generated with PDFBox in the test (six major rows), so each run costs
 * milliseconds; one test uses the committed 2026 calendar against the V222 seed.
 */
class ImoCalendarRefreshServiceTest {

    private static final String BASE = "https://www.imo.net/files/meteor-shower/";
    private static final String URL_2026 = BASE + "cal2026.pdf";
    private static final String URL_2027 = BASE + "cal2027.pdf";
    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private static final EnumSet<ImoCalendarFetchOutcome> PARSED = EnumSet.of(
            ImoCalendarFetchOutcome.INVALID, ImoCalendarFetchOutcome.STORED, ImoCalendarFetchOutcome.UNCHANGED);

    private static final List<String> MAJOR_ROWS = List.of(
            "Quadrantids (010 QUA) Dec 28–Jan 12 Jan 03 283 230 +49 41 2.1 80",
            "Lyrids (006 LYR) Apr 14–Apr 30 Apr 22 32 271 +34 49 2.9 18",
            "Perseids (007 PER) Jul 17–Aug 24 Aug 12 140 48 +58 59 2.2 100",
            "Orionids (008 ORI) Oct 02–Nov 07 Oct 20 208 95 +16 66 2.5 20",
            "Leonids (013 LEO) Nov 06–Nov 30 Nov 17 235 152 +22 71 2.5 15",
            "Geminids (004 GEM) Dec 04–Dec 20 Dec 14 262 112 +33 35 2.6 150");

    private ImoCalendarClient client;
    private MeteorShowerPeakRepository peaks;
    private ImoCalendarFetchRepository fetchLog;
    private MeteorPeakCalendar peakCalendar;
    private AdminAlertService alerts;
    private DynamicSchedulerService scheduler;
    private PlatformTransactionManager txManager;
    private TransactionStatus txStatus;
    private TransactionStatus isolatedStatus;

    @BeforeEach
    void setUp() {
        client = mock(ImoCalendarClient.class);
        peaks = mock(MeteorShowerPeakRepository.class);
        fetchLog = mock(ImoCalendarFetchRepository.class);
        peakCalendar = mock(MeteorPeakCalendar.class);
        alerts = mock(AdminAlertService.class);
        scheduler = mock(DynamicSchedulerService.class);
        txManager = mock(PlatformTransactionManager.class);
        txStatus = mock(TransactionStatus.class);
        isolatedStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(argThat((TransactionDefinition d) -> d != null
                && d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED))).thenReturn(txStatus);
        when(txManager.getTransaction(argThat((TransactionDefinition d) -> d != null
                && d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW)))
                .thenReturn(isolatedStatus);
        when(client.urlFor(2026)).thenReturn(URL_2026);
        when(client.urlFor(2027)).thenReturn(URL_2027);
        when(peaks.findAll()).thenReturn(List.of());
    }

    private ImoCalendarRefreshService service(Instant now) {
        return new ImoCalendarRefreshService(client, peaks, fetchLog, peakCalendar, alerts, scheduler,
                Clock.fixed(now, ZoneOffset.UTC), txManager);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------

    private static byte[] pdf(List<String> rows) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                content.newLineAtOffset(20, 760);
                content.setLeading(12);
                for (String row : rows) {
                    content.showText(row);
                    content.newLine();
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] calendar() {
        return pdf(MAJOR_ROWS);
    }

    private static byte[] calendarWithout(String code) {
        return pdf(MAJOR_ROWS.stream().filter(r -> !r.contains("(" + codeNumber(code) + " " + code + ")")).toList());
    }

    private static String codeNumber(String code) {
        return MAJOR_ROWS.stream().filter(r -> r.contains(" " + code + ")")).findFirst().orElseThrow()
                .replaceAll(".*\\((\\d{3}) " + code + "\\).*", "$1");
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ImoCalendarClient.Fetched found(String url, byte[] bytes) {
        return new ImoCalendarClient.Fetched(url, 200, bytes);
    }

    private static ImoCalendarClient.Fetched notFound(String url) {
        return new ImoCalendarClient.Fetched(url, 404, null);
    }

    /** The stored rows a year would hold if the PDF had been applied already, with the given hash. */
    private static List<MeteorShowerPeakEntity> storedFrom(byte[] pdf, int year, String sha) {
        try {
            Map<String, ParsedPeak> parsed = ImoCalendarParser.parse(ImoCalendarPdfExtractor.extract(pdf), year);
            List<MeteorShowerPeakEntity> rows = new ArrayList<>();
            parsed.forEach((code, p) -> rows.add(new MeteorShowerPeakEntity(code, year, p.night(), p.maxUtc(),
                    p.zhr(), BASE + "cal" + year + ".pdf", sha, Instant.parse("2026-06-01T00:00:00Z"))));
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ImoCalendarFetchEntity logged(ImoCalendarFetchOutcome outcome, String sha) {
        ImoCalendarFetchEntity row = new ImoCalendarFetchEntity();
        row.setOutcome(outcome);
        row.setSha256(sha);
        return row;
    }

    private List<ImoCalendarFetchEntity> savedLog() {
        ArgumentCaptor<ImoCalendarFetchEntity> captor = ArgumentCaptor.forClass(ImoCalendarFetchEntity.class);
        verify(fetchLog, Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private List<MeteorShowerPeakEntity> savedPeaks() {
        ArgumentCaptor<MeteorShowerPeakEntity> captor = ArgumentCaptor.forClass(MeteorShowerPeakEntity.class);
        verify(peaks, Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private static SchedulerFireOutcome.Report run(ImoCalendarRefreshService service) {
        return SchedulerFireOutcome.capture(service::refresh);
    }

    // ---- first fill, unchanged, seed ----------------------------------------------------------------------

    @Test
    @DisplayName("a first fill writes both years, logs STORED with the hash, reloads once per year only after "
            + "the commit, sends no email and reports 'first fill'")
    void firstFill() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(found(URL_2027, bytes));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("2026 stored (first fill) · 2027 stored (first fill)");
        List<MeteorShowerPeakEntity> saved = savedPeaks();
        assertThat(saved).hasSize(12);
        assertThat(saved).allMatch(p -> p.getSourceSha256().equals(sha(bytes)));
        assertThat(saved).filteredOn(p -> p.getShowerCode().equals("GEM") && p.getCalendarYear() == 2027)
                .singleElement().satisfies(p -> {
                    assertThat(p.getPeakNight()).isEqualTo(LocalDate.of(2027, 12, 14));
                    assertThat(p.getZhr()).isEqualTo(150);
                    assertThat(p.getSourceUrl()).isEqualTo(URL_2027);
                    assertThat(p.getFetchedAt()).isEqualTo(NOW);
                });
        List<ImoCalendarFetchEntity> log = savedLog();
        assertThat(log).extracting(ImoCalendarFetchEntity::getOutcome)
                .containsExactly(ImoCalendarFetchOutcome.STORED, ImoCalendarFetchOutcome.STORED);
        assertThat(log.get(0).getSha256()).isEqualTo(sha(bytes));
        assertThat(log.get(0).getHttpStatus()).isEqualTo(200);
        verify(peakCalendar, times(2)).reload();
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("the upsert commits before the calendar is reloaded")
    void reloadOnlyAfterCommit() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        run(service(NOW));

        InOrder order = Mockito.inOrder(peaks, fetchLog, txManager, peakCalendar);
        order.verify(peaks, Mockito.atLeastOnce()).save(any(MeteorShowerPeakEntity.class));
        order.verify(txManager).commit(txStatus);
        order.verify(peakCalendar).reload();
    }

    @Test
    @DisplayName("a year whose stored rows already match is UNCHANGED: no write, no reload, no email")
    void unchanged() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(found(URL_2027, bytes));
        List<MeteorShowerPeakEntity> all = new ArrayList<>(storedFrom(bytes, 2026, sha(bytes)));
        all.addAll(storedFrom(bytes, 2027, sha(bytes)));
        when(peaks.findAll()).thenReturn(all);

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("2026 unchanged · 2027 unchanged");
        assertThat(savedPeaks()).isEmpty();
        assertThat(savedLog()).extracting(ImoCalendarFetchEntity::getOutcome)
                .containsExactly(ImoCalendarFetchOutcome.UNCHANGED, ImoCalendarFetchOutcome.UNCHANGED);
        verify(peakCalendar, never()).reload();
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("the first run over the V222 seed rows, with the real 2026 PDF, only fills source_sha256: UNCHANGED, "
            + "no reload, no email")
    void firstRunOverTheSeedOnlyFillsTheHash() {
        byte[] real = ImoCalendarFixtures.bytes("/imo/cal2026.pdf");
        when(client.fetch(2026)).thenReturn(found(URL_2026, real));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        List<MeteorShowerPeakEntity> seeded = new ArrayList<>();
        for (MeteorSeed.Row r : MeteorSeed.ROWS) {
            if (r.year() == 2026) {
                seeded.add(new MeteorShowerPeakEntity(r.code(), 2026, r.night(), r.maxUtc(), r.zhr(), URL_2026, null,
                        Instant.parse("2026-11-07T00:00:00Z")));
            }
        }
        when(peaks.findAll()).thenReturn(seeded);

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 unchanged · 2027 not published");
        assertThat(savedPeaks()).hasSize(12).allMatch(p -> sha(real).equals(p.getSourceSha256()));
        assertThat(savedPeaks()).extracting(MeteorShowerPeakEntity::getFetchedAt)
                .containsOnly(Instant.parse("2026-11-07T00:00:00Z"));
        assertThat(savedLog()).extracting(ImoCalendarFetchEntity::getOutcome)
                .containsExactly(ImoCalendarFetchOutcome.UNCHANGED, ImoCalendarFetchOutcome.NOT_PUBLISHED);
        verify(peakCalendar, never()).reload();
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    // ---- revisions ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("a moved night or changed rate on a stored row is written and emailed once, before and after")
    void revisionAlert() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        List<MeteorShowerPeakEntity> stored = new ArrayList<>(storedFrom(bytes, 2026, "0".repeat(64)));
        stored.stream().filter(p -> p.getShowerCode().equals("GEM")).forEach(p -> {
            p.setPeakNight(LocalDate.of(2026, 12, 13));
            p.setZhr(140);
        });
        when(peaks.findAll()).thenReturn(stored);

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 stored · 2027 not published");
        assertThat(savedPeaks().stream().filter(p -> p.getShowerCode().equals("GEM")).toList()).singleElement()
                .satisfies(p -> {
                    assertThat(p.getPeakNight()).isEqualTo(LocalDate.of(2026, 12, 14));
                    assertThat(p.getZhr()).isEqualTo(150);
                    assertThat(p.getFetchedAt()).isEqualTo(NOW);
                });
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(alerts).sendMeteorCalendarAlert(subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("PhotoCast: IMO revised its 2026 meteor calendar");
        assertThat(body.getValue()).contains("GEM: night 2026-12-13 -> 2026-12-14, ZHR 140 -> 150")
                .contains(URL_2026);
        verify(peakCalendar).reload();
    }

    @Test
    @DisplayName("a changed time of maximum alone is stored without an email; a changed hash alone just updates "
            + "the hash")
    void timeOnlyAndHashOnly() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        List<MeteorShowerPeakEntity> stored = new ArrayList<>(storedFrom(bytes, 2026, "1".repeat(64)));
        stored.stream().filter(p -> p.getShowerCode().equals("LEO"))
                .forEach(p -> p.setMaxUtc(Instant.parse("2026-11-17T23:45:00Z")));
        when(peaks.findAll()).thenReturn(stored);

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 stored · 2027 not published");
        assertThat(savedPeaks()).hasSize(6);
        assertThat(savedPeaks().stream().filter(p -> p.getShowerCode().equals("LEO")).findFirst().orElseThrow()
                .getMaxUtc()).isNull();
        assertThat(savedPeaks()).allMatch(p -> sha(bytes).equals(p.getSourceSha256()));
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("a shower missing from a year that already has rows is added without a first-fill note or email")
    void addedRowInAnExistingYear() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        List<MeteorShowerPeakEntity> stored = new ArrayList<>(storedFrom(bytes, 2026, sha(bytes)));
        stored.removeIf(p -> p.getShowerCode().equals("LYR"));
        when(peaks.findAll()).thenReturn(stored);

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 stored · 2027 not published");
        assertThat(savedPeaks()).singleElement().satisfies(p -> assertThat(p.getShowerCode()).isEqualTo("LYR"));
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    // ---- INVALID ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a calendar with a major shower missing is INVALID: nothing written, one email naming the shower")
    void invalidWritesNothing() {
        byte[] bytes = calendarWithout("GEM");
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(report.summary()).isEqualTo("2026 invalid · 2027 not published");
        assertThat(savedPeaks()).isEmpty();
        assertThat(savedLog().get(0).getOutcome()).isEqualTo(ImoCalendarFetchOutcome.INVALID);
        assertThat(savedLog().get(0).getDetail()).contains("no Table 5 row for GEM");
        assertThat(savedLog().get(0).getSha256()).isEqualTo(sha(bytes));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(alerts).sendMeteorCalendarAlert(eq("PhotoCast: the IMO 2026 meteor calendar could not be read"),
                body.capture());
        assertThat(body.getValue()).contains("no Table 5 row for GEM").contains(URL_2026);
        verify(peakCalendar, never()).reload();
    }

    @Test
    @DisplayName("INVALID is emailed once per distinct PDF: the same hash after an intervening FAILED is not "
            + "repeated, a new hash or a prior good parse is")
    void invalidDedupe() {
        byte[] bytes = calendarWithout("GEM");
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        // The query ignores FAILED and NOT_PUBLISHED rows, so the latest relevant row is the earlier INVALID.
        when(fetchLog.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(2026, PARSED))
                .thenReturn(Optional.of(logged(ImoCalendarFetchOutcome.INVALID, sha(bytes))));

        run(service(NOW));

        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
        assertThat(savedLog().get(0).getOutcome()).isEqualTo(ImoCalendarFetchOutcome.INVALID);

        when(fetchLog.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(2026, PARSED))
                .thenReturn(Optional.of(logged(ImoCalendarFetchOutcome.INVALID, "f".repeat(64))));
        run(service(NOW));
        verify(alerts, times(1)).sendMeteorCalendarAlert(any(String.class), any(String.class));

        when(fetchLog.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(2026, PARSED))
                .thenReturn(Optional.of(logged(ImoCalendarFetchOutcome.STORED, sha(bytes))));
        run(service(NOW));
        verify(alerts, times(2)).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("an unreadable PDF is INVALID, and a failed history read errs towards alerting")
    void unreadablePdf() {
        byte[] junk = "%PDF-1.7 not really".getBytes(StandardCharsets.US_ASCII);
        when(client.fetch(2026)).thenReturn(found(URL_2026, junk));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        when(fetchLog.findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(2026, PARSED))
                .thenThrow(new IllegalStateException("db down"));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 invalid · 2027 not published");
        assertThat(savedLog().get(0).getDetail()).startsWith("the PDF could not be read");
        verify(alerts).sendMeteorCalendarAlert(eq("PhotoCast: the IMO 2026 meteor calendar could not be read"),
                any(String.class));
    }

    @Test
    @DisplayName("an unchecked exception from the parser makes the year INVALID with its message, writes nothing")
    void runtimeErrorInParseIsInvalid() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        SchedulerFireOutcome.Report report;
        try (org.mockito.MockedStatic<ImoCalendarParser> parser = Mockito.mockStatic(ImoCalendarParser.class)) {
            parser.when(() -> ImoCalendarParser.parse(any(String.class), eq(2026)))
                    .thenThrow(new IllegalStateException("surprise"));
            report = run(service(NOW));
        }

        assertThat(report.summary()).isEqualTo("2026 invalid · 2027 not published");
        assertThat(savedLog().get(0).getDetail()).contains("surprise");
        assertThat(savedPeaks()).isEmpty();
    }

    // ---- transaction propagation ---------------------------------------------------------------------------

    @Test
    @DisplayName("the upsert and its STORED/UNCHANGED log row run in the default REQUIRED transaction, never "
            + "REQUIRES_NEW")
    void storePathIsRequired() {
        byte[] bytes = calendar();
        when(client.fetch(2026)).thenReturn(found(URL_2026, bytes));
        when(client.fetch(2027)).thenReturn(found(URL_2027, bytes));
        when(peaks.findAll()).thenReturn(storedFrom(bytes, 2027, sha(bytes)));

        run(service(NOW));

        // 2026 is STORED, 2027 is UNCHANGED: one REQUIRED transaction each.
        verify(txManager, times(2)).commit(txStatus);
        verify(txManager, never()).commit(isolatedStatus);
    }

    @Test
    @DisplayName("a FAILED log row is written in its own REQUIRES_NEW transaction")
    void failedLogIsRequiresNew() {
        when(client.fetch(2026)).thenThrow(new ImoCalendarException("HTTP 500", 500, null));
        when(client.fetch(2027)).thenReturn(found(URL_2027, calendar()));

        run(service(NOW));

        verify(txManager, times(1)).commit(isolatedStatus);
        verify(txManager, times(1)).commit(txStatus);
    }

    @Test
    @DisplayName("an INVALID log row is written in its own REQUIRES_NEW transaction")
    void invalidLogIsRequiresNew() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendarWithout("GEM")));
        when(client.fetch(2027)).thenReturn(found(URL_2027, calendar()));

        run(service(NOW));

        verify(txManager, times(1)).commit(isolatedStatus);
        verify(txManager, times(1)).commit(txStatus);
    }

    @Test
    @DisplayName("a NOT_PUBLISHED log row is written in its own REQUIRES_NEW transaction")
    void notPublishedLogIsRequiresNew() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        run(service(NOW));

        verify(txManager, times(1)).commit(isolatedStatus);
        verify(txManager, times(1)).commit(txStatus);
    }

    // ---- FAILED / NOT_PUBLISHED ---------------------------------------------------------------------------

    @Test
    @DisplayName("the current year's 404 is FAILED (status 404), while next year's 404 is NOT_PUBLISHED")
    void currentYear404IsFailed() {
        when(client.fetch(2026)).thenReturn(notFound(URL_2026));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.PARTIAL);
        assertThat(report.summary()).isEqualTo("2026 failed · 2027 not published");
        List<ImoCalendarFetchEntity> log = savedLog();
        assertThat(log.get(0).getOutcome()).isEqualTo(ImoCalendarFetchOutcome.FAILED);
        assertThat(log.get(0).getHttpStatus()).isEqualTo(404);
        assertThat(log.get(1).getOutcome()).isEqualTo(ImoCalendarFetchOutcome.NOT_PUBLISHED);
        assertThat(log.get(1).getHttpStatus()).isEqualTo(404);
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("FAILED is emailed on exactly the third failure in a row, with 'three runs' copy")
    void failedAlertsOnExactlyTheThird() {
        when(client.fetch(2026)).thenThrow(new ImoCalendarException("HTTP 403", 403, null));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        ImoCalendarFetchEntity f = logged(ImoCalendarFetchOutcome.FAILED, null);
        ImoCalendarFetchEntity ok = logged(ImoCalendarFetchOutcome.STORED, "a");

        for (List<ImoCalendarFetchEntity> history : List.of(List.of(f), List.of(f, f), List.of(f, f, f, f),
                List.of(f, f, f, f, f))) {
            when(fetchLog.findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(2026)).thenReturn(history);
            run(service(NOW));
        }
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));

        when(fetchLog.findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(2026)).thenReturn(List.of(f, f, f, ok));
        run(service(NOW));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(alerts).sendMeteorCalendarAlert(subject.capture(), body.capture());
        assertThat(subject.getValue()).contains("2026").contains("three runs in a row");
        assertThat(body.getValue()).contains("three runs in a row").contains(URL_2026).contains("HTTP 403");
        assertThat(savedLog().get(0).getHttpStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("when both years fail the fire is FAILED, a runtime error from the client counts as FAILED, and "
            + "a long detail is cut to 500 characters")
    void bothFail() {
        when(client.fetch(2026)).thenThrow(new IllegalStateException("x".repeat(900)));
        when(client.fetch(2027)).thenThrow(new ImoCalendarException("HTTP 500", 500, null));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("2026 failed · 2027 failed");
        assertThat(savedLog().get(0).getDetail()).hasSize(500);
        assertThat(savedLog().get(1).getHttpStatus()).isEqualTo(500);
        assertThat(savedLog().get(0).getHttpStatus()).isNull();
    }

    @Test
    @DisplayName("a failure to store the rows is FAILED, nothing is reloaded, and the log-row write failing too "
            + "does not escape")
    void storeFailure() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        when(peaks.save(any(MeteorShowerPeakEntity.class))).thenThrow(new IllegalStateException("db down"));
        when(fetchLog.findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(2026))
                .thenThrow(new IllegalStateException("db down"));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 failed · 2027 not published");
        verify(peakCalendar, never()).reload();
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("a log row that cannot be saved is swallowed")
    void logWriteFailure() {
        when(client.fetch(2026)).thenThrow(new ImoCalendarException("HTTP 500", 500, null));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        when(fetchLog.save(any(ImoCalendarFetchEntity.class))).thenThrow(new IllegalStateException("db down"));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 failed · 2027 not published");
    }

    // ---- 1 October alert ----------------------------------------------------------------------------------

    @Test
    @DisplayName("from 1 October UK time an unpublished next-year calendar is emailed once; before it, never")
    void octoberFirstAlert() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        Instant ukOct1 = Instant.parse("2026-09-30T23:00:00Z");

        // 22:30Z on 30 Sep is 23:30 UK on 30 Sep: still September.
        run(service(Instant.parse("2026-09-30T22:30:00Z")));
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
        verify(fetchLog, never()).existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                2027, ImoCalendarFetchOutcome.NOT_PUBLISHED, ukOct1);

        // 23:30Z on 30 Sep is 00:30 UK on 1 Oct.
        run(service(Instant.parse("2026-09-30T23:30:00Z")));
        verify(alerts).sendMeteorCalendarAlert(eq("PhotoCast: the IMO 2027 meteor calendar is not published yet"),
                any(String.class));

        // Already reported since 1 Oct UK: not repeated.
        when(fetchLog.existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                2027, ImoCalendarFetchOutcome.NOT_PUBLISHED, ukOct1)).thenReturn(true);
        run(service(Instant.parse("2026-11-07T10:00:00Z")));
        verify(alerts, times(1)).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    @Test
    @DisplayName("an unreadable not-published history means no alert rather than a failure")
    void octoberHistoryReadFails() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendar()));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        when(fetchLog.existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
                2027, ImoCalendarFetchOutcome.NOT_PUBLISHED, Instant.parse("2026-09-30T23:00:00Z")))
                .thenThrow(new IllegalStateException("db down"));

        SchedulerFireOutcome.Report report = run(service(Instant.parse("2026-11-07T10:00:00Z")));

        assertThat(report.summary()).isEqualTo("2026 stored (first fill) · 2027 not published");
        verify(alerts, never()).sendMeteorCalendarAlert(any(String.class), any(String.class));
    }

    // ---- guard, alerts, registration ----------------------------------------------------------------------

    @Test
    @DisplayName("a run that starts while another is in progress is SKIPPED and does no work; the guard is "
            + "released afterwards")
    void overlappingRunIsSkipped() {
        ImoCalendarRefreshService service = service(NOW);
        List<SchedulerFireOutcome.Report> inner = new ArrayList<>();
        when(client.fetch(2026)).thenAnswer(invocation -> {
            inner.add(SchedulerFireOutcome.capture(service::refresh));
            return notFound(URL_2026);
        });
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));

        run(service);

        assertThat(inner).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(SchedulerFireStatus.SKIPPED);
            assertThat(r.summary()).isEqualTo("Already running — skipped");
        });
        verify(client, times(1)).fetch(2026);

        inner.clear();
        run(service);
        verify(client, times(2)).fetch(2026);
    }

    @Test
    @DisplayName("a failing alert send never fails the refresh")
    void alertFailureIsSwallowed() {
        when(client.fetch(2026)).thenReturn(found(URL_2026, calendarWithout("GEM")));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        Mockito.doThrow(new IllegalStateException("smtp down")).when(alerts)
                .sendMeteorCalendarAlert(any(String.class), any(String.class));

        SchedulerFireOutcome.Report report = run(service(NOW));

        assertThat(report.summary()).isEqualTo("2026 invalid · 2027 not published");
    }

    @Test
    @DisplayName("an unexpected error is caught and reported FAILED, and releases the guard")
    void unexpectedErrorIsCaught() {
        when(client.urlFor(2026)).thenThrow(new IllegalStateException("bad base url"));
        ImoCalendarRefreshService service = service(NOW);

        SchedulerFireOutcome.Report report = run(service);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.FAILED);
        assertThat(report.summary()).isEqualTo("Unexpected error: IllegalStateException");

        Mockito.doReturn(URL_2026).when(client).urlFor(2026);
        when(client.fetch(2026)).thenReturn(notFound(URL_2026));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        assertThat(run(service).summary()).isEqualTo("2026 failed · 2027 not published");
    }

    @Test
    @DisplayName("the job registers itself under imo_calendar_refresh and the target runs the refresh")
    void registersItself() {
        ImoCalendarRefreshService service = service(NOW);

        service.registerJob();

        ArgumentCaptor<Runnable> target = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).registerJobTarget(eq("imo_calendar_refresh"), target.capture());
        when(client.fetch(2026)).thenReturn(notFound(URL_2026));
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        target.getValue().run();
        verify(client).fetch(2026);
    }

    @Test
    @DisplayName("the years come from the UK date, not the UTC date or a fixed year")
    void yearsFollowTheUkDate() {
        when(client.urlFor(2028)).thenReturn(BASE + "cal2028.pdf");
        when(client.fetch(2027)).thenReturn(notFound(URL_2027));
        when(client.fetch(2028)).thenReturn(notFound(BASE + "cal2028.pdf"));

        // 00:30Z on 1 Jan 2027 is 00:30 UK on 1 Jan 2027: years are 2027 and 2028.
        run(service(Instant.parse("2027-01-01T00:30:00Z")));

        verify(client).fetch(2027);
        verify(client).fetch(2028);
        verify(client, never()).fetch(2026);
    }
}
