package com.example.processengine.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses short, agent-issued instructions ("pause process ORD-42",
 * "resume process ORD-42 if inventory > 0", "set sku=ABC, quantity=3 on
 * process ORD-42") into a structured {@link AgentCommand}.
 *
 * This is intentionally a keyword/regex parser, not an LLM call — the
 * point of this project is to demonstrate the *orchestration* layer (an
 * agent deciding to pause/resume/mutate a live process based on system
 * state), which doesn't require an LLM in the loop to prove out. Swapping
 * this parser for an LLM-based one later is a drop-in replacement: it
 * only needs to keep producing {@link AgentCommand} values.
 */
public final class IntentParser {
    private static final Pattern PAUSE = Pattern.compile("^pause process (\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESUME = Pattern.compile("^resume process (\\S+)(?: if (.+))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MUTATE = Pattern.compile("^set (.+) on process (\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY = Pattern.compile("^(?:query|status of) process (\\S+)$", Pattern.CASE_INSENSITIVE);

    public AgentCommand parse(String instruction) {
        String text = instruction.trim();

        Matcher pause = PAUSE.matcher(text);
        if (pause.matches()) {
            return new AgentCommand.Pause(pause.group(1));
        }

        Matcher resume = RESUME.matcher(text);
        if (resume.matches()) {
            return new AgentCommand.Resume(resume.group(1), resume.group(2));
        }

        Matcher mutate = MUTATE.matcher(text);
        if (mutate.matches()) {
            String assignments = mutate.group(1);
            String processId = mutate.group(2);
            Map<String, String> updates = new LinkedHashMap<>();
            for (String pair : assignments.split(",")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length != 2) {
                    throw new IllegalArgumentException("Malformed assignment: '" + pair + "'");
                }
                updates.put(kv[0].trim(), kv[1].trim());
            }
            return new AgentCommand.Mutate(processId, updates);
        }

        Matcher query = QUERY.matcher(text);
        if (query.matches()) {
            return new AgentCommand.Query(query.group(1));
        }

        throw new IllegalArgumentException("Could not parse intent from instruction: \"" + instruction + "\"");
    }
}
