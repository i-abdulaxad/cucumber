package org.reportservice.security;

/**
 * A bearer token that isn't valid (D5). The message names only the rule that failed, never the token or a claim.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String reason) {
        super(reason);
    }
}
