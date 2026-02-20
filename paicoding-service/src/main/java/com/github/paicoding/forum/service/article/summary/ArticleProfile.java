package com.github.paicoding.forum.service.article.summary;

import lombok.Data;

import java.util.List;

/**
 * 文章特征分析结果（Agent ANALYZE 阶段的输出）
 *
 * <p>通过规则引擎对文章进行预分析，指导后续 Agent 的决策路由。</p>
 *
 * @author Claude
 * @date 2026/2/20
 */
@Data
public class ArticleProfile {

    public enum LengthLevel {
        /** <=2000字 */
        SHORT,
        /** 2000~8000字 */
        MEDIUM,
        /** >8000字 */
        LONG
    }

    public enum ArticleType {
        /** 含代码块的教程/实战 */
        TUTORIAL,
        /** 概念性分析/原理讲解 */
        ANALYSIS,
        /** 通用文章 */
        GENERAL
    }

    private Long articleId;
    private String title;
    private String content;
    private LengthLevel lengthLevel;
    private ArticleType articleType;
    private boolean hasCodeBlocks;
    private int codeBlockCount;
    private int paragraphCount;
    private List<String> paragraphs;

    /** 是否需要分段摘要（长文章） */
    public boolean needsChunkedSummary() {
        return lengthLevel == LengthLevel.LONG;
    }

    /** 是否需要生成 codeOrSteps 字段 */
    public boolean needsCodeSteps() {
        return hasCodeBlocks || articleType == ArticleType.TUTORIAL;
    }

    /** 是否需要生成 risksOrPitfalls 字段 */
    public boolean needsRisks() {
        return articleType == ArticleType.TUTORIAL;
    }

    /** 获取文章类型的中文描述（用于 PLAN prompt） */
    public String getArticleTypeDesc() {
        switch (articleType) {
            case TUTORIAL:
                return "含代码的技术教程（" + codeBlockCount + "个代码块）";
            case ANALYSIS:
                return "概念分析/原理讲解";
            default:
                return "通用技术文章";
        }
    }
}
