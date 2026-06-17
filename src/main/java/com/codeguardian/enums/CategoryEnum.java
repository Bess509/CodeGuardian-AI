package com.codeguardian.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 问题类别枚举
 *
 * @author CodeGuardian Team
 * @date 2025/12/30
 */
@Getter
@AllArgsConstructor
public enum CategoryEnum {

    /**
     * 安全
     */
    SECURITY(0, "安全"),

    /**
     * 性能
     */
    PERFORMANCE(1, "性能"),

    /**
     * 缺陷
     */
    BUG(2, "缺陷"),

    /**
     * 代码风格
     */
    CODE_STYLE(3, "代码风格"),

    /**
     * 可维护性
     */
    MAINTAINABILITY(4, "可维护性");

    /**
     * 枚举值
     */
    private Integer value;

    /**
     * 枚举描述
     */
    private String desc;

    public static CategoryEnum fromName(String name) {
        if (name == null) return CODE_STYLE;
        String n = name.toUpperCase();
        for (CategoryEnum e : values()) {
            if (e.name().equals(n)) return e;
        }
        return CODE_STYLE;
    }

    public static CategoryEnum fromValue(Integer value) {
        if (value == null) return CODE_STYLE;
        for (CategoryEnum e : values()) {
            if (e.value.equals(value)) return e;
        }
        return CODE_STYLE;
    }
}
