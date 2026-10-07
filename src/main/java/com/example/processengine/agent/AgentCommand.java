package com.example.processengine.agent;

import java.util.Map;

/**
 * The parsed, structured result of interpreting a free-text agent
 * instruction — the boundary between "natural-ish language in" and
 * "typed command the engine can execute." Everything past this point in
 * the pipeline is ordinary typed Java; only {@link IntentParser} deals
 * with strings.
 */
public sealed interface AgentCommand permits
        AgentCommand.Pause, AgentCommand.Resume, AgentCommand.Mutate, AgentCommand.Query {

    String processId();

    record Pause(String processId) implements AgentCommand {}

    /** conditionExpr is optional raw text like "inventory > 0"; null means resume unconditionally. */
    record Resume(String processId, String conditionExpr) implements AgentCommand {}

    record Mutate(String processId, Map<String, String> variableUpdates) implements AgentCommand {}

    record Query(String processId) implements AgentCommand {}
}
