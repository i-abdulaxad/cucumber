package org.reportservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scenario stub of the spec's glossary (D16): an in-process HTTP server on {@code 127.0.0.1} answering
 * scenario-service 1.0.4's {@code GET /api/projects/{p}/features} (active features) and
 * {@code GET /api/projects/{p}/features/{f}/test-cases}. Checkout has Payment (version 2, three test cases), Address
 * (version 1, one) and the archived Legacy (not listed); Billing has Invoice. It records every request, and a test can
 * make one kind of call fail, wait, answer oddly, change a version on a later list, or grow a feature. One per test
 * JVM, on the port {@link TestSettings} reserved. Tests call {@link #reset()} first.
 */
public final class ScenarioServiceStub {

    public static final UUID PAYMENT = UUID.fromString("f0000000-0000-4000-8000-0000000000a1");
    public static final UUID ADDRESS = UUID.fromString("f0000000-0000-4000-8000-0000000000a2");
    public static final UUID LEGACY = UUID.fromString("f0000000-0000-4000-8000-0000000000a3");
    public static final UUID INVOICE = UUID.fromString("f0000000-0000-4000-8000-0000000000b1");
    public static final UUID S_CARD = UUID.fromString("5c000000-0000-4000-8000-000000000001");
    public static final UUID S_VOUCHER = UUID.fromString("5c000000-0000-4000-8000-000000000002");
    public static final UUID R_SAVE10 = UUID.fromString("e0000000-0000-4000-8000-000000000003");
    public static final UUID R_OLD5 = UUID.fromString("e0000000-0000-4000-8000-000000000004");
    public static final UUID S_ADDRESS = UUID.fromString("5c000000-0000-4000-8000-000000000005");

    /**
     * What a test can make one kind of call do.
     */
    public enum Kind {
        LIST,
        TEST_CASES
    }

    private static final Pattern LIST_PATH = Pattern.compile("^/api/projects/([^/]+)/features$");
    private static final Pattern TEST_CASES_PATH =
            Pattern.compile("^/api/projects/([^/]+)/features/([^/]+)/test-cases$");

    private static ScenarioServiceStub instance;

    private final int port;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<Kind, Answer> fixedAnswers = new ConcurrentHashMap<>();
    private final AtomicInteger lists = new AtomicInteger();
    private volatile Duration delay = Duration.ZERO;
    private volatile int paymentVersionFromSecondList;
    private volatile int extraPaymentTestCases;
    private HttpServer server;

    private ScenarioServiceStub(int port) {
        this.port = port;
    }

    public static synchronized ScenarioServiceStub get() {
        if (instance == null) {
            instance = new ScenarioServiceStub(TestSettings.releaseScenarioServicePort());
            instance.start();
        }
        return instance;
    }

    /**
     * Back to the fixture's answers, running, with no recorded request.
     */
    public synchronized void reset() {
        received.clear();
        fixedAnswers.clear();
        lists.set(0);
        delay = Duration.ZERO;
        paymentVersionFromSecondList = 0;
        extraPaymentTestCases = 0;
        if (server == null) {
            start();
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    /**
     * Answers every call of this kind with this status and body.
     */
    public void answer(Kind kind, int status, String body) {
        fixedAnswers.put(kind, new Answer(status, body));
    }

    public void delay(Duration wait) {
        delay = wait;
    }

    /**
     * From the second feature list on, Payment's {@code currentVersion} is this (AC-3.6).
     */
    public void paymentVersionFromSecondList(int version) {
        paymentVersionFromSecondList = version;
    }

    /**
     * Payment gets this many more test cases (AC-3.5).
     */
    public void extraPaymentTestCases(int count) {
        extraPaymentTestCases = count;
    }

    private synchronized void start() {
        try {
            HttpServer created = HttpServer.create();
            created.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
            created.createContext("/", this::handle);
            created.setExecutor(Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "scenario-service-stub");
                thread.setDaemon(true);
                return thread;
            }));
            created.start();
            server = created;
        } catch (IOException e) {
            throw new UncheckedIOException("The scenario-service stub can't bind its reserved port " + port, e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getRawPath();
            received.add(new Received(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Request-Id")));
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            Answer answer = answer(exchange, path);
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client gave up (for example after its read timeout); nothing to answer
        }
    }

    private Answer answer(HttpExchange exchange, String path) {
        Matcher list = LIST_PATH.matcher(path);
        Matcher testCases = TEST_CASES_PATH.matcher(path);
        if (list.matches()) {
            int number = lists.incrementAndGet();
            Answer fixed = fixedAnswers.get(Kind.LIST);
            return fixed != null ? fixed : new Answer(200, features(UUID.fromString(list.group(1)), number));
        }
        if (testCases.matches()) {
            Answer fixed = fixedAnswers.get(Kind.TEST_CASES);
            return fixed != null ? fixed : testCases(UUID.fromString(testCases.group(2)));
        }
        return new Answer(404, "{\"code\":\"NOT_FOUND\"}");
    }

    private String features(UUID projectId, int listNumber) {
        if (projectId.equals(ReportsFixture.CHECKOUT)) {
            int paymentVersion = listNumber > 1 && paymentVersionFromSecondList > 0 ? paymentVersionFromSecondList : 2;
            return "[" + feature(PAYMENT, "features/checkout/payment.feature", "Payment", paymentVersion) + ","
                    + feature(ADDRESS, "features/checkout/address.feature", "Address", 1) + "]";
        }
        if (projectId.equals(ReportsFixture.BILLING)) {
            return "[" + feature(INVOICE, "features/billing/invoice.feature", "Invoice", 1) + "]";
        }
        return "[]";
    }

    private static String feature(UUID id, String path, String name, int version) {
        return "{\"id\":\"" + id + "\",\"projectId\":\"" + ReportsFixture.CHECKOUT + "\",\"path\":\"" + path
                + "\",\"name\":\"" + name + "\",\"language\":\"en\",\"currentVersion\":" + version
                + ",\"tags\":[],\"testCaseCount\":1,\"archivedAt\":null}";
    }

    private Answer testCases(UUID featureId) {
        if (featureId.equals(PAYMENT)) {
            List<String> cases = new ArrayList<>(List.of(
                    testCase(S_CARD, "SCENARIO", S_CARD, null, "Pay by card", "[\"@checkout\",\"@smoke\"]", 12,
                            "[{\"keyword\":\"Given\",\"text\":\"a basket with these items:\","
                                    + "\"dataTable\":[[\"item\",\"price\"],[\"Mug\",\"8.00\"]],\"docString\":null},"
                                    + "{\"keyword\":\"When\",\"text\":\"the customer pays by card\","
                                    + "\"dataTable\":null,\"docString\":null},"
                                    + "{\"keyword\":\"Then\",\"text\":\"the receipt is:\",\"dataTable\":null,"
                                    + "\"docString\":{\"content\":\"Total: 8.00\",\"mediaType\":null}}]"),
                    testCase(R_SAVE10, "EXAMPLE_ROW", S_VOUCHER, R_SAVE10, "Pay with voucher SAVE10",
                            "[\"@checkout\",\"@voucher\"]", 30, voucherSteps("SAVE10")),
                    testCase(R_OLD5, "EXAMPLE_ROW", S_VOUCHER, R_OLD5, "Pay with voucher OLD5",
                            "[\"@checkout\",\"@voucher\"]", 31, voucherSteps("OLD5"))));
            for (int i = 0; i < extraPaymentTestCases; i++) {
                UUID id = UUID.randomUUID();
                cases.add(testCase(id, "SCENARIO", id, null, "Extra " + i, "[\"@checkout\"]", 100 + i, "[]"));
            }
            return new Answer(200, "[" + String.join(",", cases) + "]");
        }
        if (featureId.equals(ADDRESS)) {
            return new Answer(200, "[" + testCase(S_ADDRESS, "SCENARIO", S_ADDRESS, null, "Change address",
                    "[\"@address\",\"@smoke\"]", 5, "[{\"keyword\":\"When\",\"text\":\"the customer changes the "
                            + "address\",\"dataTable\":null,\"docString\":null}]") + "]");
        }
        return new Answer(404, "{\"code\":\"FEATURE_NOT_FOUND\"}");
    }

    private static String voucherSteps(String code) {
        return "[{\"keyword\":\"When\",\"text\":\"the customer pays with voucher \\\"" + code + "\\\"\","
                + "\"dataTable\":null,\"docString\":null}]";
    }

    private static String testCase(UUID id, String type, UUID scenarioId, UUID exampleRowId, String name, String tags,
                                   int line, String steps) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"scenarioId\":\"" + scenarioId
                + "\",\"exampleRowId\":" + (exampleRowId == null ? "null" : "\"" + exampleRowId + "\"")
                + ",\"name\":\"" + name + "\",\"tags\":" + tags + ",\"line\":" + line + ",\"steps\":" + steps + "}";
    }

    /**
     * One request the stub received.
     */
    public record Received(String method, String path, String query, String authorization, String requestId) {
    }

    private record Answer(int status, String body) {
    }
}
