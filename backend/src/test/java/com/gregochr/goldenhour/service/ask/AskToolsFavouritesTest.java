package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.FavouriteLocations;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskTools.CompanionKind;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Favourites in {@code rank_spots} (find-and-favourites-plan D4, D4b, F4b): the tie-break that never beats a higher
 * rating, pick or tide fit; the favourite-and-favourite-blind pair; the {@code favourites} scope and its empty note;
 * what makes an answer personal; and that an unchanged order keeps the shared result's bytes.
 */
class AskToolsFavouritesTest {

    private static final String TODAY_SUNSET = "2026-10-05_sunset";
    private static final long AMBLE = 1L;
    private static final long BAMBURGH = 2L;
    private static final long MARYS = 3L;
    private static final long ZETA = 4L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private static AskUserContext reader(UserRole role, Set<Long> favouriteIds, Map<Long, String> areas) {
        return new AskUserContext(7L, role, false, null, new FavouriteLocations(favouriteIds, areas));
    }

    private static AskUserContext reader(Long... favouriteIds) {
        return reader(UserRole.PRO_USER, Set.of(favouriteIds), Map.of());
    }

    private AskTools tools(AskSnapshot snapshot, AskUserContext user) {
        return new AskTools(snapshot, user, AskScope.ALL, driveTimes, mapper);
    }

