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
                return ArticleSummaryDTO.generating(articleId);
            }
            if (record.getStatus() == -1) {
                // 失败：返回失败 DTO，允许前端展示重试按钮
                return ArticleSummaryDTO.failed(articleId, record.getFailReason());
            }
        }

        // === 第三层：触发异步生成 ===
        return triggerGenerate(articleId, record);
    }

    @Override
    public void regenerate(Long articleId) {
        String lockKey = LOCK_KEY_PREFIX + articleId;

        // 用 SETNX 保证 regenerate 也不会并发
        Boolean locked = RedisClient.setNx(lockKey, "1", LOCK_TTL_SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("regenerate 时锁已被持有，跳过, articleId={}", articleId);
            return;
        }

        try {
            // 删除缓存
            RedisClient.del(CACHE_KEY_PREFIX + articleId);

            // 将现有记录状态重置为 0（生成中），而非删除再插入
            ArticleSummaryDO existing = summaryDao.getByArticleId(articleId);
            if (existing != null) {
                existing.setStatus(0);
                existing.setFailReason("");
                existing.setVersion(existing.getVersion() + 1);
                summaryDao.updateByArticleId(existing);
            } else {
                ArticleSummaryDO placeholder = new ArticleSummaryDO();
                placeholder.setArticleId(articleId);
                placeholder.setStatus(0);
                placeholder.setVersion(1);
                summaryDao.save(placeholder);
            }

            // 异步执行
            doAsyncGenerate(articleId, lockKey);
        } catch (Exception e) {
            RedisClient.del(lockKey);
            throw e;
        }
    }

    /**
     * 触发异步生成总结
     */
    private ArticleSummaryDTO triggerGenerate(Long articleId, ArticleSummaryDO existing) {
        String lockKey = LOCK_KEY_PREFIX + articleId;

        // Redis SETNX 分布式锁
        Boolean locked = RedisClient.setNx(lockKey, "1", LOCK_TTL_SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.info("文章总结正在生成中（锁已被持有），articleId={}", articleId);
            return ArticleSummaryDTO.generating(articleId);
        }

        try {
            // 先写一条 status=0 的占位记录
            if (existing == null) {
                ArticleSummaryDO placeholder = new ArticleSummaryDO();
                placeholder.setArticleId(articleId);
                placeholder.setStatus(0);
                placeholder.setVersion(1);
                summaryDao.save(placeholder);
            } else if (existing.getStatus() == -1) {
                existing.setStatus(0);
                existing.setFailReason("");
                summaryDao.updateByArticleId(existing);
            }

            doAsyncGenerate(articleId, lockKey);
            return ArticleSummaryDTO.generating(articleId);
        } catch (Exception e) {
            RedisClient.del(lockKey);
            throw e;
        }
    }

    /**
     * 异步执行 AI Agent 生成
     */
    private void doAsyncGenerate(Long articleId, String lockKey) {
        AsyncUtil.execute(() -> {
            try {
                log.info("开始异步生成文章总结, articleId={}", articleId);
                ArticleSummaryDTO result = agentService.generateSummary(articleId);
                result.ensureNonNull();

                // 更新 MySQL：写入总结内容，status=1
                updateRecord(articleId, result);

                // 写入 Redis 缓存
                String cacheKey = CACHE_KEY_PREFIX + articleId;
                RedisClient.setStrWithExpire(cacheKey, JsonUtil.toStr(result), CACHE_TTL_SECONDS);

                log.info("文章总结生成成功, articleId={}", articleId);
            } catch (Exception e) {
                log.error("文章总结生成失败, articleId={}", articleId, e);
                String reason = e.getMessage();
                if (reason != null && reason.length() > 450) {
                    reason = reason.substring(0, 450);
                }
                summaryDao.markFailed(articleId, reason != null ? reason : "未知错误");
            } finally {
                RedisClient.del(lockKey);
            }
        });
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
        record.setModelName(dto.getModelName());
        record.setStatus(1);
        record.setFailReason("");
        summaryDao.updateByArticleId(record);
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
        dto.setModelName(record.getModelName());
        dto.setVersion(record.getVersion());
        dto.setStatus(record.getStatus());
        dto.ensureNonNull();
        return dto;
    }
}
