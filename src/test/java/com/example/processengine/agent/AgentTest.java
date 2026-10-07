package com.example.processengine.agent;

import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessInstance;
import com.example.processengine.core.ProcessNotFoundException;
import com.example.processengine.core.ProcessState;
import com.example.processengine.storage.InMemoryStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {

    private final IntentParser parser = new IntentParser();
    private ProcessEngine engine;
    private AgentOrchestrator agent;

    @BeforeEach
    void setUp() {
        engine = new ProcessEngine(new InMemoryStateStore());
        agent = new AgentOrchestrator(engine);
        engine.create("ORD-1", "order", Map.of("inventory", "0"));
    }

    // ---- IntentParser ----

    @Test
    void parsesEveryCommandShape() {
        assertEquals(new AgentCommand.Pause("ORD-1"), parser.parse("Pause process ORD-1"));
        assertEquals(new AgentCommand.Resume("ORD-1", null), parser.parse("resume process ORD-1"));
        assertEquals(new AgentCommand.Resume("ORD-1", "inventory > 0"),
                parser.parse("resume process ORD-1 if inventory > 0"));
        assertEquals(new AgentCommand.Query("ORD-1"), parser.parse("status of process ORD-1"));
        AgentCommand.Mutate mutate = assertInstanceOf(AgentCommand.Mutate.class,
                parser.parse("  set sku=ABC, qty = 3 on process ORD-1  "));
        assertEquals(Map.of("sku", "ABC", "qty", "3"), mutate.variableUpdates());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete everything", "pause ORD-1", "set a on process X", ""})
    void rejectsUnrecognisedInstructions(String text) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(text));
    }

    // ---- ConditionEvaluator ----

    @ParameterizedTest
    @CsvSource({"inventory > -1,true", "inventory > 0,false", "inventory >= 0,true", "inventory < 1,true",
            "inventory <= -1,false", "inventory == 0,true", "inventory != 0,false", "inventory == 0.0,true"})
    void evaluatesNumericConditions(String expr, boolean expected) {
        assertEquals(expected, ConditionEvaluator.evaluate(expr, engine.find("ORD-1").orElseThrow()));
    }

    @Test
    void stringEqualityWorksButOrderingDoesNot() {
        ProcessInstance p = engine.mutate("ORD-1", Map.of("status", "ready"));
        assertTrue(ConditionEvaluator.evaluate("status == ready", p));
        assertFalse(ConditionEvaluator.evaluate("status != ready", p));
        assertThrows(IllegalArgumentException.class, () -> ConditionEvaluator.evaluate("status > ready", p));
    }

    @Test
    void blankConditionIsUnconditionalAndBadOnesAreRejected() {
        ProcessInstance p = engine.find("ORD-1").orElseThrow();
        assertTrue(ConditionEvaluator.evaluate(null, p));
        assertTrue(ConditionEvaluator.evaluate("  ", p));
        assertThrows(IllegalArgumentException.class, () -> ConditionEvaluator.evaluate("not a condition", p));
        assertThrows(IllegalStateException.class, () -> ConditionEvaluator.evaluate("missing > 1", p));
    }

    // ---- AgentOrchestrator ----

    @Test
    void conditionalResumeIsRefusedUntilStateChanges() {
        agent.execute("pause process ORD-1");
        assertTrue(agent.execute("resume process ORD-1 if inventory > 0").startsWith("Resume refused"));
        assertEquals(ProcessState.PAUSED, engine.find("ORD-1").orElseThrow().getState());

        agent.execute("set inventory=5 on process ORD-1");
        assertTrue(agent.execute("resume process ORD-1 if inventory > 0").contains("met"));
        assertEquals(ProcessState.RUNNING, engine.find("ORD-1").orElseThrow().getState());
    }

    @Test
    void unconditionalResumeAndQuery() {
        agent.execute("pause process ORD-1");
        assertTrue(agent.execute("resume process ORD-1").contains("unconditional"));
        assertTrue(agent.execute("query process ORD-1").contains("state=RUNNING"));
    }

    @Test
    void unknownProcessIsReportedAsNotFound() {
        assertThrows(ProcessNotFoundException.class, () -> agent.execute("pause process NOPE"));
        assertThrows(ProcessNotFoundException.class, () -> agent.execute("resume process NOPE"));
        assertThrows(ProcessNotFoundException.class, () -> agent.execute("query process NOPE"));
    }
}
