package com.example.processengine.agent;

import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessInstance;

/**
 * The "intelligent agent processing tool" from the resume bullet: takes
 * a free-text instruction, parses intent, and dynamically pauses,
 * mutates, or resumes an active process based on its current state
 * variables — the resume case is the one that's actually conditional and
 * interesting; pause/mutate/query are comparatively mechanical.
 */
public final class AgentOrchestrator {
    private final IntentParser parser;
    private final ProcessEngine engine;

    public AgentOrchestrator(ProcessEngine engine) {
        this.engine = engine;
        this.parser = new IntentParser();
    }

    /** Executes a natural-language-ish instruction end to end and returns a short, agent-facing status string. */
    public String execute(String instruction) {
        AgentCommand command = parser.parse(instruction);
        return switch (command) {
            case AgentCommand.Pause c -> {
                ProcessInstance instance = engine.pause(c.processId());
                yield "Paused " + instance.getProcessId() + " (was RUNNING).";
            }
            case AgentCommand.Resume c -> {
                ProcessInstance current = engine.find(c.processId())
                        .orElseThrow(() -> new com.example.processengine.core.ProcessNotFoundException(c.processId()));
                boolean conditionMet = ConditionEvaluator.evaluate(c.conditionExpr(), current);
                if (!conditionMet) {
                    yield "Resume refused for " + c.processId() + ": condition '" + c.conditionExpr()
                            + "' not met (current value: " + extractVariableName(c.conditionExpr()).map(current::getVariable).orElse("n/a") + ").";
                }
                ProcessInstance resumed = engine.resume(c.processId(), true);
                yield "Resumed " + resumed.getProcessId()
                        + (c.conditionExpr() != null ? " (condition '" + c.conditionExpr() + "' met)." : " (unconditional).");
            }
            case AgentCommand.Mutate c -> {
                ProcessInstance instance = engine.mutate(c.processId(), c.variableUpdates());
                yield "Updated " + instance.getProcessId() + " variables: " + instance.variablesSnapshot();
            }
            case AgentCommand.Query c -> {
                ProcessInstance instance = engine.find(c.processId())
                        .orElseThrow(() -> new com.example.processengine.core.ProcessNotFoundException(c.processId()));
                yield instance.toString();
            }
        };
    }

    private static java.util.Optional<String> extractVariableName(String conditionExpr) {
        if (conditionExpr == null) return java.util.Optional.empty();
        String[] parts = conditionExpr.split("[><=!]", 2);
        return parts.length > 0 ? java.util.Optional.of(parts[0].trim()) : java.util.Optional.empty();
    }
}
