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

    /**
     * 文章长度等级
     */
    public enum LengthLevel {
        /** ≤2000字 */
        SHORT,
        /** 2000~8000字 */
        MEDIUM,
        /** >8000字 */
        LONG
    }

    /**
     * 文章类型（规则推断）
     */
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
    private int paragraphCount;
    private List<String> paragraphs;

    /**
     * 是否需要分段摘要（长文章）
     */
    public boolean needsChunkedSummary() {
        return lengthLevel == LengthLevel.LONG;
    }

    /**
     * 是否需要生成 codeOrSteps 字段
     */
    public boolean needsCodeSteps() {
        return hasCodeBlocks || articleType == ArticleType.TUTORIAL;
    }
}
