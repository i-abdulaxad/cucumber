package org.apigateway.configuration;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apigateway.exception.GatewayExceptionHandler;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Aborts the client connection when a downstream call failed after the response was committed (platform-hardening
 * D11, E14). {@link GatewayExceptionHandler} writes nothing more and flags the request; this Tomcat context valve then
 * closes the connection once the application has returned, so the client sees a truncated response (no terminating
 * chunk, fewer bytes than {@code Content-Length}) instead of a body that looks complete. Without it, Tomcat 11 would
 * include the error page into the committed body and log an ERROR. The valve acts on flagged requests only.
 */
@Configuration(proxyBeanMethods = false)
public class CommittedResponseAbortConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> committedResponseAbort() {
        return factory -> factory.addContextCustomizers(context ->
                context.getPipeline().addValve(new AbortFlaggedConnectionValve()));
    }

    static final class AbortFlaggedConnectionValve extends ValveBase {

        AbortFlaggedConnectionValve() {
            super(true);
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            getNext().invoke(request, response);
            if (Boolean.TRUE.equals(request.getAttribute(GatewayExceptionHandler.ABORT_CONNECTION_ATTRIBUTE))
                    && response.isCommitted()) {
                response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
            }
        }
    }
}
