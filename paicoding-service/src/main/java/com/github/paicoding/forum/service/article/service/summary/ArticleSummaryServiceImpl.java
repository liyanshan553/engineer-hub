package com.github.paicoding.forum.service.article.service.summary;

import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.api.model.vo.article.summary.ArticleSummaryCardDTO;
import com.github.paicoding.forum.api.model.vo.article.summary.ArticleSummaryResVo;
import com.github.paicoding.forum.api.model.vo.article.summary.ArticleSummaryStatusEnum;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.core.util.Md5Util;
import com.github.paicoding.forum.service.article.repository.dao.ArticleAiSummaryDao;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleAiSummaryDO;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.chatai.springai.ArticleSummaryAgentOrchestrator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class ArticleSummaryServiceImpl implements ArticleSummaryService {
    private static final String SUMMARY_CACHE_KEY = "article:summary:data:%d";
    private static final String SUMMARY_LOCK_KEY = "article:summary:lock:%d";
    private static final long SUMMARY_CACHE_EXPIRE = 86400L;
    private static final long SUMMARY_LOCK_EXPIRE = 120L;

    @Resource
    private ArticleDao articleDao;
    @Resource
    private ArticleAiSummaryDao articleAiSummaryDao;
    @Resource
    private ArticleSummaryAgentOrchestrator articleSummaryAgentOrchestrator;

    @Override
    public ArticleSummaryResVo getOrGenerateSummary(Long articleId) {
        ArticleDO article = articleDao.getById(articleId);
        if (article == null || article.getDeleted() == YesOrNoEnum.YES.getCode()) {
            return new ArticleSummaryResVo().setArticleId(articleId)
                    .setStatus(ArticleSummaryStatusEnum.FAILED)
                    .setMessage("文章不存在");
        }

        String content = articleDao.findLatestDetail(articleId).getContent();
        String contentHash = Md5Util.encode(content);

        // 1) Redis
        ArticleSummaryResVo redisResult = readFromRedis(articleId, contentHash);
        if (redisResult != null) {
            return redisResult;
        }

        // 2) DB
        ArticleAiSummaryDO db = articleAiSummaryDao.getByArticleId(articleId);
        if (db != null && StringUtils.equals(db.getContentHash(), contentHash)
                && db.getStatus() != null && db.getStatus() == 1
                && StringUtils.isNotBlank(db.getSummaryJson())) {
            ArticleSummaryCardDTO summary = JsonUtil.toObj(db.getSummaryJson(), ArticleSummaryCardDTO.class);
            cache(articleId, contentHash, summary, db.getUpdateTime() == null ? System.currentTimeMillis() : db.getUpdateTime().getTime());
            return ready(articleId, summary, db.getUpdateTime() == null ? System.currentTimeMillis() : db.getUpdateTime().getTime());
        }

        // 3) lock
        String lockKey = String.format(SUMMARY_LOCK_KEY, articleId);
        String token = UUID.randomUUID().toString();
        Boolean lock = RedisClient.setStrIfAbsentWithExpire(lockKey, token, SUMMARY_LOCK_EXPIRE);
        if (!Boolean.TRUE.equals(lock)) {
            return new ArticleSummaryResVo().setArticleId(articleId)
                    .setStatus(ArticleSummaryStatusEnum.GENERATING)
                    .setMessage("总结生成中，请稍后刷新");
        }

        try {
            // double check
            ArticleSummaryResVo afterLock = readFromRedis(articleId, contentHash);
            if (afterLock != null) {
                return afterLock;
            }

            ArticleSummaryCardDTO summary = articleSummaryAgentOrchestrator.run(articleId, article.getTitle(), content);
            long now = System.currentTimeMillis();
            ArticleAiSummaryDO save = new ArticleAiSummaryDO();
            save.setArticleId(articleId);
            save.setSummaryJson(JsonUtil.toStr(summary));
            save.setModel("spring-ai-openai-compatible");
            save.setPromptVersion("article-summary-v1");
            save.setContentHash(contentHash);
            save.setStatus(1);
            save.setDeleted(YesOrNoEnum.NO.getCode());
            save.setCreateTime(new Date(now));
            save.setUpdateTime(new Date(now));
            articleAiSummaryDao.saveOrUpdateByArticleId(save);

            cache(articleId, contentHash, summary, now);
            return ready(articleId, summary, now);
        } catch (Exception e) {
            log.error("生成文章总结失败 articleId={}", articleId, e);
            ArticleAiSummaryDO fail = new ArticleAiSummaryDO();
            fail.setArticleId(articleId);
            fail.setStatus(2);
            fail.setErrorMsg(e.getMessage());
            fail.setDeleted(YesOrNoEnum.NO.getCode());
            fail.setUpdateTime(new Date());
            articleAiSummaryDao.saveOrUpdateByArticleId(fail);
            return new ArticleSummaryResVo().setArticleId(articleId)
                    .setStatus(ArticleSummaryStatusEnum.FAILED)
                    .setMessage("生成失败，请重试");
        } finally {
            String lockVal = RedisClient.getStr(lockKey);
            if (StringUtils.equals(lockVal, token)) {
                RedisClient.del(lockKey);
            }
        }
    }

    private ArticleSummaryResVo ready(Long articleId, ArticleSummaryCardDTO summary, long ts) {
        return new ArticleSummaryResVo()
                .setArticleId(articleId)
                .setStatus(ArticleSummaryStatusEnum.READY)
                .setSummary(summary)
                .setUpdatedAt(ts)
                .setMessage("ok");
    }

    private ArticleSummaryResVo readFromRedis(Long articleId, String contentHash) {
        String cache = RedisClient.getStr(String.format(SUMMARY_CACHE_KEY, articleId));
        if (StringUtils.isBlank(cache)) {
            return null;
        }
        SummaryCacheData data = JsonUtil.toObj(cache, SummaryCacheData.class);
        if (!StringUtils.equals(data.getContentHash(), contentHash) || data.getSummary() == null) {
            return null;
        }
        return ready(articleId, data.getSummary(), data.getUpdatedAt());
    }

    private void cache(Long articleId, String contentHash, ArticleSummaryCardDTO summary, long updatedAt) {
        SummaryCacheData data = new SummaryCacheData();
        data.setContentHash(contentHash);
        data.setSummary(summary);
        data.setUpdatedAt(updatedAt);
        RedisClient.setStrWithExpire(String.format(SUMMARY_CACHE_KEY, articleId), JsonUtil.toStr(data), SUMMARY_CACHE_EXPIRE);
    }

    @Data
    public static class SummaryCacheData {
        private String contentHash;
        private ArticleSummaryCardDTO summary;
        private Long updatedAt;
    }
}
