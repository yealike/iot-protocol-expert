package top.yealike.ipe.protocol.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class HttpTelemetryConfiguration {
    @Bean
    public OncePerRequestFilter apiRequestLogFilter() {
        return new OncePerRequestFilter() {
            private final Logger log = LoggerFactory.getLogger("top.yealike.ipe.http");

            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith("/api/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                long start = System.nanoTime();
                boolean failed = false;
                try {
                    chain.doFilter(request, response);
                } catch (ServletException | IOException | RuntimeException e) {
                    failed = true;
                    throw e;
                } finally {
                    int status = failed ? 500 : response.getStatus();
                    // Use the route template, never raw URLs, query strings or request/response bodies.
                    Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    var event = status >= 500 ? log.atError() : status >= 400 ? log.atWarn() :
                                                                request.getMethod().equals("GET") ? log.atDebug() : log.atInfo();
                    event.addKeyValue("event.name", "http.request.completed")
                            .addKeyValue("http.request.method", request.getMethod())
                            .addKeyValue("http.route", route == null ? "unmatched" : route.toString())
                            .addKeyValue("http.response.status_code", status)
                            .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000.0)
                            .addKeyValue("outcome", status >= 400 ? "error" : "success")
                            .log("API request completed");
                }
            }
        };
    }
}
