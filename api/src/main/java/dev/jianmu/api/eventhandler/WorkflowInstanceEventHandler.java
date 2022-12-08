package dev.jianmu.api.eventhandler;

import dev.jianmu.application.command.WorkflowStartCmd;
import dev.jianmu.application.service.internal.*;
import dev.jianmu.workflow.aggregate.process.WorkflowInstance;
import dev.jianmu.workflow.event.process.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author Ethan Liu
 * @class WorkflowEventHandler
 * @description 流程事件处理器
 * @create 2021-03-24 14:18
 */
@Component
@Slf4j
public class WorkflowInstanceEventHandler {
    private final WorkflowInternalApplication workflowInternalApplication;
    private final AsyncTaskInstanceInternalApplication asyncTaskInstanceInternalApplication;
    private final ApplicationEventPublisher publisher;
    private final WorkerInternalApplication workerInternalApplication;
    private final TaskInstanceInternalApplication taskInstanceInternalApplication;
    private final WorkflowInstanceInternalApplication workflowInstanceInternalApplication;

    public WorkflowInstanceEventHandler(
            WorkflowInternalApplication workflowInternalApplication,
            AsyncTaskInstanceInternalApplication asyncTaskInstanceInternalApplication,
            ApplicationEventPublisher publisher,
            WorkerInternalApplication workerInternalApplication,
            TaskInstanceInternalApplication taskInstanceInternalApplication,
            WorkflowInstanceInternalApplication workflowInstanceInternalApplication) {
        this.workflowInternalApplication = workflowInternalApplication;
        this.asyncTaskInstanceInternalApplication = asyncTaskInstanceInternalApplication;
        this.publisher = publisher;
        this.workerInternalApplication = workerInternalApplication;
        this.taskInstanceInternalApplication = taskInstanceInternalApplication;
        this.workflowInstanceInternalApplication = workflowInstanceInternalApplication;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleAggregateRootEvents(WorkflowInstance workflowInstance) {
        log.info("Get workflowInstance here -------------------------");
        workflowInstance.getUncommittedDomainEvents().forEach(event -> {
            log.info("publish {} here", event.getClass().getSimpleName());
            this.publisher.publishEvent(event);
        });
        workflowInstance.clear();
        log.info("-----------------------------------------------------");
    }

    @Async
    @EventListener
    public void handleProcessInitializedEvent(ProcessInitializedEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessInitializedEvent here -------------------------");
        log.info(event.toString());
        // 执行流程实例
        this.workflowInstanceInternalApplication.start(event.getWorkflowRef(), event.getTriggerId());
        log.info("-----------------------------------------------------");
    }

    @Async
    @EventListener
    public void handleProcessVolumeCreatedEvent(ProcessVolumeCreatedEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessVolumeCreatedEvent here -------------------------");
        log.info(event.toString());
        // 初始化流程实例
        var workflowStartCmd = WorkflowStartCmd.builder()
                .triggerId(event.getTriggerId())
                .workflowRef(event.getWorkflowRef())
                .workflowVersion(event.getWorkflowVersion())
                .build();
        this.workflowInternalApplication.init(workflowStartCmd);
        // 创建Workspace
        this.workerInternalApplication.createVolumeTask(event.getTriggerId(), "start");
        log.info("-----------------------------------------------------");
    }

    @Async
    @EventListener
    public void handleProcessStartedEvent(ProcessStartedEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessStartedEvent here -------------------------");
        log.info(event.toString());
        // 触发流程启动
        var workflowStartCmd = WorkflowStartCmd.builder()
                .triggerId(event.getTriggerId())
                .workflowRef(event.getWorkflowRef())
                .workflowVersion(event.getWorkflowVersion())
                .build();
        this.workflowInternalApplication.start(workflowStartCmd);
        log.info("-----------------------------------------------------");
    }

    @Async
    @EventListener
    public void handleProcessTerminatedEvent(ProcessTerminatedEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessTerminatedEvent here -------------------------");
        log.info(event.toString());
        this.asyncTaskInstanceInternalApplication.terminateByTriggerId(event.getTriggerId());
        this.taskInstanceInternalApplication.terminateByTriggerId(event.getTriggerId());
        // 执行流程实例
        this.workflowInstanceInternalApplication.start(event.getWorkflowRef(), event.getTriggerId());
        log.info("-----------------------------------------------------");
    }

    @Async
    @EventListener
    public void handleProcessEndedEvent(ProcessEndedEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessEndedEvent here -------------------------");
        log.info(event.toString());
        this.workerInternalApplication.createVolumeTask(event.getTriggerId(), "end");
        // 执行流程实例
        this.workflowInstanceInternalApplication.start(event.getWorkflowRef(), event.getTriggerId());
        log.info("-----------------------------------------------------");
    }

    @EventListener
    public void handleProcessNotRunningEvent(ProcessNotRunningEvent event) {
        MDC.put("triggerId", event.getTriggerId());
        log.info("Get ProcessNotRunningEvent here -------------------------");
        log.info(event.toString());
        this.workerInternalApplication.createVolumeTask(event.getTriggerId(), "end");
        // 执行流程实例
        this.workflowInstanceInternalApplication.start(event.getWorkflowRef(), event.getTriggerId());
        log.info("-----------------------------------------------------");
    }
}
