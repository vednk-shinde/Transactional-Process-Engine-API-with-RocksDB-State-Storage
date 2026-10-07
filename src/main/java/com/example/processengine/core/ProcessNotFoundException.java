package com.example.processengine.core;

/** Raised when an operation targets a process id that does not exist. Maps to HTTP 404 / gRPC NOT_FOUND. */
public final class ProcessNotFoundException extends IllegalArgumentException {
    public ProcessNotFoundException(String processId) {
        super("No such process: " + processId);
    }
}
