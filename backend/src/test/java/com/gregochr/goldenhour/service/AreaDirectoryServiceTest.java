package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AreaDirectoryService} over a real (H2) schema: the member mapping for both kinds, the enabled-only
 * rule, the empty-area rule, and, above all, that the number of SQL statements does not grow with the number
 * of areas (no N+1 on the lazy alias and member collections).
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({AreaDirectoryService.class, SubRegionMembershipService.class})
class AreaDirectoryServiceTest {

    @Autowired
    private AreaDirectoryService service;

    @Autowired
    private TestEntityManager entityManager;

    private RegionEntity region;

    @BeforeEach
    void setUp() {
        region = entityManager.persist(RegionEntity.builder().name("North East").enabled(true)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0)).build());
    }

    private Long location(String name, boolean enabled) {
        return entityManager.persist(LocationEntity.builder().name(name).lat(55.0).lon(-2.0).enabled(enabled)
                .region(region).createdAt(LocalDateTime.of(2026, 3, 1, 12, 0)).build()).getId();
    }

    private Long namedArea(String name, Set<String> aliases, Set<Long> members) {
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setAliases(aliases);
        area.setLocationIds(members);
        return entityManager.persist(area).getId();
    }

    private Long subRegion(String name, Long... members) {
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setKind(AreaKind.SUB_REGION);
        area.setRegion(region);
        Long id = entityManager.persist(area).getId();
        entityManager.flush();
        for (Long member : members) {
            entityManager.getEntityManager().createNativeQuery(
                    "insert into location_sub_region (location_id, area_id) values (?1, ?2)")
                    .setParameter(1, member).setParameter(2, id).executeUpdate();
        }
        return id;
    }

    private Statistics statistics() {
        return entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class)
                .getStatistics();
    }

    private List<AreaDirectoryEntry> read() {
        entityManager.flush();
        entityManager.clear();
        return service.directoryFor(null);
    }

    @Test
    @DisplayName("a named area lists its aliases and enabled members; a disabled member is left out")
    void namedArea_enabledMembersAndAliases() {
        Long a = location("Housesteads", true);
        Long b = location("Cawfields", true);
        Long off = location("Closed Crag", false);
        Long id = namedArea("Hadrian's Wall", Set.of("Roman Wall", "The Wall"), Set.of(a, b, off));

        List<AreaDirectoryEntry> directory = read();

        assertThat(directory).hasSize(1);
        AreaDirectoryEntry wall = directory.get(0);
        assertThat(wall.id()).isEqualTo(id);
        assertThat(wall.kind()).isEqualTo(AreaKind.NAMED);
        assertThat(wall.aliases()).containsExactly("The Wall", "Roman Wall");
        assertThat(wall.regionId()).isNull();
        assertThat(wall.regionName()).isNull();
        assertThat(wall.locationIds()).containsExactlyInAnyOrder(a, b).doesNotContain(off);
    }

    @Test
    @DisplayName("a sub-region's members come from location_sub_region and it carries its region")
    void subRegion_membersAndRegion() {
        Long a = location("Bamburgh", true);
        Long b = location("Craster", true);
        Long id = subRegion("Northumberland Coast", a, b);

        List<AreaDirectoryEntry> directory = read();

        assertThat(directory).hasSize(1);
        AreaDirectoryEntry coast = directory.get(0);
        assertThat(coast.id()).isEqualTo(id);
        assertThat(coast.kind()).isEqualTo(AreaKind.SUB_REGION);
        assertThat(coast.regionId()).isEqualTo(region.getId());
        assertThat(coast.regionName()).isEqualTo("North East");
        assertThat(coast.locationIds()).containsExactly(a, b);
    }

    @Test
    @DisplayName("an area with no enabled member is not offered, and the list is ordered by name")
    void emptyAreasDropped_orderedByName() {
        Long a = location("Bamburgh", true);
        Long off = location("Closed Crag", false);
        namedArea("Zeta", Set.of(), Set.of(a));
        namedArea("alpha", Set.of(), Set.of(a));
        namedArea("Dormant", Set.of(), Set.of(off));
        namedArea("Memberless", Set.of(), Set.of());

        assertThat(read()).extracting(AreaDirectoryEntry::name).containsExactly("alpha", "Zeta");
    }

    @Test
    @DisplayName("the statement count is the same for one area as for twenty (no N+1)")
    void statementCount_isConstant() {
        Long first = location("Spot 0", true);
        namedArea("Area 0", Set.of("Alias 0"), Set.of(first));
        read();
        Statistics stats = statistics();
        stats.clear();
        read();
        long one = stats.getPrepareStatementCount();

        for (int i = 1; i <= 20; i++) {
            Long member = location("Spot " + i, true);
            namedArea("Area " + i, Set.of("Alias " + i, "Other " + i), Set.of(first, member));
            subRegion("Sub " + i, member);
        }
        entityManager.flush();
        entityManager.clear();
        stats.clear();
        List<AreaDirectoryEntry> directory = service.directoryFor(null);
        long many = stats.getPrepareStatementCount();

        assertThat(directory).hasSize(41);
        assertThat(many).isEqualTo(one);
        assertThat(many).isLessThanOrEqualTo(5L);
    }
}
