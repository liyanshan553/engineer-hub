package com.github.paicoding.forum.api.model.vo.article.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 文章AI总结卡片DTO
 *
 * @author Claude
 * @date 2026/2/20
 */
@Data
public class ArticleSummaryDTO {
    private Long articleId;

    /** 1~2句结论 */
    private String tldr;

    /** 3~6条要点（每条<=30字） */
    private List<String> highlights;

    /** 适用人群/前置知识 */
    private String whoShouldRead;

    /** 关键术语列表 */
    private List<KeyTermDTO> keyTerms;

    /** 代码/步骤（3~5步） */
    private List<String> codeOrSteps;

    /** 坑点/注意事项 */
    private List<String> risksOrPitfalls;

    /** 引用锚点 */
    private List<CitationDTO> citations;

    /** 0=生成中, 1=完成, -1=失败 */
    private Integer status;

    /** 生成所用模型名称 */
    private String modelName;

    /** 失败原因（仅 status=-1 时有值） */
    private String failReason;

    /** 总结版本号 */
    private Integer version;

    /**
     * 构建一个空的 "生成中" 状态 DTO
     */
    public static ArticleSummaryDTO generating(Long articleId) {
        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(articleId);
        dto.setStatus(0);
        return dto;
    }

    /**
     * 构建一个 "失败" 状态 DTO
     */
    public static ArticleSummaryDTO failed(Long articleId, String reason) {
        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(articleId);
        dto.setStatus(-1);
        dto.setFailReason(reason);
        return dto;
    }

    /**
     * 初始化所有列表字段为空列表（防止序列化 null）
     */
    public void ensureNonNull() {
        if (highlights == null) highlights = Collections.emptyList();
        if (keyTerms == null) keyTerms = Collections.emptyList();
        if (codeOrSteps == null) codeOrSteps = Collections.emptyList();
        if (risksOrPitfalls == null) risksOrPitfalls = Collections.emptyList();
        if (citations == null) citations = Collections.emptyList();
    }

    @Data
    public static class KeyTermDTO {
        private String term;
        private String explanation;
    }

    @Data
    public static class CitationDTO {
        /** 引用的原文片段 */
        private String text;

        /** 锚点定位 (paragraphIndex:startOffset:endOffset) */
        private String anchor;
    }
}
