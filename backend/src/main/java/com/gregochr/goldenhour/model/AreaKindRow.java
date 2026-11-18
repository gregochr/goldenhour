package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AreaKind;

/**
 * An area's id and kind, flat.
 *
 * @param id   the area id
 * @param kind the kind; null (a row read before V191) reads as a named area
 */
public record AreaKindRow(Long id, AreaKind kind) {
}
