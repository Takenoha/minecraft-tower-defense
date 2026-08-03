package io.github.takenoha.towerdefense.domain;

import java.util.List;

/**
 * Paper-independent projection of an optional third-party region protection query.
 *
 * <p>Implementations must return violations when the requested combat circle overlaps a
 * protected area. A missing integration is represented by {@link #none()} and does not change
 * the existing explicit deny-list policy.</p>
 */
@FunctionalInterface
public interface ThirdPartyRegionProbe {
    List<String> violations(
            String worldName,
            double centerX,
            double centerZ,
            double radius);

    /** Returns a probe for servers without a third-party region plugin. */
    static ThirdPartyRegionProbe none() {
        return (worldName, centerX, centerZ, radius) -> List.of();
    }
}
