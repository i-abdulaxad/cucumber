package org.reportservice.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The spec's CI sample (glossary): Cucumber JSON of two features. Payment ({@code @checkout}) has three background and
 * scenario pairs; each background step "Given a basket" passed in 1,000,000 ns. "Pay by card" (line 12,
 * {@code @checkout @smoke}): When 2,000,000 ns, Then 3,000,000 ns, an after hook 500,000 ns. "Pay with voucher
 * SAVE10" (line 30) passes; "Pay with voucher OLD5" (line 31) fails its When with "expected rejected but was
 * accepted" and skips its Then. Address: "Change address" (line 5) with one pending step. {@link #tree()} returns a
 * fresh copy that a test may change.
 */
public final class CiSample {

    public static final String JSON = """
            [
              {
                "uri": "features/checkout/payment.feature",
                "id": "payment",
                "keyword": "Feature",
                "name": "Payment",
                "line": 2,
                "tags": [{"name": "@checkout", "line": 1}],
                "elements": [
                  {"type": "background", "keyword": "Background", "name": "", "line": 4,
                   "steps": [{"keyword": "Given ", "name": "a basket", "line": 5,
                              "match": {"location": "Steps.basket()"},
                              "result": {"status": "passed", "duration": 1000000}}]},
                  {"type": "scenario", "keyword": "Scenario", "id": "payment;pay-by-card", "name": "Pay by card",
                   "line": 12, "start_timestamp": "2026-10-29T09:30:00.000Z",
                   "tags": [{"name": "@checkout"}, {"name": "@smoke"}],
                   "steps": [
                     {"keyword": "When ", "name": "the customer pays by card", "line": 13,
                      "result": {"status": "passed", "duration": 2000000}},
                     {"keyword": "Then ", "name": "the receipt is", "line": 14,
                      "result": {"status": "passed", "duration": 3000000}}],
                   "after": [{"match": {"location": "Hooks.after()"},
                              "result": {"status": "passed", "duration": 500000}}]},
                  {"type": "background", "keyword": "Background", "name": "", "line": 4,
                   "steps": [{"keyword": "Given ", "name": "a basket", "line": 5,
                              "result": {"status": "passed", "duration": 1000000}}]},
                  {"type": "scenario", "keyword": "Scenario Outline", "name": "Pay with voucher SAVE10", "line": 30,
                   "tags": [{"name": "@checkout"}, {"name": "@voucher"}],
                   "steps": [
                     {"keyword": "When ", "name": "the customer pays with voucher \\"SAVE10\\"", "line": 22,
                      "result": {"status": "passed", "duration": 400000}},
                     {"keyword": "Then ", "name": "the result is \\"confirmed\\"", "line": 23,
                      "result": {"status": "passed", "duration": 300000}}]},
                  {"type": "background", "keyword": "Background", "name": "", "line": 4,
                   "steps": [{"keyword": "Given ", "name": "a basket", "line": 5,
                              "result": {"status": "passed", "duration": 1000000}}]},
                  {"type": "scenario", "keyword": "Scenario Outline", "name": "Pay with voucher OLD5", "line": 31,
                   "tags": [{"name": "@checkout"}, {"name": "@voucher"}],
                   "steps": [
                     {"keyword": "When ", "name": "the customer pays with voucher \\"OLD5\\"", "line": 22,
                      "result": {"status": "failed", "duration": 700000,
                                 "error_message": "expected rejected but was accepted"}},
                     {"keyword": "Then ", "name": "the result is \\"rejected\\"", "line": 23,
                      "result": {"status": "skipped"}}]}
                ]
              },
              {
                "uri": "features/checkout/address.feature",
                "name": "Address",
                "elements": [
                  {"type": "scenario", "name": "Change address", "line": 5,
                   "steps": [{"keyword": "When ", "name": "the customer changes the address", "line": 6,
                              "result": {"status": "pending", "duration": 100000}}]}
                ]
              }
            ]
            """;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CiSample() {
    }

    /**
     * A fresh, changeable tree of the sample.
     */
    public static ArrayNode tree() {
        return (ArrayNode) MAPPER.readTree(JSON);
    }

    public static String json(JsonNode tree) {
        return MAPPER.writeValueAsString(tree);
    }
}
