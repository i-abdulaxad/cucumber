package org.scenarioservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ScenarioServiceApplication {

    static final String HTTP_CLIENT_ATTEMPT_LIMIT = "jdk.httpclient.redirects.retrylimit";

    public static void main(String[] args) {
        limitHttpClientToOneAttempt();
        SpringApplication.run(ScenarioServiceApplication.class, args);
    }

    /**
     * One attempt per access check (platform-hardening D2, SFU-1). The JDK 17 HTTP client retries a GET once when the
     * connection closes before the answer, and has no per-client switch for it. This JVM-wide limit of one attempt
     * stops that retry; the access check client follows no redirect, so nothing else changes for it, and it's this
     * service's only HTTP client. The JDK reads the value once, so it's set before anything else runs; the build sets
     * the same value for the test JVM.
     */
    static void limitHttpClientToOneAttempt() {
        System.setProperty(HTTP_CLIENT_ATTEMPT_LIMIT, "1");
    }
}
