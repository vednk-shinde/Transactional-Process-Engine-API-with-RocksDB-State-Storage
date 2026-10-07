package com.example.processengine.grpc;

import com.example.processengine.agent.AgentOrchestrator;
import com.example.processengine.agent.ConditionEvaluator;
import com.example.processengine.core.DuplicateProcessException;
import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessInstance;
import com.example.processengine.core.ProcessNotFoundException;
import com.example.processengine.grpc.v1.AgentInstructionRequest;
import com.example.processengine.grpc.v1.AgentInstructionResponse;
import com.example.processengine.grpc.v1.CreateProcessRequest;
import com.example.processengine.grpc.v1.GetProcessRequest;
import com.example.processengine.grpc.v1.MutateProcessRequest;
import com.example.processengine.grpc.v1.ProcessEngineServiceGrpc;
import com.example.processengine.grpc.v1.ProcessIdRequest;
import com.example.processengine.grpc.v1.ProcessInstanceResponse;
import com.example.processengine.grpc.v1.ResumeProcessRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.function.Supplier;

/** gRPC adapter over {@link ProcessEngine}: the same operations as the REST API, using the v1 proto contract. */
public final class ProcessEngineGrpcService extends ProcessEngineServiceGrpc.ProcessEngineServiceImplBase {

    private final ProcessEngine engine;
    private final AgentOrchestrator orchestrator;

    public ProcessEngineGrpcService(ProcessEngine engine, AgentOrchestrator orchestrator) {
        this.engine = engine;
        this.orchestrator = orchestrator;
    }

    @Override
    public void createProcess(CreateProcessRequest request, StreamObserver<ProcessInstanceResponse> out) {
        reply(out, () -> toResponse(engine.create(
                request.getProcessId(), request.getProcessType(), request.getInitialVariablesMap())));
    }

    @Override
    public void getProcess(GetProcessRequest request, StreamObserver<ProcessInstanceResponse> out) {
        reply(out, () -> toResponse(engine.find(request.getProcessId())
                .orElseThrow(() -> new ProcessNotFoundException(request.getProcessId()))));
    }

    @Override
    public void pauseProcess(ProcessIdRequest request, StreamObserver<ProcessInstanceResponse> out) {
        reply(out, () -> toResponse(engine.pause(request.getProcessId())));
    }

    @Override
    public void resumeProcess(ResumeProcessRequest request, StreamObserver<ProcessInstanceResponse> out) {
        reply(out, () -> {
            ProcessInstance current = engine.find(request.getProcessId())
                    .orElseThrow(() -> new ProcessNotFoundException(request.getProcessId()));
            String condition = request.hasConditionExpr() ? request.getConditionExpr() : null;
            if (!ConditionEvaluator.evaluate(condition, current)) {
                throw new IllegalStateException("Resume condition '" + condition + "' not met for " + request.getProcessId());
            }
            return toResponse(engine.resume(request.getProcessId(), true));
        });
    }

    @Override
    public void mutateProcess(MutateProcessRequest request, StreamObserver<ProcessInstanceResponse> out) {
        reply(out, () -> toResponse(engine.mutate(request.getProcessId(), request.getVariableUpdatesMap())));
    }

    @Override
    public void executeAgentInstruction(AgentInstructionRequest request, StreamObserver<AgentInstructionResponse> out) {
        reply(out, () -> AgentInstructionResponse.newBuilder()
                .setResult(orchestrator.execute(request.getInstruction())).build());
    }

    private static <T> void reply(StreamObserver<T> out, Supplier<T> action) {
        try {
            out.onNext(action.get());
            out.onCompleted();
        } catch (ProcessNotFoundException e) {
            out.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (DuplicateProcessException e) {
            out.onError(Status.ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalStateException e) {
            out.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    static ProcessInstanceResponse toResponse(ProcessInstance instance) {
        ProcessInstanceResponse.Builder builder = ProcessInstanceResponse.newBuilder()
                .setProcessId(instance.getProcessId())
                .setProcessType(instance.getProcessType())
                .setState(com.example.processengine.grpc.v1.ProcessState.valueOf(
                        "PROCESS_STATE_" + instance.getState().name()))
                .putAllVariables(instance.variablesSnapshot());
        if (instance.getLastError() != null) builder.setLastError(instance.getLastError());
        return builder.build();
    }
}
