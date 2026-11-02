package org.reportservice.service.impl;

import org.reportservice.client.ProjectStatusClient;
import org.reportservice.dto.request.CiUploadQuery;
import org.reportservice.dto.request.UploadBody;
import org.reportservice.dto.response.BuildResponse;
import org.reportservice.dto.response.EnvironmentResponse;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;
import org.reportservice.exception.CucumberJsonInvalidException;
import org.reportservice.exception.InvalidFieldsException;
import org.reportservice.exception.MalformedRequestException;
import org.reportservice.exception.ProjectArchivedException;
import org.reportservice.exception.UploadEmptyException;
import org.reportservice.exception.UploadTokenInvalidException;
import org.reportservice.exception.UploadTooLargeException;
import org.reportservice.exception.UnsupportedMediaTypeException;
import org.reportservice.exception.UploadTooManyTestCasesException;
import org.reportservice.ingest.CucumberJsonException;
import org.reportservice.ingest.CucumberJsonReader;
import org.reportservice.ingest.IngestedRow;
import org.reportservice.ingest.IngestedTestCase;
import org.reportservice.model.Build;
import org.reportservice.model.Environment;
import org.reportservice.model.StepResult;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.model.UploadToken;
import org.reportservice.repository.BuildRepository;
import org.reportservice.repository.EnvironmentRepository;
import org.reportservice.repository.StepResultRepository;
import org.reportservice.repository.TestCaseResultRepository;
import org.reportservice.repository.TestRunRepository;
import org.reportservice.repository.UploadTokenRepository;
import org.reportservice.security.UploadTokenPrincipal;
import org.reportservice.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The CI upload (US-2; D5, D8, D9, D11, D13, D14, D17), in D13's order: check the body's charset and encoding (415:
 * UTF-8 JSON only, never decoded, BD-15), reject a declared length over 20 MiB unread (413), read the body up to the
 * limit (413 beyond; never more than one byte past the limit is held), check the query parameters (400), read the
 * Cucumber JSON (400 or 422), ask auth-service whether the project is archived (C7: 503, then 409), all before any
 * transaction. Then one transaction: the project lock first, the token re-read under it (a token revoked meanwhile
 * is 401 and nothing is stored, AC-2.14), environment and build found or created by exact name, the run, its results
 * and their rows inserted in batches, and the token's {@code lastUsedAt} set to the run's {@code createdAt}. One INFO line per upload: run id,
 * test-case count and duration; never content, names or query values.
 */
@Service
public class UploadServiceImpl implements UploadService {

