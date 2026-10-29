package org.reportservice.exception;

/** A write on an archived project (D7): 409; reads still work. */
public class ProjectArchivedException extends ApiException {

    public ProjectArchivedException() {
        super(ErrorCode.PROJECT_ARCHIVED, "The project is archived. Its content is read-only.");
    }
}
