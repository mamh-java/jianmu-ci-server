package dev.jianmu.task.repository;

import dev.jianmu.task.aggregate.Volume;

import java.util.Optional;

/**
 * @author <a href="https://gitee.com/ethan-liu">Ethan Liu</a>
 * @date 2023-02-17 09:21
 */
public interface VolumeRepository {
    // 创建Volume
    void create(Volume volume);

    // 根据ID查找
    Optional<Volume> findById(String id);

    // 根据Name查找
    Optional<Volume> findByName(String name);

    // 激活Volume
    void activate(Volume volume);

    // 标记Volume
    void taint(Volume volume);

    // 根据ID删除Volume
    void deleteById(String id);

    // 根据name删除Volume
    void deleteByName(String name);
}