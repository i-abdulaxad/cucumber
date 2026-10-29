package org.apigateway.exception;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's error controller (platform-hardening D3, D4). It only turns the error dispatch into an
 * {@link ErrorDispatchException}; {@link GatewayExceptionHandler} renders the D6 Problem. A direct request to the
 * error path is an unrouted path: 404 (only the ERROR dispatch is permitted without a token, so an anonymous direct
 * request is 401 first). Nothing here is forwarded downstream.
 */
@RestController
public class GatewayErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public void error(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.ERROR) {
            throw new ErrorDispatchException(HttpStatus.NOT_FOUND.value(), request.getRequestURI(), null);
        }
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        throw new ErrorDispatchException(
                status instanceof Integer code ? code : HttpStatus.INTERNAL_SERVER_ERROR.value(),
                (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI),
                (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
    }

    /**
     * What reached the error dispatch: the status the container set, the path of the original request as sent, and
     * the exception that escaped, if any. It carries no message, so nothing from the request or the failure can reach
     * the body.
     */
    static final class ErrorDispatchException extends RuntimeException {

        private final int status;
        private final String originalPath;

        ErrorDispatchException(int status, String originalPath, Throwable escaped) {
            super(null, escaped, false, false);
            this.status = status;
            this.originalPath = originalPath;
        }

        int status() {
            return status;
        }

        /** The original request's path as sent (raw), or {@code null} when the container didn't record one. */
        String originalPath() {
            return originalPath;
        }
    }
}
