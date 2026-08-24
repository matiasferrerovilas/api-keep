package api.m2.file.configuration;

import api.m2.file.exceptions.BusinessException;
import api.m2.file.exceptions.EntityAlreadyExistsException;
import api.m2.file.exceptions.EntityNotFoundException;
import api.m2.file.exceptions.ErrorResponse;
import api.m2.file.exceptions.PermissionDeniedException;
import api.m2.file.exceptions.ServiceException;
import jakarta.persistence.EntityExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class ErrorHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        var details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.BAD_REQUEST.value()),
                "One or more fields are invalid",
                details
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<api.m2.file.exceptions.ErrorResponse> handleApiException(MethodArgumentTypeMismatchException ex) {
        log.error("MethodArgumentTypeMismatchException", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.BAD_REQUEST.value()),
                "Parameter type mismatch",
                String.format("The value '%s' mismatch the type '%s' of parameter '%s'",
                        ex.getValue(),
                        ex.getParameter().getParameterType(),
                        ex.getParameter().getParameterName())
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElementException(NoSuchElementException ex) {
        log.info("The requested resource was not found", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.NOT_FOUND.value()),
                "Resource not found",
                "The requested resource was not found"
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDeniedException(PermissionDeniedException ex) {
        log.info("No tiene permisos para realizar la tarea", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.FORBIDDEN.value()),
                "Permisos Insuficiente",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.BAD_REQUEST.value()),
                "Bad Request",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ErrorResponse> handleServiceException(ServiceException ex) {
        log.error("Internal service error: {}", ex.getMessage(), ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value()),
                "Internal Server Error",
                "An internal error occurred"
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.BAD_REQUEST.value()),
                "Bad Request",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        var errorResponse = new ErrorResponse(
                String.valueOf(statusCode.value()),
                "Generic exception handling",
                ex.getLocalizedMessage()
        );

        return ResponseEntity.status(statusCode)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex) {
        log.error(ex.getMessage(), ex);

        var errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.name(),
                "Unexpected Error",
                "Check logs for more details"
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<ErrorResponse> handleEntityExistsException(EntityExistsException ex) {
        log.error("Entidad already in data base", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.BAD_REQUEST.value()),
                "BAD_REQUEST",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFoundException(EntityNotFoundException ex) {
        log.warn("Entidad no encontrada: {}", ex.getMessage());

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.NOT_FOUND.value()),
                "NOT_FOUND",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(EntityAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEntityAlreadyExistsException(EntityAlreadyExistsException ex) {
        log.warn("Entidad ya existe: {}", ex.getMessage());

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.CONFLICT.value()),
                "CONFLICT",
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        // Sin este handler explícito, un @PreAuthorize que rechaza (ej. hasRole('ADMIN') en
        // UserSharingService/SharingService) caía en el catch-all genérico de más abajo y
        // devolvía 500 en vez de 403 — el mismo tipo de error que causaba el fallo de permisos,
        // no lo distinguía.
        log.info("Access denied", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.FORBIDDEN.value()),
                "Permisos Insuficiente",
                ex.getMessage() != null ? ex.getMessage() : "No tenés permisos para realizar esta acción"
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        log.info("Unauthorized access attempt", ex);

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.UNAUTHORIZED.value()),
                "Unauthorized",
                ex.getMessage() != null ? ex.getMessage() : "Authentication required"
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());

        var errorResponse = new ErrorResponse(
                String.valueOf(HttpStatus.CONFLICT.value()),
                "Conflict",
                "La operación viola restricciones de integridad de datos"
        );

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errorResponse);
    }
}
