package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The single {@code sub_region_policy} row (V192, sub-regions-plan D5): the admin-set limit on how many
 * sub-regions a Lite reader may pick. The id is always {@link #ONLY_ID} (the table's CHECK).
 *
 * <p>The columns are {@code SMALLINT}, so the Java types are {@code Short}/{@code short}: Hibernate's
 * {@code ddl-auto: validate} rejects an {@code int} against a {@code SMALLINT}.
 */
@Entity
@Table(name = "sub_region_policy")
@Getter
@Setter
@NoArgsConstructor
public class SubRegionPolicyEntity {

    /** The only legal id. */
    public static final short ONLY_ID = 1;

    /** Primary key; always {@link #ONLY_ID}. */
    @Id
    private Short id = ONLY_ID;

    /** The most sub-regions a Lite reader may hold (1..24). */
    @Column(name = "lite_limit", nullable = false)
    private short liteLimit;

    /** When the limit was last saved (UTC wall clock). */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Who saved it; null never, except a hand-edited row. */
    @Column(name = "updated_by", length = 50)
    private String updatedBy;
}
