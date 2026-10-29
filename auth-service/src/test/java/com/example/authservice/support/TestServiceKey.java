package com.example.authservice.support;

/**
 * TEST-ONLY internal service keys for C7 (report-service spec D5, AE3). Never used outside tests, and never a real
 * value.
 */
public final class TestServiceKey {

    /** Standard Base64 of the 55-byte ASCII text "auth-service TEST ONLY internal service key, never real". */
    public static final String KEY =
            "YXV0aC1zZXJ2aWNlIFRFU1QgT05MWSBpbnRlcm5hbCBzZXJ2aWNlIGtleSwgbmV2ZXIgcmVhbA==";

    /** Another valid key: standard Base64 of the 38-byte ASCII text "another TEST ONLY 32-byte service key!". */
    public static final String OTHER_KEY = "YW5vdGhlciBURVNUIE9OTFkgMzItYnl0ZSBzZXJ2aWNlIGtleSE=";

    private TestServiceKey() {
    }
}
