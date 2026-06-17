package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReviewAgentPlanService {

    public String buildChineseMarkdown(ReviewAgentState state) {
        List<Finding> seeds = state.getSeedFindings() != null ? state.getSeedFindings() : List.of();
        String highRiskLines = seeds.stream()
                .filter(f -> f.getSeverity() != null && f.getSeverity() <= SeverityEnum.HIGH.getValue())
                .map(f -> "- " + safe(f.getTitle()) + "，行号：" + lineLabel(f))
                .limit(8)
                .collect(Collectors.joining("\n"));
        if (highRiskLines.isBlank()) {
            highRiskLines = "- 暂无高风险静态信号，后续按敏感 API 与文件结构继续检查";
        }

        String seedSummary = seeds.stream()
                .map(f -> "- [" + SeverityEnum.fromValue(f.getSeverity()).name() + "] "
                        + safe(f.getTitle()) + "，位置：" + safe(f.getLocation()))
                .limit(12)
                .collect(Collectors.joining("\n"));
        if (seedSummary.isBlank()) {
            seedSummary = "- 暂无静态 seed finding";
        }

        return """
                # Agent 审查计划

                ## 审查范围
                - 任务 ID：%s
                - 文件路径：%s
                - 编程语言：%s
                - Workflow Run ID：%s

                ## 静态分析信号
                - 规则/工具 seed findings 数量：%d
                - 高风险候选位置：
                %s

                ## Seed Finding 摘要
                %s

                ## 风险假设
                - [高] 检查安全敏感 API、硬编码凭据、注入风险、弱加密和鉴权缺失。
                - [中] 检查异常处理、空指针、资源释放、性能热点和可维护性问题。
                - [低] 检查命名、格式、注释和团队规范一致性。

                ## RAG 检索计划
                - 查询策略：静态发现 + 风险关键词 + 目标行上下文
                - 目标行：优先使用 HIGH/CRITICAL seed finding 行号，其次使用敏感 API 行号
                - 风险关键词：从 seed finding、源码敏感 API、框架导入中提取
                - 规则类别：SECURITY、BUG、PERFORMANCE、MAINTAINABILITY、CODE_STYLE
                - 预计检索轮次：最多 2 次

                ## 双角色 Workflow
                1. Reviewer Agent 生成候选问题。
                2. Judge Agent 复核候选问题。
                3. Deterministic Verifier 校验证据、行号和哈希。
                4. 仅保存通过确定性校验的问题。

                ## Loop 策略
                - RAG 最大补检索次数：2
                - Judge 修正建议最大处理次数：2
                - 高危/严重问题必须具备源码证据。
                - RAG 证据只能辅助，不能替代源码证据。

                ## Prompt 边界策略
                %s
                """.formatted(
                state.getTaskId() != null ? state.getTaskId() : "未分配",
                safe(state.getSourceRef()),
                safe(state.getLanguage()),
                safe(state.getWorkflowRunId()),
                seeds.size(),
                highRiskLines,
                seedSummary,
                indentBoundary(AgentRoleBoundaries.PLANNER)
        ).trim();
    }

    private String indentBoundary(String value) {
        return safe(value).lines()
                .map(line -> "- " + line)
                .collect(Collectors.joining("\n"));
    }

    private String lineLabel(Finding finding) {
        if (finding.getStartLine() == null) {
            return "未知";
        }
        if (finding.getEndLine() != null && !finding.getEndLine().equals(finding.getStartLine())) {
            return finding.getStartLine() + "-" + finding.getEndLine();
        }
        return String.valueOf(finding.getStartLine());
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
