package org.scenarioservice.service.impl;

import org.scenarioservice.dto.request.CreateFeatureRequest;
import org.scenarioservice.dto.request.SaveFeatureRequest;
import org.scenarioservice.dto.request.UnicodeBlank;
import org.scenarioservice.dto.response.FeatureResponse;
import org.scenarioservice.dto.response.FeatureSummaryResponse;
import org.scenarioservice.dto.response.FeatureVersionResponse;
import org.scenarioservice.dto.response.FeatureVersionSummaryResponse;
import org.scenarioservice.exception.FeatureArchivedException;
import org.scenarioservice.exception.FeatureNotFoundException;
import org.scenarioservice.exception.FeaturePathTakenException;
import org.scenarioservice.exception.FeatureTooLargeException;
import org.scenarioservice.exception.GherkinInvalidException;
import org.scenarioservice.exception.VersionConflictException;
import org.scenarioservice.exception.VersionNotFoundException;
import org.scenarioservice.gherkin.GherkinAdapter;
import org.scenarioservice.gherkin.GherkinParseResult;
import org.scenarioservice.gherkin.ParsedFeature;
import org.scenarioservice.model.Feature;
import org.scenarioservice.model.FeatureVersion;
import org.scenarioservice.repository.FeatureRepository;
import org.scenarioservice.repository.FeatureVersionRepository;
import org.scenarioservice.repository.TagRepository;
import org.scenarioservice.security.ProjectAccessChecker;
import org.scenarioservice.service.FeatureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The feature use cases (US-1 to US-3). Each one checks access first (D5), outside any transaction (P13), then
 * parses the text (pure), and only then opens its transaction. Writes take the project's advisory lock as their first
 * statement, then check paths and versions (D14). The error order is D16's: access, archived project, not found,
 * archived feature, version conflict, invalid Gherkin, path taken.
 */
@Service
public class FeatureServiceImpl implements FeatureService {

    /** A5: features by path, lower-cased with Locale.ROOT, then id. The one place that orders the list. */
    /**
     * D21 #2: the most elements one feature may have.
     */
    static final int MAX_ELEMENTS = 10_000;

    private static final Comparator<Feature> LIST_ORDER = Comparator
            .comparing((Feature feature) -> feature.getPath().toLowerCase(Locale.ROOT))
            .thenComparing(Feature::getId);

