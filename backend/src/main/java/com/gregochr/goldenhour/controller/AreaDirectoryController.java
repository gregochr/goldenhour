package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.AreaOutlookEntry;
import com.gregochr.goldenhour.service.AreaDirectoryService;
import com.gregochr.goldenhour.service.AreaOutlookService;
import com.gregochr.goldenhour.service.LitePickShaper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The reader-facing area reads behind Find: the directory of areas and each reader's outlook per area.
 *
 * <p>Distinct from {@link AreaController}, which is the ADMIN-only maintenance API and is untouched. Both
 * reads are for any signed-in role (Find is not a Pro feature); the Spring Security chain has no
 * {@code /api/areas} rule beyond {@code /api/**} authenticated. No logic lives here.
 */
@RestController
@RequestMapping("/api/areas")
public class AreaDirectoryController {

    private final AreaDirectoryService directoryService;
    private final AreaOutlookService outlookService;
    private final LitePickShaper litePickShaper;

    /**
     * Constructs the controller.
     *
     * @param directoryService the directory of areas
     * @param outlookService   the per-reader figure per area and window
     * @param litePickShaper   decides whether the outlook is shaped for the caller (and so served no-store)
     */
    public AreaDirectoryController(AreaDirectoryService directoryService, AreaOutlookService outlookService,
            LitePickShaper litePickShaper) {
        this.directoryService = directoryService;
        this.outlookService = outlookService;
        this.litePickShaper = litePickShaper;
    }

    /**
     * Lists every area with its other names and enabled members. Role-free and identical for every reader
     * today, so it is ETag-shared ({@code HttpCachingConfig}).
     *
     * @param auth the caller's authentication
     * @return the directory, by name
     */
    @GetMapping("/directory")
    public List<AreaDirectoryEntry> getDirectory(Authentication auth) {
        return directoryService.directoryFor(auth);
    }

    /**
     * The caller's own outlook per area and window: counts and a top rating, gated members counted and never
     * rated. Personal (it reveals which places the reader's picks cover), so never ETag-shared, and
     * {@code no-store} when it was shaped for a Lite reader.
     *
     * @param auth the caller's authentication
     * @return the areas that have at least one window with a rated or gated member
     */
    @GetMapping("/outlook")
    public ResponseEntity<List<AreaOutlookEntry>> getOutlook(Authentication auth) {
        return LitePickShaper.respond(outlookService.outlookFor(auth), litePickShaper.scopeFor(auth));
    }
}
