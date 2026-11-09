package com.example.authservice.aop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.authservice.configuration.LoggingProperties;
import com.example.authservice.exception.AlreadyMemberException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the logging aspect's rules (D17 item 1): one outcome line per call at one level, nothing but class,
 * method, outcome and durations, and the outermost-call rule.
 */
class LoggingTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final String SECRET_MESSAGE = "fay@example.com Secret123!";

    private final Logger logger = (Logger) LoggerFactory.getLogger(Logging.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureTheAspectLogger() {
        previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreTheAspectLogger() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    @Tag("AC-9.1")
    void atDebugANormalCallLogsItsEntryAndOneExitLineWithADuration() throws Throwable {
        logger.setLevel(Level.DEBUG);
        Logging aspect = aspect(true, ONE_HOUR);

        Object result = aspect.logUseCase(call("createOrganization", () -> "the response"));

        assertThat(result).isEqualTo("the response");
        assertThat(events()).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.DEBUG);
        assertThat(messages().get(0)).isEqualTo("Entering SampleService.createOrganization");
        assertThat(messages().get(1)).matches("Leaving SampleService\\.createOrganization: returned in \\d+ ms");
        assertThat(messages()).noneMatch(message -> message.contains("the response"));
    }

    @Test
    @Tag("AC-9.2")
    void atInfoANormalCallLogsNothing() throws Throwable {
        logger.setLevel(Level.INFO);

        aspect(true, ONE_HOUR).logUseCase(call("findByUser", () -> "orgs"));

        assertThat(events()).isEmpty();
    }

    @Test
    @Tag("AC-9.3")
    void aBusinessErrorOfTheOutermostCallIsOneInfoLineWithItsCodeAndNoThrowable() {
        logger.setLevel(Level.INFO);
        Logging aspect = aspect(true, ONE_HOUR);

        assertThatThrownBy(() -> aspect.logUseCase(call("inviteMember", () -> {
            throw new AlreadyMemberException();
        }))).isInstanceOf(AlreadyMemberException.class);

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .matches("Leaving SampleService\\.inviteMember: business error ALREADY_MEMBER in \\d+ ms");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @Tag("AC-9.3")
    void aBusinessErrorCrossingTwoAdvisedCallsIsLoggedAtInfoOnce() {
        logger.setLevel(Level.DEBUG);
        Logging aspect = aspect(true, ONE_HOUR);

        assertThatThrownBy(() -> aspect.logUseCase(call("accept", () ->
                aspect.logUseCase(call("inviteMember", () -> {
                    throw new AlreadyMemberException();
                })))))
                .isInstanceOf(AlreadyMemberException.class);

        assertThat(events()).filteredOn(event -> event.getLevel() == Level.INFO).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).startsWith("Leaving SampleService.accept:"));
        assertThat(messages()).anyMatch(message ->
                message.matches("Leaving SampleService\\.inviteMember: business error ALREADY_MEMBER in \\d+ ms"));
    }

    @Test
    @Tag("AC-9.4")
    void anUnexpectedExceptionIsADebugLineWithItsTypeOnly() {
        logger.setLevel(Level.DEBUG);
        Logging aspect = aspect(true, ONE_HOUR);

        assertThatThrownBy(() -> aspect.logUseCase(call("findByUser", () -> {
            throw new IllegalStateException(SECRET_MESSAGE);
        }))).isInstanceOf(IllegalStateException.class).hasMessage(SECRET_MESSAGE);

        assertThat(events()).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_MESSAGE);
        });
        assertThat(messages()).last().asString()
                .matches("Leaving SampleService\\.findByUser: threw IllegalStateException in \\d+ ms");
    }

    @Test
    @Tag("AC-9.4")
    void anUnexpectedExceptionIsNotLoggedAtInfoOrAbove() {
        logger.setLevel(Level.INFO);

        assertThatThrownBy(() -> aspect(true, ONE_HOUR).logUseCase(call("findByUser", () -> {
            throw new IllegalStateException(SECRET_MESSAGE);
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(events()).isEmpty();
    }

    @Test
    @Tag("AC-9.5")
    void anOutermostCallAboveTheThresholdIsOneWarnLineWithTheOutcomeAndTheThreshold() {
        logger.setLevel(Level.INFO);
        Logging aspect = aspect(true, Duration.ofMillis(1));

        assertThatThrownBy(() -> aspect.logUseCase(call("inviteMember", () -> {
            Thread.sleep(5);
            throw new AlreadyMemberException();
        }))).isInstanceOf(AlreadyMemberException.class);

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).matches(
                    "Slow call SampleService\\.inviteMember: business error ALREADY_MEMBER in \\d+ ms \\(threshold 1 ms\\)");
            // LockFailureTest and QaFixRoundTest count the handler's lock WARN line by this text.
            assertThat(event.getFormattedMessage()).doesNotContain("Request rejected because a lock could not be acquired");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @Tag("AC-9.5")
    void aSlowNestedCallIsLoggedAtDebugOnly() throws Throwable {
        logger.setLevel(Level.DEBUG);
        Logging aspect = aspect(true, Duration.ofMillis(1));

        aspect.logUseCase(call("accept", () -> aspect.logUseCase(call("inviteMember", () -> {
            Thread.sleep(5);
            return null;
        }))));

        assertThat(events()).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).startsWith("Slow call SampleService.accept:"));
        assertThat(messages()).anyMatch(message ->
                message.matches("Leaving SampleService\\.inviteMember: returned in \\d+ ms"));
    }

    @Test
    void theOutermostCallIsResetAfterAFailure() {
        logger.setLevel(Level.INFO);
        Logging aspect = aspect(true, ONE_HOUR);

        assertThatThrownBy(() -> aspect.logUseCase(call("findByUser", () -> {
            throw new IllegalStateException("first");
        }))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> aspect.logUseCase(call("inviteMember", () -> {
            throw new AlreadyMemberException();
        }))).isInstanceOf(AlreadyMemberException.class);

        assertThat(events()).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.INFO);
    }

    @Test
    @Tag("AC-9.12")
    void whenSwitchedOffTheAspectLogsNothing() throws Throwable {
        logger.setLevel(Level.DEBUG);
        Logging aspect = aspect(false, Duration.ZERO);

        Object result = aspect.logUseCase(call("findByUser", () -> "orgs"));
        assertThatThrownBy(() -> aspect.logUseCase(call("inviteMember", () -> {
            throw new AlreadyMemberException();
        }))).isInstanceOf(AlreadyMemberException.class);

        assertThat(result).isEqualTo("orgs");
        assertThat(events()).isEmpty();
    }

    private static Logging aspect(boolean enabled, Duration threshold) {
        return new Logging(new LoggingProperties(enabled, threshold));
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * A join point on {@link SampleService} whose {@code proceed()} runs the given body.
     */
    private static ProceedingJoinPoint call(String method, Body body) throws Throwable {
        ProceedingJoinPoint call = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn(method);
        when(call.getSignature()).thenReturn(signature);
        when(call.getTarget()).thenReturn(new SampleService());
        when(call.proceed()).thenAnswer(invocation -> body.run());
        return call;
    }

    @FunctionalInterface
    private interface Body {
        Object run() throws Throwable;
    }

    private static final class SampleService {
    }
}
