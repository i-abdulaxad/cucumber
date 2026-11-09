package org.reportservice.service;

import org.reportservice.dto.request.CiUploadQuery;
import org.reportservice.dto.request.UploadBody;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.security.UploadTokenPrincipal;

/**
 * The CI upload (US-2, D9): Cucumber JSON in, one completed AUTOMATED run out.
 */
public interface UploadService {

    TestRunResponse upload(UploadTokenPrincipal uploader, CiUploadQuery query, UploadBody body);
}
