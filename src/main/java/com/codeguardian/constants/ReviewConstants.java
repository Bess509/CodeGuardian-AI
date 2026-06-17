package com.codeguardian.constants;

/**
 * 审查相关常量
 */
public class ReviewConstants {
    
    /**
     * 审查类型
     */
    public static class ReviewType {
        public static final String PROJECT = "PROJECT";
        public static final String DIRECTORY = "DIRECTORY";
        public static final String FILE = "FILE";
        public static final String SNIPPET = "SNIPPET";
        public static final String GIT = "GIT";
    }
    
    /**
     * 任务状态
     */
    public static class TaskStatus {
        public static final String PENDING = "PENDING";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
    }
    
    /**
     * 问题严重程度
     */
    public static class Severity {
        public static final String CRITICAL = "CRITICAL";
        public static final String HIGH = "HIGH";
        public static final String MEDIUM = "MEDIUM";
        public static final String LOW = "LOW";
    }
    
    /**
     * 问题类别
     */
    public static class Category {
        public static final String SECURITY = "SECURITY";
        public static final String PERFORMANCE = "PERFORMANCE";
        public static final String BUG = "BUG";
        public static final String CODE_STYLE = "CODE_STYLE";
        public static final String MAINTAINABILITY = "MAINTAINABILITY";
    }
}

