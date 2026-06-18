<!-- TRELLIS:START -->
# Trellis 指令

这些指令适用于在本项目中工作的 AI 助手。

本项目由 Trellis 管理。你需要的工作知识位于 `.trellis/` 目录下：

- `.trellis/workflow.md`：开发阶段、何时创建任务、技能路由
- `.trellis/spec/`：按包和层级划分的编码规范（在对应层级编写代码前先阅读）
- `.trellis/workspace/`：每位开发者的日志和会话轨迹
- `.trellis/tasks/`：活动任务和归档任务（PRD、研究记录、jsonl 上下文）

如果你的平台提供 Trellis 命令（例如 `/trellis:finish-work`、`/trellis:continue`），优先使用它们，而不是手动执行对应步骤。并非每个平台都会暴露所有命令。

如果你使用 Codex 或其他具备 agent 能力的工具，额外的项目级辅助内容可能位于：

- `.agents/skills/`：可复用的 Trellis 技能
- `.codex/agents/`：可选的自定义子代理

由 Trellis 管理。此代码块之外的编辑会被保留；此代码块之内的编辑未来可能会被 `trellis update` 覆盖。

<!-- TRELLIS:END -->

# LLM 编码规范

- 编码前先思考：说明假设，暴露歧义；当需求或取舍不清晰时主动询问，不隐藏不确定性。
- 保持方案简单：只实现被请求的内容，避免猜测性的功能，不为一次性需求增加抽象或配置项。
- 做精确修改：只触碰完成任务所需的文件和代码行，匹配现有风格，避免无关清理或重构。
- 只清理自己的改动：移除本次工作导致无用的 import、变量、方法或文件；无关的历史遗留死代码只说明，不主动修改。
- 以可验证目标推进：非平凡任务要定义成功标准；多步骤任务使用简短计划；汇报完成前用聚焦测试或检查验证。
- 所有 Git 提交信息必须使用中文，包括提交标题和提交正文。
- 所有所需密码可以从 `\code-review-ai-agent-master\start-codeguardian-local.bat` 获取。
