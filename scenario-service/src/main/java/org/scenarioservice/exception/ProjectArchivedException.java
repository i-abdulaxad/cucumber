package org.scenarioservice.exception;

/**
 * A write on an archived project (D3, D11): 409; reads still work.
 */
public class ProjectArchivedException extends ApiException {

    public ProjectArchivedException() {
        super(ErrorCode.PROJECT_ARCHIVED, "The project is archived. Its content is read-only.");
    }
}
