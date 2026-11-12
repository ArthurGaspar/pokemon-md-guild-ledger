package dev.arthurgaspar.guildledger.common;

import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handle(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "validation", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handle(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "validation", "Request body is missing or malformed");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handle(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-change",
                "Someone changed this at the same time. Reload and try again.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return pd;
    }
}