    static final int MAX_BODY_BYTES = 20 * 1024 * 1024;
    static final int MAX_NAME_CODE_POINTS = 255;
    static final int MAX_ENVIRONMENT_CODE_POINTS = 100;
    static final int MAX_CI_URL_LENGTH = 2048;

    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);
    private static final Pattern COMMIT = Pattern.compile("[0-9A-Fa-f]{4,64}");

    private final CucumberJsonReader reader;
    private final ProjectStatusClient projectStatus;
    private final UploadTokenRepository tokens;
    private final TestRunRepository runs;
    private final EnvironmentRepository environments;
    private final BuildRepository builds;
    private final TestCaseResultRepository results;
    private final StepResultRepository steps;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    public UploadServiceImpl(CucumberJsonReader reader, ProjectStatusClient projectStatus,
                             UploadTokenRepository tokens, TestRunRepository runs, EnvironmentRepository environments,
                             BuildRepository builds, TestCaseResultRepository results, StepResultRepository steps,
                             Clock clock, PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.projectStatus = projectStatus;
        this.tokens = tokens;
        this.runs = runs;
        this.environments = environments;
        this.builds = builds;
        this.results = results;
        this.steps = steps;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public TestRunResponse upload(UploadTokenPrincipal uploader, CiUploadQuery query, UploadBody body) {
        long start = System.nanoTime();
        checkMediaType(body);
        if (body.contentLength() != null && body.contentLength() > MAX_BODY_BYTES) {
            throw new UploadTooLargeException();
        }
        byte[] json = readAtMostTheLimit(body.stream());
        check(query);
        List<IngestedTestCase> cases = read(json);
        if (projectStatus.isArchived(uploader.projectId())) {
            throw new ProjectArchivedException();
        }
        TestRunResponse run = writeTransaction.execute(status -> store(uploader, query, cases));
        log.info("Upload stored: run {} with {} test cases in {} ms", run.id(), cases.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        return run;
    }

    /** BD-15: {@code application/json} with no charset or {@code charset=utf-8}; no content coding but identity. */
    private static void checkMediaType(UploadBody body) {
        if (body.contentType() != null) {
            String[] parts = body.contentType().split(";");
            for (int i = 1; i < parts.length; i++) {
                String[] parameter = parts[i].split("=", 2);
                if (parameter[0].strip().equalsIgnoreCase("charset")) {
                    String charset = parameter.length < 2 ? "" : parameter[1].strip().replace("\"", "");
                    if (!charset.equalsIgnoreCase("utf-8")) {
                        throw new UnsupportedMediaTypeException();
                    }
                }
            }
        }
        String encoding = body.contentEncoding();
        if (encoding != null && !encoding.isBlank() && !encoding.strip().equalsIgnoreCase("identity")) {
            throw new UnsupportedMediaTypeException();
        }
    }

    private static byte[] readAtMostTheLimit(InputStream body) {
        byte[] bytes;
        try {
            bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException ex) {
            throw new MalformedRequestException();
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new UploadTooLargeException();
        }
        return bytes;
    }

    private static void check(CiUploadQuery query) {
        Map<String, String> invalid = new TreeMap<>();
        name(query.name(), "name", MAX_NAME_CODE_POINTS, invalid);
        name(query.environment(), "environment", MAX_ENVIRONMENT_CODE_POINTS, invalid);
        name(query.build(), "build", MAX_NAME_CODE_POINTS, invalid);
        name(query.branch(), "branch", MAX_NAME_CODE_POINTS, invalid);
        if (query.commit() != null && !COMMIT.matcher(query.commit()).matches()) {
            invalid.put("commit", "Commit must be 4 to 64 hexadecimal characters");
        }
        if (query.ciUrl() != null && !isHttpUrl(query.ciUrl())) {
            invalid.put("ciUrl", "CI URL must be an absolute http or https URL of at most 2,048 characters");
        }
        if (!invalid.isEmpty()) {
            throw new InvalidFieldsException(invalid);
        }
    }

    private static void name(String value, String field, int max, Map<String, String> invalid) {
        if (value != null && !TextRules.isName(value, max)) {
            invalid.put(field, field + " must be 1 to " + max + " characters, not blank, without control characters");
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value.length() > MAX_CI_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private List<IngestedTestCase> read(byte[] json) {
        try {
            return reader.read(json);
        } catch (CucumberJsonException ex) {
            throw switch (ex.reason()) {
                case MALFORMED -> new MalformedRequestException();
                case INVALID -> new CucumberJsonInvalidException(ex.problems());
                case EMPTY -> new UploadEmptyException();
                case TOO_MANY_TEST_CASES -> new UploadTooManyTestCasesException();
            };
        }
    }

    private TestRunResponse store(UploadTokenPrincipal uploader, CiUploadQuery query, List<IngestedTestCase> cases) {
        UUID projectId = uploader.projectId();
        runs.lockProject(projectId);
        Instant now = Instant.now(clock);
        UploadToken token = tokens.findById(uploader.tokenId())
                .filter(found -> found.getRevokedAt() == null
                        && (found.getExpiresAt() == null || found.getExpiresAt().isAfter(now)))
                .orElseThrow(UploadTokenInvalidException::new);
        Environment environment = query.environment() == null ? null : environment(projectId, query.environment(), now);
        Build build = query.build() == null ? null : build(projectId, query, now);

        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setProjectId(projectId);
        run.setName(query.name() != null ? query.name() : "CI upload " + now);
        run.setSource(TestRunSource.AUTOMATED);
        run.setStatus(TestRunStatus.COMPLETED);
        run.setEnvironmentId(environment == null ? null : environment.getId());
        run.setBuildId(build == null ? null : build.getId());
        run.setUploadTokenId(token.getId());
        run.setFinishedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runs.save(run);

        List<TestCaseResult> resultRows = new ArrayList<>(cases.size());
        List<StepResult> stepRows = new ArrayList<>();
        List<RunSummaries.Item> items = new ArrayList<>(cases.size());
        for (int position = 0; position < cases.size(); position++) {
            IngestedTestCase testCase = cases.get(position);
            TestCaseResult result = result(run.getId(), position + 1, testCase, now);
            resultRows.add(result);
            for (int row = 0; row < testCase.rows().size(); row++) {
                stepRows.add(step(result.getId(), row + 1, testCase.rows().get(row), now));
            }
            items.add(new RunSummaries.Item(testCase.featurePath(), testCase.featureName(), testCase.status(),
                    testCase.durationMs()));
        }
        results.saveAll(resultRows);
        steps.saveAll(stepRows);
        token.setLastUsedAt(now);
        token.setUpdatedAt(now);

        return new TestRunResponse(run.getId(), projectId, run.getName(), run.getSource(), run.getStatus(),
                environment == null ? null : new EnvironmentResponse(environment.getId(), environment.getName()),
                build == null ? null : new BuildResponse(build.getId(), build.getName(), build.getBranch(),
                        build.getCommitSha(), build.getCiUrl()),
                null, null, null, now, now, now, RunSummaries.summarize(items));
    }

    private Environment environment(UUID projectId, String name, Instant now) {
        return environments.findByProjectIdAndName(projectId, name).orElseGet(() -> {
            Environment created = new Environment();
            created.setId(UUID.randomUUID());
            created.setProjectId(projectId);
            created.setName(name);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return environments.save(created);
        });
    }

    private Build build(UUID projectId, CiUploadQuery query, Instant now) {
        return builds.findByProjectIdAndName(projectId, query.build()).orElseGet(() -> {
            Build created = new Build();
            created.setId(UUID.randomUUID());
            created.setProjectId(projectId);
            created.setName(query.build());
            created.setBranch(query.branch());
            created.setCommitSha(query.commit() == null ? null : query.commit().toLowerCase(Locale.ROOT));
            created.setCiUrl(query.ciUrl());
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return builds.save(created);
        });
    }

    private static TestCaseResult result(UUID runId, int position, IngestedTestCase testCase, Instant now) {
        TestCaseResult result = new TestCaseResult();
        result.setId(UUID.randomUUID());
        result.setTestRunId(runId);
        result.setPosition(position);
        result.setFeaturePath(testCase.featurePath());
        result.setFeatureName(testCase.featureName());
        result.setScenarioName(testCase.name());
        result.setTags(testCase.tags());
        result.setLine(testCase.line());
        result.setStatus(testCase.status());
        result.setAttempt(1);
        result.setDurationMs(testCase.durationMs());
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        return result;
    }

    private static StepResult step(UUID resultId, int position, IngestedRow row, Instant now) {
        StepResult step = new StepResult();
        step.setId(UUID.randomUUID());
        step.setTestCaseResultId(resultId);
        step.setPosition(position);
        step.setKind(row.kind());
        step.setKeywordText(row.keyword());
        step.setStepText(row.text());
        step.setDataTable(row.dataTable());
        step.setDocString(row.docString());
        step.setStatus(row.status());
        step.setDurationMs(row.durationMs());
        step.setErrorMessage(row.errorMessage());
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        return step;
    }
}
