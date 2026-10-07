package com.example.processengine.core;

/** Raised when creating a process whose id is already taken. Maps to HTTP 409 / gRPC ALREADY_EXISTS. */
public final class DuplicateProcessException extends IllegalStateException {
    public DuplicateProcessException(String processId) {
        super("Process " + processId + " already exists");
    }
}
