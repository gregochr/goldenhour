package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.model.AreaAliasRow;
import com.gregochr.goldenhour.model.AreaDirectoryRow;
import com.gregochr.goldenhour.model.AreaKindRow;
import com.gregochr.goldenhour.model.SubRegionMemberRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link AreaEntity}.
 */
@Repository
public interface AreaRepository extends JpaRepository<AreaEntity, Long> {

    /**
     * Every area of one kind.
     *
     * @param kind the kind
     * @return the areas, in no particular order
     */
    List<AreaEntity> findByKind(AreaKind kind);

    /**
     * How many areas are of one kind.
     *
     * @param kind the kind
     * @return the count
     */
    long countByKind(AreaKind kind);

    /**
     * Every area with its region's id and name, flat: one statement, no lazy association touched.
     *
     * @return the area rows, in no particular order
     */
    @Query("select new com.gregochr.goldenhour.model.AreaDirectoryRow(a.id, a.name, a.kind, r.id, r.name) "
            + "from AreaEntity a left join a.region r")
    List<AreaDirectoryRow> findDirectoryRows();

    /**
     * Every area's other names, flat: one statement.
     *
     * @return the alias rows
     */
    @Query("select new com.gregochr.goldenhour.model.AreaAliasRow(a.id, alias) "
            + "from AreaEntity a join a.aliases alias")
    List<AreaAliasRow> findAliasRows();

    /**
     * Every named area's members ({@code area_location}), flat: one statement. A sub-region's members are not
     * here; they are {@code location_sub_region} rows.
     *
     * @return the (area id, location id) rows
     */
    @Query("select new com.gregochr.goldenhour.model.SubRegionMemberRow(a.id, loc) "
            + "from AreaEntity a join a.locationIds loc")
    List<SubRegionMemberRow> findNamedMemberRows();

    /**
     * The named members ({@code area_location}) of the given areas only, flat: one statement.
     *
     * @param areaIds the areas to read
     * @return the (area id, location id) rows
     */
    @Query("select new com.gregochr.goldenhour.model.SubRegionMemberRow(a.id, loc) "
            + "from AreaEntity a join a.locationIds loc where a.id in :areaIds")
    List<SubRegionMemberRow> findNamedMemberRowsFor(@Param("areaIds") Collection<Long> areaIds);

    /**
     * Which of the given ids are areas, with their kind: flat, no lazy association touched.
     *
     * @param areaIds the ids to look for
     * @return one row per area that exists
     */
    @Query("select new com.gregochr.goldenhour.model.AreaKindRow(a.id, a.kind) "
            + "from AreaEntity a where a.id in :areaIds")
    List<AreaKindRow> findKindRows(@Param("areaIds") Collection<Long> areaIds);
}
