package org.reportservice.ingest;

/** One rule a read member breaks (D9): its JSONPath from {@code $}, and a message that never contains the value. */
public record JsonProblem(String pointer, String message) {
}
