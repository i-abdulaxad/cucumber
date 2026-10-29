package org.apigateway.configuration;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.apigateway.exception.GatewayExceptionHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * platform-hardening D11, E14: the valve closes the connection only for a request the exception handler flagged after
 * the response was committed; every other request, including a flagged one that isn't committed, is left alone.
 */
class CommittedResponseAbortValveTest {

    @ParameterizedTest(name = "flagged={0}, committed={1}: connection closed={2}")
    @CsvSource({
            "true, true, true",
            "true, false, false",
            "false, true, false",
            "false, false, false"})
    @Tag("AC-6.1")
    void closesOnlyFlaggedCommittedResponses(boolean flagged, boolean committed, boolean closed) throws Exception {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        org.apache.coyote.Response coyoteResponse = mock(org.apache.coyote.Response.class);
        when(request.getAttribute(GatewayExceptionHandler.ABORT_CONNECTION_ATTRIBUTE))
                .thenReturn(flagged ? Boolean.TRUE : null);
        when(response.isCommitted()).thenReturn(committed);
        when(response.getCoyoteResponse()).thenReturn(coyoteResponse);
        Valve next = mock(Valve.class);
        CommittedResponseAbortConfiguration.AbortFlaggedConnectionValve valve =
                new CommittedResponseAbortConfiguration.AbortFlaggedConnectionValve();
        valve.setNext(next);

        valve.invoke(request, response);

        verify(next).invoke(request, response);
        verify(coyoteResponse, closed ? times(1) : never()).action(eq(ActionCode.CLOSE_NOW), any());
    }
}
