package com.github.paicoding.forum.web.front.article.rest;

import com.github.paicoding.forum.api.model.vo.ResVo;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import com.github.paicoding.forum.api.model.vo.constants.StatusEnum;
import com.github.paicoding.forum.core.permission.Permission;
import com.github.paicoding.forum.core.permission.UserRole;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import com.github.paicoding.forum.service.article.service.ArticleSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章AI总结 REST 接口
 *
 * @author Claude
 * @date 2026/2/20
 */
@RestController
@RequestMapping("article/api")
public class ArticleSummaryRestController {

    @Autowired
    private ArticleSummaryService summaryService;

    @Autowired
    private ArticleReadService articleReadService;

    /**
     * 获取文章AI总结
     * <p>首次请求触发异步生成，返回 status=0；前端轮询直到 status=1</p>
     */
    @GetMapping("summary/{articleId}")
    public ResVo<ArticleSummaryDTO> getSummary(@PathVariable Long articleId) {
        if (articleId == null || articleId <= 0) {
            return ResVo.fail(StatusEnum.ILLEGAL_ARGUMENTS_MIXED, "文章ID无效");
        }
        ArticleDO article = articleReadService.queryBasicArticle(articleId);
        if (article == null) {
            return ResVo.fail(StatusEnum.RECORDS_NOT_EXISTS, "文章不存在");
        }
        ArticleSummaryDTO dto = summaryService.querySummary(articleId);
        return ResVo.ok(dto);
    }

    /**
     * 重新生成总结（文章内容更新后可调用）
     */
    @Permission(role = UserRole.LOGIN)
    @PostMapping("summary/regenerate/{articleId}")
    public ResVo<Boolean> regenerate(@PathVariable Long articleId) {
        if (articleId == null || articleId <= 0) {
            return ResVo.fail(StatusEnum.ILLEGAL_ARGUMENTS_MIXED, "文章ID无效");
        }
        ArticleDO article = articleReadService.queryBasicArticle(articleId);
        if (article == null) {
            return ResVo.fail(StatusEnum.RECORDS_NOT_EXISTS, "文章不存在");
        }
        summaryService.regenerate(articleId);
        return ResVo.ok(true);
    }
}
