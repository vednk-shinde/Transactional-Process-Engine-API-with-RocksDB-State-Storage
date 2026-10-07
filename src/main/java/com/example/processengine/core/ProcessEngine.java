package com.example.processengine.core;

import com.example.processengine.storage.StateStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * State machine over {@link ProcessInstance}s: CREATED/RUNNING &lt;-&gt; PAUSED, then COMPLETED or FAILED.
 *
 * <p>Every transition on one process is serialized by a per-process lock, so concurrent callers can
 * never both win a create, or interleave a pause with a resume. Different processes never contend.
 * Each accepted transition is written through to the {@link StateStore} before it returns, and an
 * engine restarted over the same store sees every process again.
 */
public final class ProcessEngine {

    private static final String KEY_PREFIX = "process:";

    private final StateStore store;
    private final Map<String, ProcessInstance> liveInstances = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ProcessEngine(StateStore store) {
        this.store = store;
    }

    private Object lock(String processId) {
        return locks.computeIfAbsent(processId, id -> new Object());
    }

    public ProcessInstance create(String processId, String processType) {
        return create(processId, processType, Map.of());
    }

    public ProcessInstance create(String processId, String processType, Map<String, String> initialVariables) {
        if (processId == null || processId.isBlank() || processType == null || processType.isBlank()) {
            throw new IllegalArgumentException("processId and processType must be non-blank");
        }
        synchronized (lock(processId)) {
            if (find(processId).isPresent()) {
                throw new DuplicateProcessException(processId);
            }
            ProcessInstance instance = new ProcessInstance(processId, processType);
            initialVariables.forEach(instance::setVariable);
            instance.setState(ProcessState.RUNNING);
            persist(instance);
            return instance;
        }
    }

    public ProcessInstance pause(String processId) {
        synchronized (lock(processId)) {
            ProcessInstance instance = require(processId);
            if (instance.getState() != ProcessState.RUNNING) {
                throw new IllegalStateException("Cannot pause process in state " + instance.getState());
            }
            instance.setState(ProcessState.PAUSED);
            persist(instance);
            return instance;
        }
    }

    public ProcessInstance resume(String processId, boolean conditionMet) {
        synchronized (lock(processId)) {
            ProcessInstance instance = require(processId);
            if (instance.getState() != ProcessState.PAUSED) {
                throw new IllegalStateException("Cannot resume process in state " + instance.getState());
            }
            if (!conditionMet) {
                throw new IllegalStateException("Resume condition not met for process " + processId);
            }
            instance.setState(ProcessState.RUNNING);
            persist(instance);
            return instance;
        }
    }

    public ProcessInstance mutate(String processId, Map<String, String> variableUpdates) {
        synchronized (lock(processId)) {
            ProcessInstance instance = require(processId);
            if (isTerminal(instance)) {
                throw new IllegalStateException("Cannot mutate a terminal process (state=" + instance.getState() + ")");
            }
            variableUpdates.forEach(instance::setVariable);
            persist(instance);
            return instance;
        }
    }

    public ProcessInstance complete(String processId) {
        synchronized (lock(processId)) {
            ProcessInstance instance = require(processId);
            if (isTerminal(instance)) {
                throw new IllegalStateException("Process already terminal (state=" + instance.getState() + ")");
            }
            instance.setState(ProcessState.COMPLETED);
            persist(instance);
            return instance;
        }
    }

    public ProcessInstance fail(String processId, String reason) {
        synchronized (lock(processId)) {
            ProcessInstance instance = require(processId);
            if (isTerminal(instance)) {
                throw new IllegalStateException("Process already terminal (state=" + instance.getState() + ")");
            }
            instance.fail(reason);
            persist(instance);
            return instance;
        }
    }

    public Optional<ProcessInstance> find(String processId) {
        ProcessInstance live = liveInstances.get(processId);
        if (live != null) return Optional.of(live);
        return store.get(KEY_PREFIX + processId).map(v -> {
            ProcessInstance loaded = ProcessInstance.deserialize(processId, v);
            ProcessInstance raced = liveInstances.putIfAbsent(processId, loaded);
            return raced != null ? raced : loaded;
        });
    }

    /** Ids of every process known to the store, including ones from before a restart. */
    public List<String> listProcessIds() {
        return store.listKeys(KEY_PREFIX).stream().map(k -> k.substring(KEY_PREFIX.length())).toList();
    }

    private boolean isTerminal(ProcessInstance instance) {
        return instance.getState() == ProcessState.COMPLETED || instance.getState() == ProcessState.FAILED;
    }

    private ProcessInstance require(String processId) {
        return find(processId).orElseThrow(() -> new ProcessNotFoundException(processId));
    }

    private void persist(ProcessInstance instance) {
        liveInstances.put(instance.getProcessId(), instance);
        store.put(KEY_PREFIX + instance.getProcessId(), instance.serializeValue());
    }
}
