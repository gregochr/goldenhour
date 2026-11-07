package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AppUserSubRegionRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives the <b>E2E smoke suite's</b> local backend a {@code lite} and a {@code pro} reader beside the seeded
 * admin, and the sub-regions the Lite reader's picks need, so the role-gating specs run against production's
 * shape ({@code docs/engineering/engineering-gaps-plan.md}, Phase 3a.2).
 *
 * <p>Called only by {@link E2eLocalFixtureSeeder}, after its locations are saved, so it inherits that bean's
 * three independent refusals (the {@code local & !prod} profile, the {@code photocast.e2e.seed-fixture} flag and
 * the H2 and empty-database checks). It has no entry point of its own.
 *
 * <p><b>The users</b> mirror {@code LocalAdminSeeder}'s admin: an encoded password, {@code enabled}, a
 * {@code createdAt}, and the columns that are insertable but {@code updatable = false} set on the builder at
 * insert: {@code passwordChangeRequired = false} (no forced password change at sign-in) and
 * {@code postcodePromptDismissedAt} (the one-time "set your home postcode" prompt would hold the whole app inert
 * on a first sign-in). Nothing else gates a first sign-in: terms are accepted at registration only, and no email
 * verification applies to a seeded account.
 *
 * <p><b>The sub-regions</b>: one per region, holding all six of that region's places. {@code lite} picks two of
 * the three (the fixture's limit is the policy row's default of three), so it holds <em>some but not all</em>:
 * the third region's places are gated. It has picks, so its standing is {@code OPEN} (no grace stamp, no change
 * lock), which is the stable "picks set" state: the first-choice sign-in prompt, which shows only at
 * {@code FIRST_CHOICE}, never appears. {@code pro} and {@code admin} are unrestricted whatever they pick.
 */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.e2e.seed-fixture", havingValue = "true")
public class E2eRoleUsers {

    /** The Lite reader's username. */
    public static final String LITE_USERNAME = "lite";

    /** The Lite reader's password; meets {@code validatePasswordComplexity}. */
    public static final String LITE_PASSWORD = "E2eLite#Pass1";

    /** The Pro reader's username. */
    public static final String PRO_USERNAME = "pro";

    /** The Pro reader's password; meets {@code validatePasswordComplexity}. */
    public static final String PRO_PASSWORD = "E2ePro#Pass1";

    /** The sub-region each fixture region holds, keyed by region name. */
    static final Map<String, String> SUB_REGIONS = Map.of(
            "Northumberland Coast", "Northumberland Shore",
            "Lake District", "Lakeland Fells",
            "Yorkshire Dales", "Dales Limestone");

    /** The regions whose sub-region {@code lite} picks: two of the three, so the third is gated. */
    static final List<String> LITE_PICKED_REGIONS = List.of("Northumberland Coast", "Lake District");

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AreaRepository areaRepository;
    private final SubRegionMembershipService membershipService;
    private final AppUserSubRegionRepository pickRepository;
    private final Clock clock;

    /**
     * Creates the writer.
     *
     * @param userRepository    accounts
     * @param passwordEncoder   hashes the two fixed passwords
     * @param areaRepository    the sub-regions
     * @param membershipService the only writer of sub-region membership
     * @param pickRepository    a reader's sub-region picks
     * @param clock             the application clock
     */
    public E2eRoleUsers(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            AreaRepository areaRepository, SubRegionMembershipService membershipService,
            AppUserSubRegionRepository pickRepository, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.areaRepository = areaRepository;
        this.membershipService = membershipService;
        this.pickRepository = pickRepository;
        this.clock = clock;
    }

    /**
     * Writes the sub-regions and their membership, then the {@code lite} and {@code pro} users, then
     * {@code lite}'s picks.
     *
     * @param locations the saved fixture places (each with its region and id)
     * @return a one-line summary for the log
     */
    public String seed(List<LocationEntity> locations) {
        Map<String, AreaEntity> areas = saveSubRegions(locations);
        for (LocationEntity location : locations) {
            membershipService.assign(location, areas.get(location.getRegion().getName()).getId());
        }
        AppUserEntity lite = userRepository.save(user(LITE_USERNAME, LITE_PASSWORD, UserRole.LITE_USER));
        userRepository.save(user(PRO_USERNAME, PRO_PASSWORD, UserRole.PRO_USER));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String region : LITE_PICKED_REGIONS) {
            pickRepository.insertPick(lite.getId(), areas.get(region).getId(), now);
        }
        return "2 role users, " + areas.size() + " sub-regions, " + LITE_PICKED_REGIONS.size() + " lite picks";
    }

    /**
     * The self-check: both users exist with their roles, and {@code lite} holds exactly its picks, some but
     * not all of the sub-regions.
     *
     * @throws IllegalStateException when a user is missing or has the wrong role, or the picks do not read back
     */
    public void verify() {
        AppUserEntity lite = require(LITE_USERNAME, UserRole.LITE_USER);
        require(PRO_USERNAME, UserRole.PRO_USER);
        List<Long> picked = pickRepository.findAreaIdsByUserId(lite.getId());
        long subRegions = areaRepository.countByKind(AreaKind.SUB_REGION);
        if (picked.size() != LITE_PICKED_REGIONS.size() || picked.size() >= subRegions) {
            throw new IllegalStateException("Self-check: user '" + LITE_USERNAME + "' reads back " + picked.size()
                    + " sub-region picks of " + subRegions + " sub-regions, expected "
                    + LITE_PICKED_REGIONS.size() + " (some but not all)");
        }
    }

    private AppUserEntity require(String username, UserRole role) {
        AppUserEntity found = userRepository.findByUsername(username).orElseThrow(() ->
                new IllegalStateException("Self-check: user '" + username + "' does not exist"));
        if (found.getRole() != role) {
            throw new IllegalStateException("Self-check: user '" + username + "' has role " + found.getRole()
                    + ", expected " + role);
        }
        return found;
    }

    private Map<String, AreaEntity> saveSubRegions(List<LocationEntity> locations) {
        Map<String, RegionEntity> regions = new LinkedHashMap<>();
        for (LocationEntity location : locations) {
            regions.putIfAbsent(location.getRegion().getName(), location.getRegion());
        }
        Map<String, AreaEntity> areas = new LinkedHashMap<>();
        regions.forEach((name, region) -> {
            AreaEntity area = new AreaEntity();
            area.setName(SUB_REGIONS.get(name));
            area.setKind(AreaKind.SUB_REGION);
            area.setRegion(region);
            areas.put(name, areaRepository.save(area));
        });
        return areas;
    }

    private AppUserEntity user(String username, String password, UserRole role) {
        Instant now = clock.instant();
        return AppUserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(role)
                .enabled(true)
                .passwordChangeRequired(false)
                .postcodePromptDismissedAt(now)
                .createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .build();
    }
}
