package com.gregochr.goldenhour.entity;

import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link SlotAtmosphereEntity}'s {@code @Table} name and its named unique constraint and
 * index to the {@code slot_atmosphere} spelling V159 renamed them to, so a later refactor cannot
 * silently drift the entity apart from the migration that created the table it maps to — exactly
 * the kind of split this class's own {@code SurvivorAtmosphereEntity} history (V159, 2026-09-30)
 * shows can otherwise go unnoticed until the schema and the code disagree.
 */
class SlotAtmosphereEntityTest {

    @Test
    @DisplayName("@Table name is slot_atmosphere, not the pre-V159 survivor_atmosphere")
    void tableName_isSlotAtmosphere() {
        Table table = SlotAtmosphereEntity.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("slot_atmosphere");
    }

    @Test
    @DisplayName("the unique constraint name matches V159's uq_slot_atmosphere")
    void uniqueConstraintName_matchesMigration() {
        Table table = SlotAtmosphereEntity.class.getAnnotation(Table.class);

        assertThat(table.uniqueConstraints()).hasSize(1);
        UniqueConstraint uniqueConstraint = table.uniqueConstraints()[0];
        assertThat(uniqueConstraint.name()).isEqualTo("uq_slot_atmosphere");
        assertThat(uniqueConstraint.columnNames())
                .containsExactly("location_id", "evaluation_date", "event_type");
    }

    @Test
    @DisplayName("the index name matches V159's idx_slot_atmosphere_date")
    void indexName_matchesMigration() {
        Table table = SlotAtmosphereEntity.class.getAnnotation(Table.class);

        assertThat(table.indexes()).hasSize(1);
        Index index = table.indexes()[0];
        assertThat(index.name()).isEqualTo("idx_slot_atmosphere_date");
        assertThat(index.columnList()).isEqualTo("evaluation_date");
    }
}
