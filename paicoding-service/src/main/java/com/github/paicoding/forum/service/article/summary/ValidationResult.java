package com.github.paicoding.forum.service.article.summary;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent VALIDATE 阶段的校验结果
 *
 * <p>收集各维度的校验问题，用于驱动 REFINE 阶段的定向修复。</p>
 *
 * @author Claude
 * @date 2026/2/20
 */
@Data
public class ValidationResult {

    private boolean passed;
    private List<String> issues = new ArrayList<>();

    /** 引用中被移除的条目数 */
    private int removedCitations;

    /** 是否需要重新生成 tldr */
    private boolean tldrNeedsRefine;

    /** 是否需要重新生成 highlights */
    private boolean highlightsNeedRefine;

    /** 是否需要重新生成 citations */
    private boolean citationsNeedRefine;

    /** 是否需要重新生成 keyTerms */
    private boolean keyTermsNeedRefine;

    public void addIssue(String issue) {
        this.issues.add(issue);
        this.passed = false;
    }

    public boolean hasRefineTarget() {
        return tldrNeedsRefine || highlightsNeedRefine || citationsNeedRefine || keyTermsNeedRefine;
    }

    /** 生成面向 LLM 的修复指令摘要 */
    public String toRefineInstruction() {
        StringBuilder sb = new StringBuilder();
        if (tldrNeedsRefine) {
            sb.append("- 请重新生成 tldr，控制在1~2句话内（不超过200字）\n");
        }
        if (highlightsNeedRefine) {
            sb.append("- 请重新生成 highlights，确保有3~6条要点，每条不超过30字\n");
        }
        if (citationsNeedRefine) {
            sb.append("- 请重新生成 citations，text 必须是文章中【逐字出现】的原文片段，不可有任何改动\n");
        }
        if (keyTermsNeedRefine) {
            sb.append("- 请重新生成 key_terms，每项必须有 term 和 explanation 字段\n");
        }
        return sb.toString();
    }

    public static ValidationResult pass() {
        ValidationResult r = new ValidationResult();
        r.setPassed(true);
        return r;
    }
}
