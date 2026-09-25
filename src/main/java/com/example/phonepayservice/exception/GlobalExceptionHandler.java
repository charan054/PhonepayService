package com.example.phonepayservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// Every error is plain text so the web page can show it as it is.
@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(MoneyRequestNotFoundException.class)
    public ResponseEntity<String> handleMoneyRequestNotFound(MoneyRequestNotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RecurringPaymentNotFoundException.class)
    public ResponseEntity<String> handleRecurringPaymentNotFound(RecurringPaymentNotFoundException e) {
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

    // malformed request body (bad JSON, wrong type for a field) - a caller mistake, not a server error
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleMalformedBody(HttpMessageNotReadableException e) {
        return reply(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    // e.g. a non-numeric transaction id in the path - Spring would otherwise map this to 400 on its own; kept
    // explicit here only because the catch-all below would otherwise shadow that with a 500.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return reply(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    // an unmapped URL - Spring would otherwise map this to 404 on its own; kept explicit here only because the
    // catch-all below would otherwise shadow that with a 500.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> handleNoResource(NoResourceFoundException e) {
        return reply(HttpStatus.NOT_FOUND, "Not found");
    }

    // Anything else is a bug we didn't anticipate (see also BankGateway.findUser's null-balance check, one
    // example of what used to fall through to here as a bare, unhandled 500). Never let it leak a stack trace
    // or an internal exception message to the caller - log it here, where the exception and stack are both
    // still available, and tell the caller only that something went wrong.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return reply(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
    }
}
