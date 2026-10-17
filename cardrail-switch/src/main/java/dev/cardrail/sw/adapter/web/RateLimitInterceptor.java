package dev.cardrail.sw.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Answers 429 with a problem detail once a client has used up its requests that change state; reads
 * are not limited. An interceptor rather than a servlet filter, so the CORS headers are already on the
 * response and the console can read the 429.
 */
class RateLimitInterceptor implements HandlerInterceptor {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Set<String> LIMITED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ClientRateLimiter limiter;
    private final ObjectMapper json;

    RateLimitInterceptor(ClientRateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!LIMITED_METHODS.contains(request.getMethod())) {
            return true;
        }
        Optional<Duration> wait = limiter.tryAcquire(clientAddress(request));
        if (wait.isEmpty()) {
            return true;
        }
        long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests from this address; retry after " + seconds + " s.");
        problem.setTitle("Too many requests");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
        return false;
    }

    /**
     * Behind Render's proxy the TCP peer is the proxy, and the client is the first X-Forwarded-For hop.
     * A client can put any address there, so this is a brake on careless load, not a security control.
     */
    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null) {
            String first = forwarded.split(",", 2)[0].strip();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