    private static AskSnapshot snapshotOf(BriefingWindow.Pick pick, BriefingSlot... slots) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, pick,
                AskFixtures.region("Northumberland", true, slots))), List.of()));
    }

    private static AskSnapshot snapshotOf(BriefingSlot... slots) {
        return snapshotOf(null, slots);
    }

    private static RankSpotsArgs plain(Integer limit) {
        return new RankSpotsArgs(null, null, null, null, null, null, limit);
    }

    private static RankSpotsArgs favourites(Integer limit) {
        return new RankSpotsArgs(null, null, null, null, null, null, limit, null, true);
    }

    private static List<Long> ids(AskToolResult<RankSpotsResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload().spots().stream().map(SpotInfo::locationId).toList();
    }

    // -- the tie-break (D4) -------------------------------------------------------------------

    @Test
    @DisplayName("equal rating, pick and tide: the second place alphabetically, if starred, ranks first")
    void aStarBreaksAnExactTie() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4), AskFixtures.slot(BAMBURGH,
                "Bamburgh", 4));

        assertThat(ids(tools(snapshot, reader(BAMBURGH)).rankSpots(plain(null)))).containsExactly(BAMBURGH, AMBLE);
        assertThat(ids(tools(snapshot, reader()).rankSpots(plain(null)))).containsExactly(AMBLE, BAMBURGH);
        assertThat(ids(tools(snapshot, AskUserContext.userLess()).rankSpots(plain(null))))
                .containsExactly(AMBLE, BAMBURGH);
    }

    @Test
    @DisplayName("a starred 3★ never ranks above an unstarred 4★")
    void aStarNeverBeatsAHigherRating() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 3), AskFixtures.slot(BAMBURGH,
                "Bamburgh", 4));

        assertThat(ids(tools(snapshot, reader(AMBLE)).rankSpots(plain(null)))).containsExactly(BAMBURGH, AMBLE);
    }

    @Test
    @DisplayName("a starred place that is not the window's BEST BET never beats the BEST BET at the same rating")
    void aStarNeverBeatsTheBestBet() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", "Zeta", ZETA);
        AskSnapshot snapshot = snapshotOf(best, AskFixtures.slot(AMBLE, "Amble", 4),
                AskFixtures.slot(ZETA, "Zeta", 4));

        assertThat(ids(tools(snapshot, reader(AMBLE)).rankSpots(plain(null)))).containsExactly(ZETA, AMBLE);
    }

    @Test
    @DisplayName("a starred place never beats a better tide fit at the same rating")
    void aStarNeverBeatsABetterTideFit() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.coastal(AMBLE, "Amble", 4, "HIGH", false),
                AskFixtures.coastal(BAMBURGH, "Bamburgh", 4, "HIGH", true));

        assertThat(ids(tools(snapshot, reader(AMBLE)).rankSpots(plain(null)))).containsExactly(BAMBURGH, AMBLE);
    }

    // -- the pair (D4b) -----------------------------------------------------------------------

    @Test
    @DisplayName("Mary's (starred, 4★) ties Bamburgh (4★): the lead is Mary's and the companion is Bamburgh, TIED")
    void aTieGivesALeadAndATiedCompanion() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(BAMBURGH, "Bamburgh", 4),
                AskFixtures.slot(MARYS, "Mary's Lighthouse", 4));
        AskTools tools = tools(snapshot, reader(MARYS));

        RankSpotsResult result = tools.rankSpots(plain(1)).payload();

        assertThat(result.spots()).extracting(SpotInfo::locationId).containsExactly(MARYS);
        assertThat(result.lead().locationId()).isEqualTo(MARYS);
        assertThat(result.lead().favourite()).isTrue();
        assertThat(result.companion().locationId()).isEqualTo(BAMBURGH);
        assertThat(result.companion().favourite()).isNull();
        assertThat(result.companionKind()).isEqualTo(CompanionKind.TIED);
        assertThat(tools.personal()).as("the star changed the list, so the answer is the reader's own").isTrue();
        assertThat(tools.evidence().offers(new AskEvidence.Pair(BAMBURGH, TODAY_SUNSET)))
                .as("the companion is evidence the answer may pick").isTrue();
        assertThat(tools.evidence().offers(new AskEvidence.Pair(MARYS, TODAY_SUNSET))).isTrue();
    }

    @Test
    @DisplayName("the tie is detected over the whole matching set when the limit would hide it")
    void aTieBeyondTheLimitIsStillSeen() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4), AskFixtures.slot(BAMBURGH,
                "Bamburgh", 4), AskFixtures.slot(MARYS, "Mary's Lighthouse", 4));

        RankSpotsResult result = tools(snapshot, reader(MARYS)).rankSpots(plain(2)).payload();

        assertThat(result.spots()).extracting(SpotInfo::locationId).containsExactly(MARYS, AMBLE);
        assertThat(result.companion().locationId()).isEqualTo(AMBLE);
        assertThat(result.companionKind()).isEqualTo(CompanionKind.TIED);
    }

    @Test
    @DisplayName("Bamburgh 5★ and a starred Mary's 4★, favourites asked: lead Bamburgh, companion Mary's, "
            + "YOUR_FAVOURITE, and the list holds the favourites only")
    void aLowerRatedFavouriteIsTheCompanion() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(BAMBURGH, "Bamburgh", 5),
                AskFixtures.slot(MARYS, "Mary's Lighthouse", 4));
        AskTools tools = tools(snapshot, reader(MARYS));

        RankSpotsResult result = tools.rankSpots(favourites(null)).payload();

        assertThat(result.spots()).extracting(SpotInfo::locationId).containsExactly(MARYS);
        assertThat(result.lead().locationId()).isEqualTo(BAMBURGH);
        assertThat(result.lead().favourite()).isNull();
        assertThat(result.companion().locationId()).isEqualTo(MARYS);
        assertThat(result.companion().favourite()).isTrue();
        assertThat(result.companionKind()).isEqualTo(CompanionKind.YOUR_FAVOURITE);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("a favourite that is the clear best is one answer: a lead and no companion")
    void aClearBestFavouriteHasNoCompanion() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(BAMBURGH, "Bamburgh", 4),
                AskFixtures.slot(MARYS, "Mary's Lighthouse", 5));

        RankSpotsResult result = tools(snapshot, reader(MARYS)).rankSpots(favourites(null)).payload();

        assertThat(result.lead().locationId()).isEqualTo(MARYS);
        assertThat(result.companion()).isNull();
        assertThat(result.companionKind()).isNull();
    }

    // -- an unchanged order keeps the shared result (D4b, F4b) --------------------------------

    @Test
    @DisplayName("when the star does not change the list, the result is byte-identical to the favourite-blind one "
            + "and the answer stays shared")
    void anUnchangedHeadIsTheSharedResult() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(BAMBURGH, "Bamburgh", 5),
                AskFixtures.slot(MARYS, "Mary's Lighthouse", 4));
        AskTools starred = tools(snapshot, reader(MARYS));
        AskTools blind = tools(snapshot, reader());

        AskToolResult<RankSpotsResult> mine = starred.rankSpots(plain(null));
        AskToolResult<RankSpotsResult> shared = blind.rankSpots(plain(null));

        assertThat(mine.content()).isEqualTo(shared.content());
        assertThat(mine.content()).doesNotContain("favourite").doesNotContain("lead").doesNotContain("companion");
        assertThat(starred.personal()).isFalse();
    }

    // -- the favourites scope -----------------------------------------------------------------

    @Test
    @DisplayName("favourites: true keeps the starred places and the members of starred areas, and names the area")
    void theScopeIsStarredPlacesAndAreaMembers() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4), AskFixtures.slot(BAMBURGH,
                "Bamburgh", 4), AskFixtures.slot(MARYS, "Mary's Lighthouse", 3), AskFixtures.slot(ZETA, "Zeta", 5));
        AskUserContext user = reader(UserRole.PRO_USER, Set.of(AMBLE, MARYS), Map.of(MARYS, "Hadrian's Wall"));

        RankSpotsResult result = tools(snapshot, user).rankSpots(favourites(null)).payload();

        assertThat(result.spots()).extracting(SpotInfo::locationId).containsExactly(AMBLE, MARYS);
        assertThat(result.spots().get(0).favourite()).isTrue();
        assertThat(result.spots().get(0).favouriteArea()).isNull();
        assertThat(result.spots().get(1).favouriteArea()).isEqualTo("Hadrian's Wall");
    }

    @Test
    @DisplayName("favourites: true with nothing starred is an empty result with a note, not an error, and personal")
    void nothingStarredIsAnEmptyResultWithANote() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4));
        AskTools tools = tools(snapshot, reader());

        AskToolResult<RankSpotsResult> result = tools.rankSpots(favourites(null));

        assertThat(result.error()).isFalse();
        assertThat(result.payload().spots()).isEmpty();
        assertThat(result.payload().note()).isEqualTo(AskTools.NO_FAVOURITES_NOTE);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("a user-less (Ready) conversation is refused favourites: true, so Ready never sees them")
    void aReadyConversationCannotAskForFavourites() {
        AskToolResult<RankSpotsResult> result = tools(snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4)),
                AskUserContext.userLess()).rankSpots(favourites(null));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("favourites is not available");
    }

    @Test
    @DisplayName("a Lite reader never has favourites: no tie-break, and favourites: true finds nothing starred")
    void aLiteReaderGetsNone() {
        AskSnapshot snapshot = snapshotOf(AskFixtures.slot(AMBLE, "Amble", 4), AskFixtures.slot(BAMBURGH,
                "Bamburgh", 4));
        AskUserContext lite = reader(UserRole.LITE_USER, Set.of(BAMBURGH), Map.of());

        assertThat(lite.hasFavourites()).isFalse();
        assertThat(lite.favouritesFingerprint()).isNull();
        assertThat(ids(tools(snapshot, lite).rankSpots(plain(null)))).containsExactly(AMBLE, BAMBURGH);
        assertThat(tools(snapshot, lite).rankSpots(favourites(null)).payload().note())
                .isEqualTo(AskTools.NO_FAVOURITES_NOTE);
    }

    @Test
    @DisplayName("the favourites fingerprint is the same for the same set whatever its order, and differs by set")
    void theFingerprintIsStableAndSetSensitive() {
        AskUserContext one = reader(UserRole.PRO_USER, Set.of(1L, 2L, 3L), Map.of());
        AskUserContext sameSet = reader(UserRole.PRO_USER, Set.of(3L, 1L, 2L), Map.of());
        AskUserContext other = reader(UserRole.PRO_USER, Set.of(1L, 2L), Map.of());

        assertThat(one.favouritesFingerprint()).isEqualTo(sameSet.favouritesFingerprint()).isNotNull();
        assertThat(other.favouritesFingerprint()).isNotEqualTo(one.favouritesFingerprint());
        assertThat(FavouriteLocations.NONE.isEmpty()).isTrue();
        assertThat(FavouriteLocations.NONE.fingerprint()).isNull();
        assertThat(new FavouriteLocations(null, null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a place's starred area is named only for a reader who has favourites")
    void anAreaNameNeedsFavourites() {
        AskUserContext user = reader(UserRole.PRO_USER, Set.of(MARYS), Map.of(MARYS, "Hadrian's Wall"));

        assertThat(user.favouriteAreaOf(MARYS)).isEqualTo("Hadrian's Wall");
        assertThat(user.favouriteAreaOf(AMBLE)).isNull();
        assertThat(AskUserContext.userLess().favouriteAreaOf(MARYS)).isNull();
        assertThat(user.isFavourite(AMBLE)).isFalse();
    }
}
