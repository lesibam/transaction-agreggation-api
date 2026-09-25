package za.co.evilcorp.transact.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.NoSuchElementException;

/**
 * Must outrank Spring Boot's auto-configured ProblemDetailsExceptionHandler
 * (order 0) so RFC 7807 bodies always include our type + traceId extensions.
 * {@code Ordered.HIGHEST_PRECEDENCE} (Integer.MIN_VALUE) is used specifically
 * because it's the one value no future default from Boot can silently tie or
 * beat without an equally explicit, visible change on Boot's side.
 *
 * In practice this class's own {@code Exception.class} catch-all below means
 * it matches every exception type and is always selected before Boot's
 * handler is ever consulted, regardless of the exact @Order value - Spring
 * picks the first advice bean (in order) that has *any* matching handler
 * method, not the most specific match across all beans. That's exactly why
 * HttpRequestMethodNotSupportedException/HttpMediaTypeNotSupportedException/
 * NoResourceFoundException needed their own explicit handlers here (fixed
 * 2026-09-25, M-04): without them, the catch-all silently turned a wrong
 * HTTP method, an unsupported content type, or an unmapped route into a
 * generic 500 instead of the correct 405/415/404 - the real bug this
 * ordering coupling was hiding.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final String ERROR_BASE = "https://api.transact.evilcorp.za/errors/";

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class,
            NumberFormatException.class,
            MethodArgumentTypeMismatchException.class,
            TypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "invalid-request", "Bad Request", 400,
                detailOrDefault(ex, "Invalid request."), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "forbidden", "Forbidden", 403,
                detailOrDefault(ex, "You do not have permission to access this resource."), request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "not-found", "Not Found", 404,
                detailOrDefault(ex, "Resource not found."), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "conflict", "Conflict", 409,
                "The request conflicts with the current state of the resource.", request);
    }

    // These three are handled explicitly because the Exception.class catch-all
    // below would otherwise swallow them into a generic 500 - they matched no
    // handler method here before this fix, so the catch-all's broad match won
    // regardless of this class's @Order relative to Boot's own
    // ProblemDetailsExceptionHandler (see the class Javadoc and
    // docs/principal_engineer_review_report.md M-04).
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "method-not-allowed", "Method Not Allowed", 405,
                detailOrDefault(ex, "This method is not supported for this resource."), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "unsupported-media-type", "Unsupported Media Type", 415,
                detailOrDefault(ex, "This media type is not supported for this resource."), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "not-found", "Not Found", 404,
                "No route matches this request.", request);
    }

    // Same "nothing matched" outcome as NoResourceFoundException above, but a
    // different exception class: NoResourceFoundException is thrown by the
    // default catch-all /** static-resource handler, which only exists while
    // spring.web.resources.add-mappings is true. This app turns that off (see
    // UiResourceConfig/DocsResourceConfig - the toggle for /ui, /docs would
    // otherwise leak through Boot's unconditional default mapping), so an
    // unmatched request never reaches a resource handler at all and
    // DispatcherServlet raises this instead. Both must produce the same 404,
    // not let one silently fall through to the 500 catch-all below.
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) {
        return problem(ERROR_BASE + "not-found", "Not Found", 404,
                "No route matches this request.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(ERROR_BASE + "internal", "Internal Server Error", 500,
                "An unexpected error occurred. Please try again later.", request);
    }

    private ResponseEntity<ProblemDetail> problem(
            String type, String title, int status, String detail, HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(URI.create(type));
        body.setTitle(title);
        body.setDetail(detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("traceId", MDC.get("correlationId"));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private String detailOrDefault(Exception ex, String fallback) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? fallback : message.trim();
    }
}
