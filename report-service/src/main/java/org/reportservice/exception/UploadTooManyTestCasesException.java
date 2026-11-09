package org.reportservice.exception;

/**
 * The upload has more than 5,000 test cases (D9, AC-2.9).
 */
public class UploadTooManyTestCasesException extends ApiException {

    public UploadTooManyTestCasesException() {
        super(ErrorCode.UPLOAD_TOO_MANY_TEST_CASES, "The upload has more than 5,000 test cases.");
    }
}
