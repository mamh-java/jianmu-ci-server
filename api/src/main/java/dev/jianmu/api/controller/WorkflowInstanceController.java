package dev.jianmu.api.controller;

import dev.jianmu.api.jwt.UserContextHolder;
import dev.jianmu.application.util.AssociationUtil;
import dev.jianmu.application.exception.DataNotFoundException;
import dev.jianmu.application.exception.NoPermissionException;
import dev.jianmu.application.service.ProjectApplication;
import dev.jianmu.application.service.internal.AsyncTaskInstanceInternalApplication;
import dev.jianmu.application.service.internal.WorkflowInstanceInternalApplication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Ethan Liu
 * @class WorkflowInstanceController
 * @description 流程实例接口类
 * @create 2021-03-24 16:02
 */
@RestController
@RequestMapping("workflow_instances")
@Tag(name = "流程实例接口", description = "提供流程实例启动停止等API")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowInstanceController {
    private final WorkflowInstanceInternalApplication instanceApplication;
    private final AsyncTaskInstanceInternalApplication taskInstanceInternalApplication;
    private final UserContextHolder userContextHolder;
    private final AssociationUtil associationUtil;
    private final ProjectApplication projectApplication;

    public WorkflowInstanceController(WorkflowInstanceInternalApplication instanceApplication, AsyncTaskInstanceInternalApplication taskInstanceInternalApplication, UserContextHolder userContextHolder, AssociationUtil associationUtil, ProjectApplication projectApplication) {
        this.instanceApplication = instanceApplication;
        this.taskInstanceInternalApplication = taskInstanceInternalApplication;
        this.userContextHolder = userContextHolder;
        this.associationUtil = associationUtil;
        this.projectApplication = projectApplication;
    }

    @PutMapping("/stop/{instanceId}")
    @Operation(summary = "流程终止接口", description = "流程终止接口")
    public void terminate(
            @Parameter(description = "流程实例ID") @PathVariable String instanceId
    ) {
        var repoId = this.userContextHolder.getSession().getAssociationId();
        var associationType = this.associationUtil.getAssociationType();
        this.checkProjectPermission(repoId, associationType, instanceId);
        this.instanceApplication.terminate(instanceId);
    }

    // 校验项目增删改查权限
    private void checkProjectPermission(String associationId, String associationType, String instanceId) {
        if (associationId == null || associationType == null) {
            return;
        }
        var workflowInstance = this.instanceApplication.findById(instanceId)
                .orElseThrow(() -> new DataNotFoundException("未找到该流程实例"));
        var project = this.projectApplication.findByWorkflowRef(workflowInstance.getWorkflowRef())
                .orElseThrow(() -> new DataNotFoundException("未找到项目：" + workflowInstance.getWorkflowRef()));
        if (!associationId.equals(project.getAssociationId()) || !associationType.equals(project.getAssociationType())) {
            throw new NoPermissionException();
        }
    }

    @PutMapping("/retry/{instanceId}/{taskRef}")
    @Operation(summary = "流程实例任务重试接口", description = "流程实例任务重试接口")
    public void retry(@PathVariable String instanceId, @PathVariable String taskRef) {
        var repoId = this.userContextHolder.getSession().getAssociationId();
        var associationType = this.associationUtil.getAssociationType();
        this.taskInstanceInternalApplication.retry(instanceId, taskRef, repoId, associationType);
    }

    @PutMapping("/ignore/{instanceId}/{taskRef}")
    @Operation(summary = "流程实例任务忽略接口", description = "流程实例任务忽略接口")
    public void ignore(@PathVariable String instanceId, @PathVariable String taskRef) {
        var repoId = this.userContextHolder.getSession().getAssociationId();
        var associationType = this.associationUtil.getAssociationType();
        this.taskInstanceInternalApplication.ignore(instanceId, taskRef, repoId, associationType);
    }
}
