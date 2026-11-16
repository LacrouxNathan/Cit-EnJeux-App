package fr.citeenjeux.cej.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.multipart.MaxUploadSizeExceededException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> invalidLibraryInput(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<?> duplicateEmail() {
        return ResponseEntity.status(409).body(Map.of("code", "EMAIL_ALREADY_USED"));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> businessError(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",
            error.getReason() == null ? "REQUEST_REJECTED" : error.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
