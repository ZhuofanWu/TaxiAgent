# Agent 学习笔记

本目录用于整理本项目 AI Agent 层（`src/main/java/com/fancy/taxiagent/agentbase/`、`agents/`）的学习笔记，与 `../redis/` 平行。

## 计划覆盖的主题

> 待补充。当前 Redis 部分已完成，见 [`../redis/README.md`](../redis/README.md)。

候选主题（来自 `CLAUDE.md` 描述的架构）：

| 主题 | 代码落点 |
|---|---|
| 四类 Agent 的职责划分与路由 | `agents/DailyAgent`、`OrderAgent`、`SupportAgent`、`FallbackAgent` |
| 工具调用（Function Calling）注册与回调 | `agentbase/tool/` |
| 记忆系统的分层存储 | `agentbase/memory/`（Heap / Redis / MySQL / ES） |
| 消息解析与 Advisor 机制 | `agentbase/memory/MessageParser`、`MemoryAdvisor` |
| RAG 检索 | `agentbase/rag/` |
| 外部 API 接入（高德 / 和风天气） | `agentbase/amap/`、`agentbase/qweather/` |
