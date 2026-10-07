package com.example.processengine.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A long-running transactional process: identity, lifecycle state and a bag of string variables.
 * State and variable changes are synchronized so concurrent callers always see a consistent snapshot.
 */
public final class ProcessInstance {

    private final String processId;
    private final String processType;
    private volatile ProcessState state;
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile String lastError;

    public ProcessInstance(String processId, String processType) {
        this(processId, processType, Instant.now());
    }

    private ProcessInstance(String processId, String processType, Instant createdAt) {
        this.processId = processId;
        this.processType = processType;
        this.state = ProcessState.CREATED;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getProcessId() { return processId; }
    public String getProcessType() { return processType; }
    public ProcessState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastError() { return lastError; }

    public synchronized void setState(ProcessState newState) {
        this.state = newState;
        this.updatedAt = Instant.now();
    }

    public synchronized void setVariable(String key, String value) {
        variables.put(key, value);
        this.updatedAt = Instant.now();
    }

    public synchronized String getVariable(String key) {
        return variables.get(key);
    }

    public synchronized Map<String, String> variablesSnapshot() {
        return Map.copyOf(variables);
    }

    public synchronized void fail(String reason) {
        this.state = ProcessState.FAILED;
        this.lastError = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Compact single-line record stored in the state store:
     * {@code type|state|createdAt|updatedAt|error|k=v,k=v}. Every free-text field is URL-encoded so
     * the separators {@code | , =} can appear inside values without corrupting the record.
     */
    public synchronized String serializeValue() {
        StringBuilder vars = new StringBuilder();
        variables.forEach((k, v) -> {
            if (vars.length() > 0) vars.append(',');
            vars.append(enc(k)).append('=').append(enc(v));
        });
        return String.join("|", enc(processType), state.name(), createdAt.toString(), updatedAt.toString(),
                enc(lastError == null ? "" : lastError), vars.toString());
    }

    public static ProcessInstance deserialize(String processId, String value) {
        String[] parts = value.split("\\|", 6);
        if (parts.length < 6) throw new IllegalArgumentException("corrupt process record for " + processId);
        try {
            ProcessInstance instance = new ProcessInstance(processId, dec(parts[0]), Instant.parse(parts[2]));
            instance.state = ProcessState.valueOf(parts[1]);
            instance.updatedAt = Instant.parse(parts[3]);
            if (!parts[4].isEmpty()) instance.lastError = dec(parts[4]);
            if (!parts[5].isBlank()) {
                for (String kv : parts[5].split(",")) {
                    String[] p = kv.split("=", 2);
                    if (p.length == 2) instance.variables.put(dec(p[0]), dec(p[1]));
                }
            }
            return instance;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("corrupt process record for " + processId, e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized String toString() {
        return "Process{" + processId + ", type=" + processType + ", state=" + state
                + ", vars=" + variables + (lastError != null ? ", error=" + lastError : "") + "}";
    }
}
