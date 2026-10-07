package com.picsou.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TotpRequiredException.class)
    ProblemDetail handleTotpRequired(TotpRequiredException ex) {
        log.info("TOTP required: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(FinaryServiceUnavailableException.class)
    ProblemDetail handleFinaryUnavailable(FinaryServiceUnavailableException ex) {
        log.warn("Finary/Clerk service unavailable: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Finary service is temporarily unavailable. Please try again later.");
    }

    @ExceptionHandler(SyncException.class)
    ProblemDetail handleSync(SyncException ex) {
        log.warn("Sync error: {}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        if (ex.getCode() != null) {
            detail.setProperty("code", ex.getCode());
        }
        return detail;
    }

    // Defense-in-depth: WalletSyncService.sync() already catches WalletRpcException and
    // re-wraps it into a SyncException with a friendly per-chain message. This backstop
    // only fires if some future WalletPort caller forgets to wrap it -- so a bad RPC
    // response can never surface as a raw 500 with a leaked technical message.
    @ExceptionHandler(WalletRpcException.class)
    ProblemDetail handleWalletRpc(WalletRpcException ex) {
        log.warn("Wallet RPC error: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
            "Could not reach the blockchain network. Please try again later.");
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ImportPreviewCapacityException.class)
    ProblemDetail handleImportPreviewCapacity(ImportPreviewCapacityException ex) {
        log.warn("Import preview store has no evictable slot left");
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ImportPreviewCapacityException.MESSAGE);
    }

    @ExceptionHandler(MfaException.class)
    ProblemDetail handleMfa(MfaException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MissingScopeException.class)
    ProblemDetail handleMissingScope(MissingScopeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(com.picsou.service.ReAuthService.ReAuthFailedException.class)
    ProblemDetail handleReAuthFailed(com.picsou.service.ReAuthService.ReAuthFailedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        pd.setTitle("REAUTH_FAILED");
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                FieldError::getField,
                f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid",
                (a, b) -> a
            ));
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setTitle("Validation failed");
        detail.setProperty("errors", errors);
        return ResponseEntity.unprocessableEntity().body(detail);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleGeneric(Exception ex) {
        // Log full exception internally but never expose it to the client
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
        );
    }
}
