-- 文章AI总结卡片
-- Author: Claude
-- Date: 2026-02-20

CREATE TABLE IF NOT EXISTS `article_summary` (
    `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `article_id`         BIGINT UNSIGNED NOT NULL COMMENT '文章ID',
    `tldr`               VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '一句话总结',
    `highlights`         TEXT          COMMENT '要点列表JSON数组',
    `who_should_read`    VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '适用人群/前置知识',
    `key_terms`          TEXT          COMMENT '关键术语JSON数组 [{term,explanation}]',
    `code_or_steps`      TEXT          COMMENT '代码步骤JSON数组',
    `risks_or_pitfalls`  TEXT          COMMENT '坑点/注意事项JSON数组',
    `citations`          TEXT          COMMENT '引用锚点JSON数组 [{text,anchor}]',
    `version`            INT           NOT NULL DEFAULT 1 COMMENT '总结版本号',
    `status`             TINYINT       NOT NULL DEFAULT 0 COMMENT '0-生成中 1-正常 -1-失败',
    `create_time`        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章AI总结';
