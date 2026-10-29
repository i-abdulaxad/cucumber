package org.reportservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReportServiceApplication {

    static final String HTTP_CLIENT_ATTEMPT_LIMIT = "jdk.httpclient.redirects.retrylimit";

    public static void main(String[] args) {
        limitHttpClientToOneAttempt();
        SpringApplication.run(ReportServiceApplication.class, args);
    }

    /**
     * One attempt per remote call (RE6, a copy of platform-hardening E7): the JDK 17 HTTP client retries a GET once
     * when the connection closes before the answer, and has no per-client switch for it. This JVM-wide limit of one
     * attempt stops that retry. report-service's HTTP clients (auth-service C5 and C7, scenario-service) follow no
     * redirect, so nothing else changes for them. The JDK reads the value once, so it's set before anything else runs;
     * the build sets the same value for the test JVM (RE7).
     */
    static void limitHttpClientToOneAttempt() {
        System.setProperty(HTTP_CLIENT_ATTEMPT_LIMIT, "1");
    }

}
