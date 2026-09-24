package com.example.phonepayservice.exception;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

// Every error is plain text so the web page can show it as it is.
@ControllerAdvice
public class GlobalExceptionHandler {
    private static ResponseEntity<String> reply(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(message);
    }

    // not logged in, or the login has expired
    @ExceptionHandler(UserNotRegisteredException.class)
    public ResponseEntity<String> handleNotLoggedIn(UserNotRegisteredException e) {
        return reply(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(UserNotExistException.class)
    public ResponseEntity<String> handleUserNotExist(UserNotExistException e) {
        return reply(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // wrong PIN, or no such phone number - see InvalidCredentialsException
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<String> handleInvalidCredentials(InvalidCredentialsException e) {
        return reply(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<String> handleAccountLocked(AccountLockedException e) {
        return reply(HttpStatus.LOCKED, e.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<String> handleTransactionNotFound(TransactionNotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PayeeNotFoundException.class)
    public ResponseEntity<String> handlePayeeNotFound(PayeeNotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // bad input
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<String> handleInvalidRequest(InvalidRequestException e) {
        return reply(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // the bank refused, for example "Insufficient Funds"
    @ExceptionHandler(BalanceException.class)
    public ResponseEntity<String> handleBalance(BalanceException e) {
        return reply(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return reply(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(BankUnavailableException.class)
    public ResponseEntity<String> handleBankUnavailable(BankUnavailableException e) {
        return reply(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<String> handleTooManyRequests(TooManyRequestsException e) {
        return reply(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    // the payment did not complete; the message says what happened to the money
    @ExceptionHandler(TransferFailedException.class)
    public ResponseEntity<String> handleTransferFailed(TransferFailedException e) {
        return reply(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    // two requests raced (the bank asked for a retry, or a unique constraint stopped a duplicate): retrying is safe
    @ExceptionHandler({BankConflictException.class, ConcurrencyFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<String> handleConflict(RuntimeException e) {
        return reply(HttpStatus.CONFLICT, "Another request changed the same data at the same time. Please retry.");
    }
}
