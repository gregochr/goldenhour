package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the insert-only nature of {@code weather_warning_version}: the repository can read and insert and nothing
 * else, and no entity column is updatable.
 */
class WeatherWarningVersionRepositoryTest {

    private static final List<String> ALLOWED_PREFIXES = List.of("find", "exists", "count", "save");

    @Test
    @DisplayName("the repository declares only save and read methods: no delete, no update, no bulk write")
    void declaresNoDeleteOrUpdate() {
        List<String> names = Arrays.stream(WeatherWarningVersionRepository.class.getMethods())
                .filter(m -> m.getDeclaringClass() == WeatherWarningVersionRepository.class)
                .map(Method::getName)
                .toList();

        assertThat(names).isNotEmpty();
        assertThat(names).allSatisfy(name ->
                assertThat(ALLOWED_PREFIXES.stream().anyMatch(name::startsWith)).as(name).isTrue());
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("delete") || n.toLowerCase().contains("update")
                || n.toLowerCase().contains("remove"));
    }

    @Test
    @DisplayName("the repository is not a JpaRepository, so it inherits no deleteAll, deleteById or saveAll")
    void isNotJpaRepository() {
        assertThat(JpaRepository.class.isAssignableFrom(WeatherWarningVersionRepository.class)).isFalse();
        assertThat(Arrays.stream(WeatherWarningVersionRepository.class.getMethods())
                .map(Method::getName)).doesNotContain("deleteAll", "deleteById", "delete", "saveAll", "flush");
    }

    @Test
    @DisplayName("every persistent column of the entity is updatable = false")
    void everyColumnNotUpdatable() {
        List<Field> columns = Arrays.stream(WeatherWarningVersionEntity.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .toList();

        assertThat(columns).isNotEmpty();
        assertThat(columns).allSatisfy(f -> {
            Column column = f.getAnnotation(Column.class);
            assertThat(column).as(f.getName() + " has @Column").isNotNull();
            assertThat(column.updatable()).as(f.getName()).isFalse();
        });
    }
}
