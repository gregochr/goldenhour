package com.gregochr.goldenhour.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Bound to the {@code photocast.dormancy} section of {@code application.yml}: the dormant-account sweep
 * ({@code DormantAccountSweepJob}) that warns and then deletes inactive LITE_USER accounts.
 *
 * <p>The defaults are the Java field initialisers below, deliberately the safe ones: {@link #dryRun} is
 * {@code true}, so a job that is resumed or run by hand sends and deletes nothing until the owner sets
 * {@code dry-run: false}. The two windows have floors ({@code @Min}): a value below them fails startup rather
 * than quietly shortening the time a reader has to come back.
 */
@Component
@ConfigurationProperties(prefix = "photocast.dormancy")
@Validated
@Getter
@Setter
public class DormancyProperties {

    /** When true (the default) the sweep only logs what it would warn and delete. */
    private boolean dryRun = true;

    /** Days without activity before a LITE_USER is warned. Defaults to 90; at least 30. */
    @Min(30)
    private int inactiveDays = 90;

    /** Days between the warning and the deletion. Defaults to 14; at least 7. */
    @Min(7)
    private int graceDays = 14;
}
