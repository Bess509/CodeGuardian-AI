package com.codeguardian.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 严重级别枚举
 *
 * @author CodeGuardian Team
 * @date 2025/12/30
 */
@Getter
@AllArgsConstructor
public enum SeverityEnum {

    /**
     * 严重
     */
    CRITICAL(0, "严重"),

    /**
     * 高危
     */
    HIGH(1, "高危"),

    /**
     * 中危
     */
    MEDIUM(2, "中危"),

    /**
     * 低危
     */
    LOW(3, "低危");

    /**
     * 枚举值
     */
    private Integer value;

    /**
     * 枚举描述
     */
    private String desc;

    public static SeverityEnum fromName(String name) {
        if (name == null) return MEDIUM;
        String n = name.toUpperCase();
        for (SeverityEnum e : values()) {
            if (e.name().equals(n)) return e;
        }
        return MEDIUM;
    }

    public static SeverityEnum fromValue(Integer value) {
        if (value == null) return MEDIUM;
        for (SeverityEnum e : values()) {
            if (e.value.equals(value)) return e;
        }
        return MEDIUM;
    }
}
