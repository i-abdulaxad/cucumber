package com.example.authservice.exception;

/** C7: no project has this id (report-service spec AC-8.3). */
public class ProjectNotFoundException extends ApiException {

    public ProjectNotFoundException() {
        super(ErrorCode.NOT_FOUND, "No project has this id.");
    }
}
