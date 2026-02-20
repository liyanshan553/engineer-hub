package com.github.paicoding.forum.service.article.service.impl;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSummaryDTO;
import com.github.paicoding.forum.core.async.AsyncUtil;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.article.repository.dao.ArticleSummaryDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSummaryDO;
import com.github.paicoding.forum.service.article.service.ArticleSummaryService;
import com.github.paicoding.forum.service.article.summary.ArticleSummaryAgentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文章AI总结服务实现
 *
 * <p>三层防线保证幂等：
 * 1. Redis 缓存命中直接返回
 * 2. MySQL status 字段判断是否已有任务在执行
 * 3. Redis SETNX 分布式锁保证只有一个线程调用大模型
 * </p>
 *
 * @author Claude
 * @date 2026/2/20
 */
@Slf4j
@Service
public class ArticleSummaryServiceImpl implements ArticleSummaryService {

    private static final String CACHE_KEY_PREFIX = "article.summary:";
    private static final String LOCK_KEY_PREFIX = "article.summary.lock:";
    /** 缓存过期时间 24h */
    private static final long CACHE_TTL_SECONDS = 86400L;
    /** 分布式锁过期时间 300s（Agent 包含多轮 LLM 调用 + 可能的 REFINE 回路） */
    private static final long LOCK_TTL_SECONDS = 300L;

    @Autowired
    private ArticleSummaryDao summaryDao;

    @Autowired
    private ArticleSummaryAgentService agentService;

    @Override
    public ArticleSummaryDTO querySummary(Long articleId) {
        // === 第一层：Redis 缓存 ===
        String cacheKey = CACHE_KEY_PREFIX + articleId;
        String cached = RedisClient.getStr(cacheKey);
        if (StringUtils.isNotBlank(cached)) {
            ArticleSummaryDTO dto = JsonUtil.toObj(cached, ArticleSummaryDTO.class);
            if (dto != null) {
                return dto;
            }
        }

        // === 第二层：MySQL 查询 ===
        ArticleSummaryDO record = summaryDao.getByArticleId(articleId);
        if (record != null) {
            if (record.getStatus() == 1) {
                // 已完成：写回 Redis 缓存
                ArticleSummaryDTO dto = convertToDTO(record);
                RedisClient.setStrWithExpire(cacheKey, JsonUtil.toStr(dto), CACHE_TTL_SECONDS);
                return dto;
            }
            if (record.getStatus() == 0) {
                // 正在生成中
                return buildGeneratingDTO(articleId);
            }
            // status=-1 失败：允许重新触发
        }

        // === 第三层：触发异步生成 ===
        return triggerGenerate(articleId, record);
    }

    @Override
    public void regenerate(Long articleId) {
        // 删除缓存
        RedisClient.del(CACHE_KEY_PREFIX + articleId);

        // 删除旧记录
        ArticleSummaryDO existing = summaryDao.getByArticleId(articleId);
        if (existing != null) {
            summaryDao.removeById(existing.getId());
        }

        // 触发重新生成
        triggerGenerate(articleId, null);
    }

    /**
     * 触发异步生成总结
     */
    private ArticleSummaryDTO triggerGenerate(Long articleId, ArticleSummaryDO existing) {
        String lockKey = LOCK_KEY_PREFIX + articleId;

        // Redis SETNX 分布式锁
        Boolean locked = RedisClient.setNx(lockKey, "1", LOCK_TTL_SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            // 有其他请求正在生成
            log.info("文章总结正在生成中（锁已被持有），articleId={}", articleId);
            return buildGeneratingDTO(articleId);
        }

        try {
            // 先写一条 status=0 的占位记录
            if (existing == null || existing.getStatus() == -1) {
                if (existing != null) {
                    existing.setStatus(0);
                    summaryDao.updateByArticleId(existing);
                } else {
                    ArticleSummaryDO placeholder = new ArticleSummaryDO();
                    placeholder.setArticleId(articleId);
                    placeholder.setStatus(0);
                    placeholder.setVersion(1);
                    summaryDao.save(placeholder);
                }
            }

            // 异步执行 AI Agent
            AsyncUtil.execute(() -> {
                try {
                    log.info("开始异步生成文章总结, articleId={}", articleId);
                    ArticleSummaryDTO result = agentService.generateSummary(articleId);

                    // 更新 MySQL：写入总结内容，status=1
                    updateRecord(articleId, result);

                    // 写入 Redis 缓存
                    String cacheKey = CACHE_KEY_PREFIX + articleId;
                    RedisClient.setStrWithExpire(cacheKey, JsonUtil.toStr(result), CACHE_TTL_SECONDS);

                    log.info("文章总结生成成功, articleId={}", articleId);
                } catch (Exception e) {
                    log.error("文章总结生成失败, articleId={}", articleId, e);
                    // 标记失败
                    markFailed(articleId);
                } finally {
                    // 释放锁
                    RedisClient.del(lockKey);
                }
            });

            return buildGeneratingDTO(articleId);
        } catch (Exception e) {
            // 异常时释放锁
            RedisClient.del(lockKey);
            throw e;
        }
    }

    private void updateRecord(Long articleId, ArticleSummaryDTO dto) {
        ArticleSummaryDO record = summaryDao.getByArticleId(articleId);
        if (record == null) {
            return;
        }
        record.setTldr(dto.getTldr());
        record.setHighlights(dto.getHighlights());
        record.setWhoShouldRead(dto.getWhoShouldRead());
        record.setKeyTerms(dto.getKeyTerms());
        record.setCodeOrSteps(dto.getCodeOrSteps());
        record.setRisksOrPitfalls(dto.getRisksOrPitfalls());
        record.setCitations(dto.getCitations());
        record.setStatus(1);
        summaryDao.updateByArticleId(record);
    }

    private void markFailed(Long articleId) {
        ArticleSummaryDO record = summaryDao.getByArticleId(articleId);
        if (record != null) {
            record.setStatus(-1);
            summaryDao.updateByArticleId(record);
        }
    }

    private ArticleSummaryDTO convertToDTO(ArticleSummaryDO record) {
        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(record.getArticleId());
        dto.setTldr(record.getTldr());
        dto.setHighlights(record.getHighlights());
        dto.setWhoShouldRead(record.getWhoShouldRead());
        dto.setKeyTerms(record.getKeyTerms());
        dto.setCodeOrSteps(record.getCodeOrSteps());
        dto.setRisksOrPitfalls(record.getRisksOrPitfalls());
        dto.setCitations(record.getCitations());
        dto.setStatus(record.getStatus());
        return dto;
    }

    private ArticleSummaryDTO buildGeneratingDTO(Long articleId) {
        ArticleSummaryDTO dto = new ArticleSummaryDTO();
        dto.setArticleId(articleId);
        dto.setStatus(0);
        return dto;
    }
}
