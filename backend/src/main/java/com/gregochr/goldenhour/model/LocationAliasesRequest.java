package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Request body replacing one location's other names.
 *
 * @param aliases the aliases (null reads as none)
 */
public record LocationAliasesRequest(List<String> aliases) {
}
