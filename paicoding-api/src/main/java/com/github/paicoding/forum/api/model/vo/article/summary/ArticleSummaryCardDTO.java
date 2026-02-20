package com.github.paicoding.forum.api.model.vo.article.summary;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;

/**
 * 文章 AI 总结卡片。
 */
@Data
@Accessors(chain = true)
public class ArticleSummaryCardDTO {
    private String tldr;
    private List<String> highlights;
    private String whoShouldRead;
    private List<String> keyTerms;
    private List<String> codeOrSteps;
    private List<String> risksOrPitfalls;
    private List<String> citations;

    public static ArticleSummaryCardDTO empty() {
        return new ArticleSummaryCardDTO()
                .setHighlights(Collections.emptyList())
                .setKeyTerms(Collections.emptyList())
                .setCodeOrSteps(Collections.emptyList())
                .setRisksOrPitfalls(Collections.emptyList())
                .setCitations(Collections.emptyList());
    }
}
