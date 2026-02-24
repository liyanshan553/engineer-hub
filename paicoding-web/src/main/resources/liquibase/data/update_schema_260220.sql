CREATE TABLE IF NOT EXISTS `article_ai_summary` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `article_id` bigint(20) unsigned NOT NULL COMMENT '文章ID',
  `summary_json` longtext COMMENT '总结卡片JSON',
  `model` varchar(128) DEFAULT NULL COMMENT '模型名称',
  `prompt_version` varchar(64) DEFAULT NULL COMMENT '提示词版本',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '文章内容哈希',
  `status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '状态 0-初始化 1-成功 2-失败',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '失败原因',
  `deleted` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_article_id` (`article_id`),
  KEY `idx_status_update_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章AI总结';
