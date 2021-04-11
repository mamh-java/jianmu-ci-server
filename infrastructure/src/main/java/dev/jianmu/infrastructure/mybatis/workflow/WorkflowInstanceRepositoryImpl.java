package dev.jianmu.infrastructure.mybatis.workflow;

import dev.jianmu.infrastructure.exception.DBException;
import dev.jianmu.infrastructure.mapper.workflow.WorkflowInstanceMapper;
import dev.jianmu.workflow.aggregate.process.ProcessStatus;
import dev.jianmu.workflow.aggregate.process.WorkflowInstance;
import dev.jianmu.workflow.repository.WorkflowInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

/**
 * @class: WorkflowInstanceRepositoryImpl
 * @description: 流程实例仓储实现类
 * @author: Ethan Liu
 * @create: 2021-03-18 08:38
 **/
@Repository
public class WorkflowInstanceRepositoryImpl implements WorkflowInstanceRepository {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowInstanceRepositoryImpl.class);
    @Resource
    private ApplicationEventPublisher publisher;

    @Resource
    private WorkflowInstanceMapper workflowInstanceMapper;

    @Override
    public List<WorkflowInstance> findByRefAndVersionAndStatus(String workflowRef, String workflowVersion, ProcessStatus status) {
        return this.workflowInstanceMapper.findByRefAndVersionAndStatus(workflowRef, workflowVersion, status);
    }

    @Override
    public Optional<WorkflowInstance> findById(String instanceId) {
        return this.workflowInstanceMapper.findById(instanceId);
    }

    @Override
    public WorkflowInstance add(WorkflowInstance workflowInstance) {
        boolean succeed = this.workflowInstanceMapper.add(workflowInstance, 1);
        if (!succeed) {
            throw new DBException.InsertFailed("流程实例插入失败");
        }
        Optional<WorkflowInstance> instanceOptional = this.workflowInstanceMapper.findById(workflowInstance.getId());
        publisher.publishEvent(workflowInstance);
        return instanceOptional.orElseThrow(() -> new RuntimeException("未找到流程实例"));
    }

    @Override
//    @IsTryAgain
    @Retryable(value = DBException.OptimisticLocking.class, maxAttempts = 5, backoff = @Backoff(delay = 3000L, multiplier = 1))
    public WorkflowInstance save(WorkflowInstance workflowInstance) {
        int version = this.workflowInstanceMapper.getVersion(workflowInstance.getId());
        boolean succeed = this.workflowInstanceMapper.save(workflowInstance, version);
        if (!succeed) {
            throw new DBException.OptimisticLocking("未找到对应的乐观锁版本数据，无法完成数据更新");
        }
        Optional<WorkflowInstance> instanceOptional = this.workflowInstanceMapper.findById(workflowInstance.getId());
        this.publisher.publishEvent(workflowInstance);
        return instanceOptional.orElseThrow(() -> new RuntimeException("流程实例更新失败"));
    }

    @Recover
    public WorkflowInstance recover(DBException.OptimisticLocking e) {
        logger.info("------------超过重试次数-------------");
        return null;
    }

    @Override
    public List<WorkflowInstance> findAll(int pageNum, int pageSize) {
        return this.workflowInstanceMapper.findAll(pageNum, pageSize);
    }
}