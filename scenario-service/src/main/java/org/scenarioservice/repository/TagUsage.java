package org.scenarioservice.repository;

/**
 * A tag of the catalogue and the number of active features whose current version uses it (D12).
 */
public record TagUsage(String name, long featureCount) {

    @Override
    public String toString() {
        return "TagUsage[featureCount=" + featureCount + "]";
    }
}
