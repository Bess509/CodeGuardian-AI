package com.codeguardian.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务状态枚举
 *
 * @author CodeGuardian Team
 * @date 2025/12/30
 */
@Getter
@AllArgsConstructor
public enum TaskStatusEnum {

    /**
     * 待处理
     */
    PENDING(0, "待处理"),

    /**
     * 运行中
     */
    RUNNING(1, "运行中"),

    /**
     * 已完成
     */
    COMPLETED(2, "已完成"),

    /**
     * 失败
     */
    FAILED(3, "失败"),

    /**
     * 已取消
     */
    CANCELLED(4, "已取消");

    /**
     * 枚举值
     */
    private Integer value;

    /**
     * 枚举描述
     */
    private String desc;

    public static TaskStatusEnum fromName(String name) {
        if (name == null) return PENDING;
        String n = name.toUpperCase();
        for (TaskStatusEnum e : values()) {
            if (e.name().equals(n)) return e;
        }
        return PENDING;
    }

    public static TaskStatusEnum fromValue(Integer value) {
        if (value == null) return PENDING;
        for (TaskStatusEnum e : values()) {
            if (e.value.equals(value)) return e;
        }
        return PENDING;
    }
}
