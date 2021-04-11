package dev.jianmu.api;

import dev.jianmu.workflow.aggregate.definition.*;
import dev.jianmu.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @class: WorkflowTest
 * @description: TODO
 * @author: Ethan Liu
 * @create: 2021-04-08 21:01
 **/
@SpringBootTest(classes = SpringbootApp.class)
@ActiveProfiles("test")
public class WorkflowTest {
    @Resource
    private WorkflowRepository workflowRepository;

    @Test
    @Transactional
    @Rollback(value = false)
    void test1() {
        Start start = Start.Builder.aStart()
                .name("Start1")
                .ref("start_1")
                .description("开始节点1")
                .build();
        AsyncTask gitTask = AsyncTask.Builder.anAsyncTask()
                .name("Git Clone")
                .ref("git_clone0.1")
                .description("Git库下载任务")
                .build();
        AsyncTask mavenTask = AsyncTask.Builder.anAsyncTask()
                .name("Maven")
                .ref("maven0.1")
                .description("Maven命令执行环境")
                .build();
        End end = End.Builder.anEnd()
                .name("End1")
                .ref("end_1")
                .description("结束节点1")
                .build();

        start.setTargets(Set.of(gitTask.getRef()));

        gitTask.setSources(Set.of(start.getRef()));
        gitTask.setTargets(Set.of(mavenTask.getRef()));

        mavenTask.setSources(Set.of(gitTask.getRef()));
        mavenTask.setTargets(Set.of(end.getRef()));

        end.setSources(Set.of(mavenTask.getRef()));

        Set<Node> nodes = Set.of(start, gitTask, mavenTask, end);
        var workflow = Workflow.Builder.aWorkflow()
                .name("Java CI")
                .ref("java_ci")
                .version("1.0")
                .description("CI流程 for Java")
                .nodes(nodes)
                .build();
        this.workflowRepository.add(workflow);
    }
}