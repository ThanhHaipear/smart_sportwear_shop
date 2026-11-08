package com.dunghaiquyen.ecommerce.common.exception;

import com.dunghaiquyen.ecommerce.common.response.ApiFieldError;
import com.dunghaiquyen.ecommerce.common.response.ApiResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // API_SPEC_PHASE1.md 2.5/3.1: validation errors are 422, not 400.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiFieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiFieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error("Validation error", errors));
    }

    // Malformed/unreadable request body (bad JSON syntax, wrong charset, etc.) is a
    // client error, not a server failure - was previously falling through to the
    // generic 500 handler below (caught during Phase D testing).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Malformed request body"));
    }

    // A malformed @PathVariable/@RequestParam (e.g. GET /admin/orders/not-a-uuid)
    // fails type conversion before the handler method even runs and used to fall
    // through to the generic 500 handler below - same "client error, not a server
    // failure" reasoning as the unreadable-body case above. Note: @ModelAttribute
    // query objects (e.g. ProductListQuery) are unaffected by this - a bad value
    // there already comes back as a clean 422 via MethodArgumentNotValidException,
    // verified while building Phase N3's product search filters.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        List<ApiFieldError> errors = List.of(new ApiFieldError(ex.getName(), "Invalid value"));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error("Validation error", errors));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Missing required header: " + ex.getHeaderName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error(ex.getMessage()));
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getMessage()));
    }

    // The product-image upload endpoint is the first multipart endpoint in this
    // app - a file over spring.servlet.multipart.max-file-size is rejected by
    // Spring's multipart resolver before any controller code runs, and without
    // this handler would fall through to the generic 500 below. A file that is
    // merely too big for OUR business rule (ProductImageService's own 5MB check)
    // never reaches here at all - it is well under the higher Spring-level
    // ceiling configured in application.yml, so it surfaces as a normal 422
    // BusinessRuleException via handleApiException above instead.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ApiResponse.error("Uploaded file is too large"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Invalid email or password"));
    }

    // Thrown by DaoAuthenticationProvider's pre-auth checks when CustomUserDetails
    // reports the account as locked/disabled (UserStatus.LOCKED) - see AuthService.login.
    @ExceptionHandler({LockedException.class, DisabledException.class})
    public ResponseEntity<ApiResponse<Void>> handleLockedAccount(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Account is locked"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error"));
    }
}
