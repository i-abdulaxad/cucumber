package org.reportservice.exception;

import java.util.List;
import java.util.UUID;

/** Feature ids that aren't active features of the project (D10, AC-3.4): 422 with {@code featureIds}. */
public class FeatureNotPlannableException extends ApiException {

    private final transient List<UUID> featureIds;

    public FeatureNotPlannableException(List<UUID> featureIds) {
        super(ErrorCode.FEATURE_NOT_PLANNABLE, "Some features can't be planned.");
        this.featureIds = List.copyOf(featureIds);
    }

    public List<UUID> featureIds() {
        return featureIds;
    }
}
