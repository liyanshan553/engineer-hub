package com.github.paicoding.forum.service.article.summary;

/**
 * Agent 状态机枚举
 *
 * <p>定义文章总结 Agent 的执行阶段，支持条件跳转和自纠错回路：
 * <pre>
 * ANALYZE → PLAN → GENERATE → VALIDATE ──→ DONE
 *                      ↑          │
 *                      └── REFINE ┘ (校验失败时回路，最多 N 次)
 * </pre>
 * </p>
 *
 * @author Claude
 * @date 2026/2/20
 */
public enum AgentState {

    /** 分析文章特征（规则引擎） */
    ANALYZE("文章特征分析"),

    /** LLM 阅读文章生成分析计划 */
    PLAN("生成分析计划"),

    /** 根据计划执行结构化 JSON 生成 */
    GENERATE("生成总结内容"),

    /** 校验输出质量（规则 + LLM 评分） */
    VALIDATE("校验输出质量"),

    /** 针对校验问题定向修复（自纠错） */
    REFINE("定向修复"),

    /** 完成 */
    DONE("完成");

    private final String desc;

    AgentState(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
