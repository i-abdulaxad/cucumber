package org.reportservice.support;

import org.springframework.boot.test.system.CapturedOutput;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AC-7.2 test setup (as scenario-service US-8): "the log of the request" is the captured console output between the
 * request's start and its end, and "an aspect line" is a log entry written by the {@code aop.Logging} logger. Entries
 * are matched on Spring Boot's console pattern: a timestamp without spaces, the level, ..., the logger name,
 * {@code " : "}, the message.
 */
public final class RequestLog {

    private static final Pattern ASPECT_ENTRY = Pattern.compile(
            "^\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s.*\\sorg\\.reportservice\\.aop\\.Logging\\s+:\\s(.*)$");
    private static final Pattern ENTRY = Pattern.compile("^\\S+\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s");
    private static final Pattern STACK_TRACE_LINE =
            Pattern.compile("^(\\s+at\\s|\\s+\\.\\.\\. \\d+ more|Caused by: |\\s+Suppressed: )");

    private final List<String> lines;

    private RequestLog(String text) {
        this.lines = Arrays.stream(text.split("\\R")).filter(line -> !line.isBlank()).toList();
    }

    /** The output captured since {@code start}: the length of {@link CapturedOutput#getAll()} before the request. */
    public static RequestLog since(CapturedOutput output, int start) {
        return new RequestLog(output.getAll().substring(start));
    }

    /** Every non-blank line, including stack trace lines. */
    public List<String> lines() {
        return lines;
    }

    /** The lines that start a log entry (timestamp, then the level). */
    public List<String> entries() {
        return lines.stream().filter(line -> ENTRY.matcher(line).find()).toList();
    }

    public List<AspectLine> aspectLines() {
        return lines.stream()
                .map(ASPECT_ENTRY::matcher)
                .filter(Matcher::matches)
                .map(matcher -> new AspectLine(matcher.group(1), matcher.group(2), matcher.group()))
                .toList();
    }

    public long count(String level) {
        return entries().stream().filter(line -> line.matches("^\\S+\\s+" + level + "\\s.*")).count();
    }

    public boolean hasStackTraceLine() {
        return lines.stream().anyMatch(line -> STACK_TRACE_LINE.matcher(line).find());
    }

    /** A log entry of the aspect: its level, its message, and the whole line. */
    public record AspectLine(String level, String message, String line) {
    }
}
