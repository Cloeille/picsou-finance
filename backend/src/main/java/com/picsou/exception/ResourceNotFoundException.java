package com.picsou.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException account(Long id) {
        return new ResourceNotFoundException("Account not found");
    }

    public static ResourceNotFoundException goal(Long id) {
        return new ResourceNotFoundException("Goal not found");
    }

    public static ResourceNotFoundException requisition(String requisitionId) {
        return new ResourceNotFoundException("Requisition not found");
    }

    public static ResourceNotFoundException transaction(Long id) {
        return new ResourceNotFoundException("Transaction not found");
    }

    public static ResourceNotFoundException category(Long id) {
        return new ResourceNotFoundException("Category not found");
    }

    public static ResourceNotFoundException rule(Long id) {
        return new ResourceNotFoundException("Categorization rule not found");
    }

    public static ResourceNotFoundException recurringSeries(Long id) {
        return new ResourceNotFoundException("Recurring series not found");
    }
}
