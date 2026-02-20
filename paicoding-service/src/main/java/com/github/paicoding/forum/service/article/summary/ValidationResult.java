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

    public void addIssue(String issue) {
        this.issues.add(issue);
        this.passed = false;
    }

    public static ValidationResult pass() {
        ValidationResult r = new ValidationResult();
        r.setPassed(true);
        return r;
    }
}
