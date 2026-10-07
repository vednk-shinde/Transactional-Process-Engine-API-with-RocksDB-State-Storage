package com.example.processengine.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessInstanceTest {

    @Test
    void serializationRoundTripsEverythingIncludingAwkwardCharacters() {
        ProcessInstance original = new ProcessInstance("p1", "order|fulfil,ment=1");
        original.setVariable("note", "a|b,c=d and unicode é中");
        original.setVariable("empty", "");
        original.setState(ProcessState.PAUSED);

        ProcessInstance copy = ProcessInstance.deserialize("p1", original.serializeValue());

        assertEquals("order|fulfil,ment=1", copy.getProcessType());
        assertEquals(ProcessState.PAUSED, copy.getState());
        assertEquals(original.variablesSnapshot(), copy.variablesSnapshot());
        assertEquals(original.getCreatedAt(), copy.getCreatedAt());
        assertEquals(original.getUpdatedAt(), copy.getUpdatedAt());
    }

    @Test
    void failureReasonIsPersisted() {
        ProcessInstance p = new ProcessInstance("p1", "t");
        p.fail("boom, with | separators");
        ProcessInstance copy = ProcessInstance.deserialize("p1", p.serializeValue());
        assertEquals(ProcessState.FAILED, copy.getState());
        assertEquals("boom, with | separators", copy.getLastError());
        assertTrue(copy.toString().contains("error=boom"));
    }

    @Test
    void corruptRecordsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProcessInstance.deserialize("p1", "garbage"));
        assertThrows(IllegalArgumentException.class,
                () -> ProcessInstance.deserialize("p1", "t|NOT_A_STATE|2024-01-01T00:00:00Z|2024-01-01T00:00:00Z||"));
    }

    @Test
    void variablesSnapshotIsIndependentOfLaterChanges() {
        ProcessInstance p = new ProcessInstance("p1", "t");
        p.setVariable("a", "1");
        var snapshot = p.variablesSnapshot();
        p.setVariable("a", "2");
        assertEquals("1", snapshot.get("a"));
        assertEquals(ProcessState.CREATED, p.getState());
    }
}
