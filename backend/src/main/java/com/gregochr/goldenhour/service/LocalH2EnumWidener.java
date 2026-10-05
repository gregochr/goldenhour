package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.RunType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps an <em>existing</em> local H2 database working when an enum gains a value, under the
 * {@code local} profile only.
 *
 * <p><b>The problem (reproduced, {@code LocalH2EnumOldSchemaReproductionTest}).</b> Hibernate maps an
 * {@code @Enumerated(STRING)} column to a native H2 {@code ENUM('A','B',...)} fixed when the table
 * is created, and the local profile's {@code ddl-auto: update} never alters a column's type. So a
 * developer's existing {@code backend/data/goldenhour.mv.db} — and every dev database before this —
 * refuses a row carrying a value added after the file was made ({@code RunType.ASK},
 * {@code ASK_READY}: <em>"Value not permitted for column"</em>) until the file is deleted. A fresh
 * file and every test are fine, and so is production: Postgres holds {@code job_run.run_type} as a
 * plain {@code VARCHAR(20)} with no check constraint (V29; no later migration touches it).
 *
 * <p><b>The fix.</b> After every bean exists — so after Hibernate's schema update, and before the
 * application is ready and anything can write an {@code ASK} run — this widens each registered
 * column to {@code ENUM(<what it holds now> + <what the Java enum declares>)} with one
 * {@code ALTER TABLE ... SET DATA TYPE}. That keeps every stored value (H2 converts by label,
 * checked) and the {@code NOT NULL} constraint, never drops a value the file already allows (a
 * retired constant a row may still hold stays valid), and does nothing at all when the column is
 * already wide enough, so a second start changes nothing.
 *
 * <p><b>Why a registry and not every enum column.</b> Finding every enum column from Hibernate's
 * mapping needs internal metamodel API that moves between versions; a short list is reviewable.
 * {@link #TARGETS} has the one column the Ask phases write a new value to. <em>Add a line
 * when a change adds a value to an enum whose column an existing local database must accept.</em>
 *
 * <p><b>Local H2 only, and it cannot misfire elsewhere.</b> The bean exists only under the
 * {@code local} profile; at run time it also asks the database for its product name and does nothing
 * unless that is H2 (a non-H2 database behind the local profile is left alone, not refused: there
 * is nothing to widen). It never fails startup — a failed ALTER is a WARN, and the symptom is then
 * the refusal this class exists to prevent, with the cause in the log.
 */
@Component
@Profile("local")
public class LocalH2EnumWidener implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(LocalH2EnumWidener.class);

    /** Table and column names are interpolated into DDL, so only plain identifiers pass. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    /**
     * An enum column and the Java enum that feeds it.
     *
     * @param table  the table name, lower case
     * @param column the column name, lower case
     * @param type   the Java enum whose constants the column must accept
     */
    record Target(String table, String column, Class<? extends Enum<?>> type) {
    }

    /** The columns to keep wide. */
    static final List<Target> TARGETS = List.of(
            new Target("job_run", "run_type", RunType.class));

    private final DataSource dataSource;

    /**
     * Creates the widener.
     *
     * @param dataSource the application's datasource
     */
    public LocalH2EnumWidener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            widen(TARGETS);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("[LOCAL H2] Could not widen the enum columns; an existing local database may refuse "
                    + "ASK job runs until backend/data is deleted: {}", e.toString());
        }
    }

    /**
     * Widens every target column that lacks a value its Java enum declares.
     *
     * @param targets the columns
     * @return how many columns were altered
     * @throws SQLException if the database cannot be read or altered
     */
    int widen(List<Target> targets) throws SQLException {
        int altered = 0;
        try (Connection connection = dataSource.getConnection()) {
            if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return 0;
            }
            for (Target target : targets) {
                if (widen(connection, target)) {
                    altered++;
                }
            }
        }
        return altered;
    }

    private boolean widen(Connection connection, Target target) throws SQLException {
        if (!IDENTIFIER.matcher(target.table()).matches() || !IDENTIFIER.matcher(target.column()).matches()) {
            throw new IllegalArgumentException("not a plain identifier: " + target);
        }
        List<String> current = currentValues(connection, target);
        if (current.isEmpty()) {
            // Not an ENUM column (or no such table yet): nothing to widen.
            return false;
        }
        Set<String> wanted = new LinkedHashSet<>(current);
        for (Enum<?> constant : target.type().getEnumConstants()) {
            wanted.add(constant.name());
        }
        if (wanted.size() == current.size()) {
            return false;
        }
        List<String> quoted = new ArrayList<>();
        wanted.forEach(value -> quoted.add("'" + value.replace("'", "''") + "'"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table " + target.table() + " alter column " + target.column()
                    + " set data type enum(" + String.join(",", quoted) + ")");
        }
        LOG.info("[LOCAL H2] Widened {}.{} from {} to {} values so an existing local database accepts {}",
                target.table(), target.column(), current.size(), wanted.size(),
                wanted.stream().filter(v -> !current.contains(v)).toList());
        return true;
    }

    /** The values an H2 ENUM column allows now, in order; empty when it is not an ENUM column. */
    private static List<String> currentValues(Connection connection, Target target) throws SQLException {
        String sql = "select e.value_name from information_schema.columns c "
                + "join information_schema.enum_values e on e.object_schema = c.table_schema "
                + "and e.object_name = c.table_name and e.object_type = 'TABLE' "
                + "and e.enum_identifier = c.dtd_identifier "
                + "where c.table_schema = schema() and upper(c.table_name) = upper(?) "
                + "and upper(c.column_name) = upper(?) order by e.value_ordinal";
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.table());
            statement.setString(2, target.column());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }
}
