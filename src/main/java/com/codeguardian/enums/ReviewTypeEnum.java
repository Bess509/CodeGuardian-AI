package com.codeguardian.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审查类型枚举
 *
 * @author CodeGuardian Team
 * @date 2025/12/30
 */
@Getter
@AllArgsConstructor
public enum ReviewTypeEnum {

    /**
     * 项目
     */
    PROJECT(0, "项目"),

    /**
     * 目录
     */
    DIRECTORY(1, "目录"),

    /**
     * 文件
     */
    FILE(2, "文件"),

    /**
     * 代码片段
     */
    SNIPPET(3, "代码片段"),

    /**
     * Git
     */
    GIT(4, "Git");

    /**
     * 枚举值
     */
    private Integer value;

    /**
     * 枚举描述
     */
    private String desc;

    public static ReviewTypeEnum fromName(String name) {
        if (name == null) return SNIPPET;
        String n = name.toUpperCase();
        for (ReviewTypeEnum e : values()) {
            if (e.name().equals(n)) return e;
        }
        return SNIPPET;
    }

    public static ReviewTypeEnum fromValue(Integer value) {
        if (value == null) return SNIPPET;
        for (ReviewTypeEnum e : values()) {
            if (e.value.equals(value)) return e;
        }
        return SNIPPET;
    }
}
