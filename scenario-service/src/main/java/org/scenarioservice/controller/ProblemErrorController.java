package org.scenarioservice.controller;

import org.scenarioservice.exception.ErrorDispatchException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Boot's error controller (platform-hardening D3, D4). It only turns the error dispatch into an
 * {@link ErrorDispatchException}; the advice renders the Problem. A direct request to the error path is an unrouted
 * path: 404 (only the ERROR dispatch is permitted without a token, so an anonymous direct request is 401 first). It
 * never calls the access check.
 */
@RestController
public class ProblemErrorController implements ErrorController {

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
}
