package dev.jianmu.application.service;

import com.github.pagehelper.PageInfo;
import dev.jianmu.application.exception.DataNotFoundException;
import dev.jianmu.infrastructure.mybatis.project.ProjectGroupRepositoryImpl;
import dev.jianmu.project.aggregate.ProjectGroup;
import dev.jianmu.project.aggregate.ProjectLinkGroup;
import dev.jianmu.project.repository.ProjectLinkGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Daihw
 * @class ProjectGroupApplication
 * @description 项目组门面类
 * @create 2021/11/25 2:59 下午
 */
@Service
public class ProjectGroupApplication {
    public static final String DEFAULT_PROJECT_GROUP_ID = "1";
    public static final String DEFAULT_PROJECT_GROUP_NAME = "默认分组";

    private final ProjectGroupRepositoryImpl projectGroupRepository;
    private final ProjectLinkGroupRepository projectLinkGroupRepository;

    public ProjectGroupApplication(ProjectGroupRepositoryImpl projectGroupRepository, ProjectLinkGroupRepository projectLinkGroupRepository) {
        this.projectGroupRepository = projectGroupRepository;
        this.projectLinkGroupRepository = projectLinkGroupRepository;
    }

    public PageInfo<ProjectGroup> findPage(int pageNum, int pageSize) {
        return this.projectGroupRepository.findPage(pageNum, pageSize);
    }

    public ProjectGroup findById(String projectGroupId) {
        return this.projectGroupRepository.findById(projectGroupId)
                .orElseThrow(() -> new DataNotFoundException("未找到项目组"));
    }

    @Transactional
    public void createProjectGroup(ProjectGroup projectGroup) {
        var sort = this.projectGroupRepository.findBySortMax()
                .map(ProjectGroup::getSort)
                .orElse(0);
        projectGroup.setProjectNumber(0);
        projectGroup.setSort(++sort);
        projectGroup.setLastModifiedTime();
        this.projectGroupRepository.add(projectGroup);
    }

    @Transactional
    public void updateProjectGroup(String projectGroupId, ProjectGroup projectGroup) {
        var originProjectGroup = this.projectGroupRepository.findById(projectGroupId)
                .orElseThrow(() -> new DataNotFoundException("未找到项目组"));
        originProjectGroup.setName(projectGroup.getName());
        originProjectGroup.setDescription(projectGroup.getDescription());
        originProjectGroup.setLastModifiedTime();
        this.projectGroupRepository.update(originProjectGroup);
    }

    @Transactional
    public void deleteById(String projectGroupId) {
        var projectIds = this.projectLinkGroupRepository.findAllProjectIdByGroupId(projectGroupId);
        // 添加到默认分组
        var sort = this.projectLinkGroupRepository.findByProjectGroupIdAndSortMax(DEFAULT_PROJECT_GROUP_ID)
                .map(ProjectLinkGroup::getSort)
                .orElseThrow(() -> new DataNotFoundException("未找到默认项目组"));
        List<ProjectLinkGroup> projectLinkGroups = new ArrayList<>();
        for (String projectId : projectIds) {
            projectLinkGroups.add(ProjectLinkGroup.Builder.aReference()
                    .projectGroupId(DEFAULT_PROJECT_GROUP_ID)
                    .projectId(projectId)
                    .sort(++sort)
                    .build());
        }
        this.projectLinkGroupRepository.addAll(projectLinkGroups);
        // 删除
        this.projectLinkGroupRepository.deleteByProjectGroupId(projectGroupId);
        this.projectGroupRepository.deleteById(projectGroupId);
    }

    @Transactional
    public void updateSort(Integer originSort, Integer targetSort) {
        var groups = this.projectGroupRepository.findAllBySortBetween(originSort, targetSort);
        for (int i = 1; i < groups.size() - 1; i++) {
            this.projectGroupRepository.updateSortById(groups.get(i).getId(), groups.get(i + 1).getSort());
        }
        this.projectGroupRepository.updateSortById(groups.get(0).getId(), targetSort);
        this.projectGroupRepository.updateSortById(groups.get(groups.size() - 1).getId(), originSort);
    }

    @Transactional
    public void addProject(String projectGroupId, List<String> projectIds) {
        // 添加分组中间表
        var sort = this.projectLinkGroupRepository.findByProjectGroupIdAndSortMax(projectGroupId)
                .map(ProjectLinkGroup::getSort)
                .orElse(0);
        var projectLinkGroups = new ArrayList<ProjectLinkGroup>();
        for (String projectId : projectIds) {
            projectLinkGroups.add(ProjectLinkGroup.Builder.aReference()
                    .projectGroupId(projectGroupId)
                    .projectId(projectId)
                    .sort(++sort)
                    .build());
        }
        this.projectLinkGroupRepository.addAll(projectLinkGroups);
        // 删除默认分组中间表
        this.projectLinkGroupRepository.deleteByGroupIdAndProjectIdIn(DEFAULT_PROJECT_GROUP_ID, projectIds);
        // 修改项目组个数
        this.projectGroupRepository.addProjectCountById(projectGroupId, projectIds.size());
    }

    @Transactional
    public void deleteProject(String projectLinkGroupId) {
        var projectLinkGroup = this.projectLinkGroupRepository.findById(projectLinkGroupId)
                .orElseThrow(() -> new DataNotFoundException("未找到项目组中的项目"));
        // 添加到默认分组
        var sort = this.projectLinkGroupRepository.findByProjectGroupIdAndSortMax(DEFAULT_PROJECT_GROUP_ID)
                .map(ProjectLinkGroup::getSort)
                .orElseThrow(() -> new DataNotFoundException("未找到默认项目组"));
        var newProjectLinkGroup = ProjectLinkGroup.Builder.aReference()
                .projectGroupId(DEFAULT_PROJECT_GROUP_ID)
                .projectId(projectLinkGroup.getProjectId())
                .sort(++sort)
                .build();
        this.projectLinkGroupRepository.add(newProjectLinkGroup);
        // 删除项目组项目
        this.projectLinkGroupRepository.deleteById(projectLinkGroupId);
        // 修改项目组个数
        this.projectGroupRepository.subProjectCountById(projectLinkGroup.getProjectGroupId(), 1);
        this.projectGroupRepository.addProjectCountById(DEFAULT_PROJECT_GROUP_ID, 1);
    }

    @Transactional
    public void updateProjectSort(String projectGroupId, Integer originSort, Integer targetSort) {
        var linkGroups = this.projectLinkGroupRepository.findAllByGroupIdAndSortBetween(projectGroupId, originSort, targetSort);
        for (int i = 1; i < linkGroups.size() - 1; i++) {
            this.projectLinkGroupRepository.updateSortById(linkGroups.get(i).getId(), linkGroups.get(i + 1).getSort());
        }
        this.projectLinkGroupRepository.updateSortById(linkGroups.get(0).getId(), targetSort);
        this.projectLinkGroupRepository.updateSortById(linkGroups.get(linkGroups.size() - 1).getId(), originSort);
    }
}
