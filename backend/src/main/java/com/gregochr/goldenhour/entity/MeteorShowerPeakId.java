package com.gregochr.goldenhour.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Composite primary key for {@link MeteorShowerPeakEntity}: the shower's IAU code and the IMO calendar year.
 */
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class MeteorShowerPeakId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String showerCode;
    private int calendarYear;
}
