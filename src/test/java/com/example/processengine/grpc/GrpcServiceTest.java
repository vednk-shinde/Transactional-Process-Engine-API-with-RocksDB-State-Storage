package com.example.processengine.grpc;

import com.example.processengine.agent.AgentOrchestrator;
import com.example.processengine.core.ProcessEngine;
import com.example.processengine.grpc.v1.AgentInstructionRequest;
import com.example.processengine.grpc.v1.CreateProcessRequest;
import com.example.processengine.grpc.v1.GetProcessRequest;
import com.example.processengine.grpc.v1.MutateProcessRequest;
import com.example.processengine.grpc.v1.ProcessEngineServiceGrpc;
import com.example.processengine.grpc.v1.ProcessIdRequest;
import com.example.processengine.grpc.v1.ProcessInstanceResponse;
import com.example.processengine.grpc.v1.ProcessState;
import com.example.processengine.grpc.v1.ResumeProcessRequest;
import com.example.processengine.storage.InMemoryStateStore;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcServiceTest {

    private Server server;
    private ManagedChannel channel;
    private ProcessEngineServiceGrpc.ProcessEngineServiceBlockingStub client;

    @BeforeEach
    void start() throws Exception {
        String name = UUID.randomUUID().toString();
        ProcessEngine engine = new ProcessEngine(new InMemoryStateStore());
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new ProcessEngineGrpcService(engine, new AgentOrchestrator(engine))).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ProcessEngineServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private ProcessInstanceResponse create(String id) {
        return client.createProcess(CreateProcessRequest.newBuilder().setProcessId(id).setProcessType("order")
                .putInitialVariables("inventory", "0").build());
    }

    private static Status.Code codeOf(Runnable call) {
        return assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode();
    }

    @Test
    void lifecycleThroughTheV1Contract() {
        ProcessInstanceResponse created = create("p1");
        assertEquals(ProcessState.PROCESS_STATE_RUNNING, created.getState());
        assertEquals("0", created.getVariablesMap().get("inventory"));

        assertEquals(ProcessState.PROCESS_STATE_PAUSED,
                client.pauseProcess(ProcessIdRequest.newBuilder().setProcessId("p1").build()).getState());
        assertEquals("5", client.mutateProcess(MutateProcessRequest.newBuilder().setProcessId("p1")
                .putVariableUpdates("inventory", "5").build()).getVariablesMap().get("inventory"));
        assertEquals(ProcessState.PROCESS_STATE_RUNNING, client.resumeProcess(ResumeProcessRequest.newBuilder()
                .setProcessId("p1").setConditionExpr("inventory > 0").build()).getState());
        assertEquals(ProcessState.PROCESS_STATE_RUNNING,
                client.getProcess(GetProcessRequest.newBuilder().setProcessId("p1").build()).getState());
    }

    @Test
    void conditionalResumeIsRefusedWithFailedPrecondition() {
        create("p1");
        client.pauseProcess(ProcessIdRequest.newBuilder().setProcessId("p1").build());
        assertEquals(Status.Code.FAILED_PRECONDITION, codeOf(() -> client.resumeProcess(
                ResumeProcessRequest.newBuilder().setProcessId("p1").setConditionExpr("inventory > 0").build())));
        // an unconditional resume (field absent) is allowed
        assertEquals(ProcessState.PROCESS_STATE_RUNNING,
                client.resumeProcess(ResumeProcessRequest.newBuilder().setProcessId("p1").build()).getState());
    }

    @Test
    void errorsUseCanonicalGrpcStatusCodes() {
        create("p1");
        assertEquals(Status.Code.ALREADY_EXISTS, codeOf(() -> create("p1")));
        assertEquals(Status.Code.NOT_FOUND, codeOf(() ->
                client.getProcess(GetProcessRequest.newBuilder().setProcessId("ghost").build())));
        assertEquals(Status.Code.NOT_FOUND, codeOf(() ->
                client.resumeProcess(ResumeProcessRequest.newBuilder().setProcessId("ghost").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() ->
                client.createProcess(CreateProcessRequest.newBuilder().setProcessId("").setProcessType("t").build())));
        assertEquals(Status.Code.FAILED_PRECONDITION, codeOf(() ->
                client.resumeProcess(ResumeProcessRequest.newBuilder().setProcessId("p1").build()))); // not paused
    }

    @Test
    void agentInstructionsWorkOverGrpc() {
        create("p1");
        String result = client.executeAgentInstruction(
                AgentInstructionRequest.newBuilder().setInstruction("pause process p1").build()).getResult();
        assertTrue(result.startsWith("Paused p1"));
        assertEquals(Status.Code.INVALID_ARGUMENT, codeOf(() -> client.executeAgentInstruction(
                AgentInstructionRequest.newBuilder().setInstruction("gibberish").build())));
    }

    @Test
    void failedProcessesCarryTheLastError() {
        ProcessEngine engine = new ProcessEngine(new InMemoryStateStore());
        engine.create("p1", "t");
        engine.fail("p1", "boom");
        ProcessInstanceResponse response = ProcessEngineGrpcService.toResponse(engine.find("p1").orElseThrow());
        assertEquals(ProcessState.PROCESS_STATE_FAILED, response.getState());
        assertEquals("boom", response.getLastError());
        assertEquals(Map.of(), response.getVariablesMap());
    }
}