    private final ProjectAccessChecker accessChecker;
    private final GherkinAdapter gherkin;
    private final FeatureRepository features;
    private final FeatureVersionRepository versions;
    private final TagRepository tags;
    private final FeatureStructure structure;
    private final FeatureViews views;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public FeatureServiceImpl(ProjectAccessChecker accessChecker, GherkinAdapter gherkin, FeatureRepository features,
                              FeatureVersionRepository versions, TagRepository tags, FeatureStructure structure,
                              FeatureViews views, Clock clock, PlatformTransactionManager transactionManager) {
        this.accessChecker = accessChecker;
        this.gherkin = gherkin;
        this.features = features;
        this.versions = versions;
        this.tags = tags;
        this.structure = structure;
        this.views = views;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public List<FeatureSummaryResponse> list(UUID projectId, boolean archived, List<String> tagValues) {
        String tag = TagFilterRule.tag(tagValues);
        accessChecker.requireRead(projectId);
        return read(() -> {
            List<Feature> found;
            if (tag == null) {
                found = archived ? features.findByProjectIdAndArchivedAtIsNotNull(projectId)
                        : features.findByProjectIdAndArchivedAtIsNull(projectId);
            } else {
                found = tags.findByProjectIdAndName(projectId, tag)
                        .map(known -> archived ? features.findArchivedWithTag(projectId, known.getId())
                                : features.findActiveWithTag(projectId, known.getId()))
                        .orElse(List.of());
            }
            if (found.isEmpty()) {
                return List.of();
            }
            Map<UUID, String> sources = versions.findCurrentVersions(found.stream().map(Feature::getId).toList())
                    .stream().collect(Collectors.toMap(FeatureVersion::getFeatureId, FeatureVersion::getSource));
            return found.stream().sorted(LIST_ORDER)
                    .map(feature -> views.summary(feature, sources.get(feature.getId()))).toList();
        });
    }

    @Override
    public FeatureResponse get(UUID projectId, UUID featureId) {
        accessChecker.requireRead(projectId);
        return read(() -> {
            Feature feature = feature(projectId, featureId);
            return views.feature(feature, currentSource(feature));
        });
    }

    @Override
    public FeatureResponse create(UUID projectId, UUID userId, CreateFeatureRequest request) {
        accessChecker.requireWrite(projectId);
        // D21 #2: parse and count before the transaction and the lock (order steps 7 and 7a)
        ParsedFeature parsed = withinLimit(parse(request.source()));
        String message = message(request.message());
        return write(() -> {
            features.lockProject(projectId);
            if (features.isPathTaken(projectId, request.path())) {
                throw new FeaturePathTakenException();
            }
            Instant now = Instant.now(clock);
            Feature feature = features.save(Feature.builder().id(UUID.randomUUID()).projectId(projectId)
                    .path(request.path()).name(parsed.name()).description(parsed.description())
                    .language(parsed.language()).currentVersion(1).createdBy(userId).updatedBy(userId)
                    .createdAt(now).updatedAt(now).build());
            versions.save(version(feature.getId(), 1, request.source(), message, userId, now));
            FeatureStructure.Written written = structure.create(projectId, feature.getId(), parsed, now);
            return views.written(feature, request.source(), parsed, written);
        });
    }

    @Override
    public FeatureResponse save(UUID projectId, UUID featureId, UUID userId, SaveFeatureRequest request) {
        accessChecker.requireWrite(projectId);
        String message = message(request.message());
        // D21 #2: parse and count before the transaction and the lock; the outcome is reported after the checks
        // that come first in D16's order (not found, archived feature, version conflict)
        Supplier<ParsedFeature> parsedOrFailure = parsedOrFailure(request.source());
        return write(() -> {
            features.lockProject(projectId);
            Feature feature = feature(projectId, featureId);
            if (feature.getArchivedAt() != null) {
                throw new FeatureArchivedException();
            }
            if (request.baseVersion() != feature.getCurrentVersion()) {
                throw new VersionConflictException(feature.getCurrentVersion());
            }
            ParsedFeature parsed = parsedOrFailure.get();
            boolean pathChanged = !request.path().equals(feature.getPath());
            if (pathChanged && features.isPathTakenByAnother(projectId, request.path(), featureId)) {
                throw new FeaturePathTakenException();
            }
            String currentSource = currentSource(feature);
            boolean textChanged = !sha256(request.source()).equals(sha256(currentSource));
            if (!textChanged && !pathChanged) {
                return views.feature(feature, currentSource);
            }
            Instant now = Instant.now(clock);
            FeatureStructure.Written written = null;
            if (textChanged) {
                int next = feature.getCurrentVersion() + 1;
                versions.save(version(featureId, next, request.source(), message, userId, now));
                written = structure.replace(projectId, featureId, parsed, now);
                feature.setCurrentVersion(next);
                feature.setName(parsed.name());
                feature.setDescription(parsed.description());
                feature.setLanguage(parsed.language());
            }
            feature.setPath(request.path());
            feature.setUpdatedBy(userId);
            feature.setUpdatedAt(now);
            Feature saved = features.save(feature);
            return textChanged ? views.written(saved, request.source(), parsed, written)
                    : views.feature(saved, currentSource);
        });
    }

    @Override
    public FeatureResponse archive(UUID projectId, UUID featureId, UUID userId) {
        accessChecker.requireWrite(projectId);
        return write(() -> {
            features.lockProject(projectId);
            Feature feature = feature(projectId, featureId);
            if (feature.getArchivedAt() == null) {
                Instant now = Instant.now(clock);
                feature.setArchivedAt(now);
                feature.setUpdatedBy(userId);
                feature.setUpdatedAt(now);
                feature = features.save(feature);
            }
            return views.feature(feature, currentSource(feature));
        });
    }

    @Override
    public FeatureResponse restore(UUID projectId, UUID featureId, UUID userId) {
        accessChecker.requireWrite(projectId);
        return write(() -> {
            features.lockProject(projectId);
            Feature feature = feature(projectId, featureId);
            if (feature.getArchivedAt() != null) {
                if (features.isPathTakenByAnother(projectId, feature.getPath(), featureId)) {
                    throw new FeaturePathTakenException();
                }
                Instant now = Instant.now(clock);
                feature.setArchivedAt(null);
                feature.setUpdatedBy(userId);
                feature.setUpdatedAt(now);
                feature = features.save(feature);
            }
            return views.feature(feature, currentSource(feature));
        });
    }

    @Override
    public List<FeatureVersionSummaryResponse> versions(UUID projectId, UUID featureId) {
        accessChecker.requireRead(projectId);
        return read(() -> {
            feature(projectId, featureId);
            return versions.findByFeatureIdOrderByVersionDesc(featureId).stream()
                    .map(version -> new FeatureVersionSummaryResponse(version.getVersion(), version.getMessage(),
                            version.getCreatedBy(), version.getCreatedAt())).toList();
        });
    }

    @Override
    public FeatureVersionResponse version(UUID projectId, UUID featureId, int number) {
        accessChecker.requireRead(projectId);
        return read(() -> {
            feature(projectId, featureId);
            FeatureVersion version = versions.findByFeatureIdAndVersion(featureId, number)
                    .orElseThrow(VersionNotFoundException::new);
            return new FeatureVersionResponse(version.getVersion(), version.getMessage(), version.getCreatedBy(),
                    version.getCreatedAt(), version.getSource());
        });
    }

    private Feature feature(UUID projectId, UUID featureId) {
        return features.findByIdAndProjectId(featureId, projectId).orElseThrow(FeatureNotFoundException::new);
    }

    private String currentSource(Feature feature) {
        return versions.findByFeatureIdAndVersion(feature.getId(), feature.getCurrentVersion())
                .orElseThrow(() -> new IllegalStateException("A feature has no current version")).getSource();
    }

    /**
     * D21 #2: at most {@value #MAX_ELEMENTS} elements per feature (order step 7a).
     */
    private static ParsedFeature withinLimit(ParsedFeature parsed) {
        if (parsed.elementCount() > MAX_ELEMENTS) {
            throw new FeatureTooLargeException(MAX_ELEMENTS);
        }
        return parsed;
    }

    /**
     * Parses and counts now; the returned supplier gives the feature or throws the parse or size failure later.
     */
    private Supplier<ParsedFeature> parsedOrFailure(String source) {
        try {
            ParsedFeature parsed = withinLimit(parse(source));
            return () -> parsed;
        } catch (GherkinInvalidException | FeatureTooLargeException failure) {
            return () -> {
                throw failure;
            };
        }
    }

    private ParsedFeature parse(String source) {
        GherkinParseResult result = gherkin.parse(source);
        if (result instanceof GherkinParseResult.Invalid invalid) {
            throw new GherkinInvalidException(invalid.errors().stream()
                    .map(error -> new GherkinInvalidException.ParseErrorEntry(error.line(), error.column(),
                            error.message())).toList());
        }
        return ((GherkinParseResult.Valid) result).feature();
    }

    /**
     * D19 #9: trimmed of Unicode-blank characters; nothing left is no message.
     */
    private static String message(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = UnicodeBlank.trim(message);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static FeatureVersion version(UUID featureId, int number, String source, String message, UUID userId,
                                          Instant now) {
        return FeatureVersion.builder().id(UUID.randomUUID()).featureId(featureId).version(number).source(source)
                .contentHash(sha256(source)).message(message).createdBy(userId).createdAt(now).build();
    }

    /**
     * SHA-256 hex of the text's UTF-8 bytes (D8).
     */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(Supplier<T> work) {
        return readTransaction.execute(status -> work.get());
    }

    private <T> T write(Supplier<T> work) {
        return writeTransaction.execute(status -> work.get());
    }
}
