# AI 文章总结卡片 — 完整实现方案

## 一、整体架构

```
用户点击"AI总结"按钮
       │
       ▼
  GET /article/api/summary/{articleId}
       │
       ▼
ArticleSummaryRestController
       │
       ├─ ① Redis 读缓存 ─→ 命中 ─→ 直接返回 ArticleSummaryDTO
       │
       ├─ ② Redis SETNX 分布式锁（防多用户并发重复调用）
       │      ├─ 拿到锁 ─→ 进入 Agent 流程
       │      └─ 没拿到 ─→ 返回 { status: "generating" }，前端轮询
       │
       └─ ③ ArticleSummaryAgentService（Spring AI Agent 编排）
              │
              ├─ Step 1: 获取文章全文 + 切分
              ├─ Step 2: 调用大模型，生成结构化 JSON
              ├─ Step 3: 校验 + 修正 JSON 字段
              │
              ├─ 成功 → 写入 MySQL + Redis 缓存 + 释放锁
              └─ 失败 → 释放锁 + 设置失败标记
```

## 二、新增文件清单

```
paicoding-api/
  └─ src/main/java/com/github/paicoding/forum/api/model/vo/article/dto/
      └─ ArticleSummaryDTO.java               # 总结卡片 DTO

paicoding-service/
  └─ src/main/java/com/github/paicoding/forum/service/article/
      ├─ repository/
      │   ├─ entity/ArticleSummaryDO.java      # 数据库实体
      │   ├─ dao/ArticleSummaryDao.java        # MyBatis-Plus DAO
      │   └─ mapper/ArticleSummaryMapper.java  # Mapper 接口
      ├─ service/
      │   ├─ ArticleSummaryService.java        # 总结服务接口
      │   └─ impl/ArticleSummaryServiceImpl.java # 总结服务实现
      └─ summary/
          └─ ArticleSummaryAgentService.java   # Spring AI Agent 编排

paicoding-web/
  └─ src/main/java/com/github/paicoding/forum/web/front/article/rest/
      └─ ArticleSummaryRestController.java     # REST 接口

paicoding-web/
  └─ src/main/resources/liquibase/data/
      └─ update_schema_260220.sql              # 建表 SQL

paicoding-ui/
  └─ src/main/resources/
      ├─ templates/views/article-detail/
      │   └─ side-summary-card/index.html      # 总结卡片 Thymeleaf 模板
      ├─ static/js/biz/
      │   └─ article-summary.js                # 前端交互 JS
      └─ static/css/views/
          └─ article-summary.css               # 卡片样式
```

## 三、数据库设计

### 表 `article_summary`

```sql
CREATE TABLE `article_summary` (
    `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `article_id`       BIGINT UNSIGNED NOT NULL COMMENT '文章ID',
    `tldr`             VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '一句话总结',
    `highlights`       TEXT          COMMENT '要点列表JSON数组',
    `who_should_read`  VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '适用人群',
    `key_terms`        TEXT          COMMENT '关键术语JSON数组 [{term,explanation}]',
    `code_or_steps`    TEXT          COMMENT '代码步骤JSON数组',
    `risks_or_pitfalls` TEXT         COMMENT '坑点注意事项JSON数组',
    `citations`        TEXT          COMMENT '引用锚点JSON数组 [{text,anchor}]',
    `version`          INT           NOT NULL DEFAULT 1 COMMENT '总结版本（文章更新后可重新生成）',
    `status`           TINYINT       NOT NULL DEFAULT 1 COMMENT '1-正常 0-生成中 -1-失败',
    `create_time`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_id` (`article_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章AI总结';
```

JSON 字段示例：
- `highlights`: `["Spring Boot 自动装配原理基于条件注解","@EnableAutoConfiguration 触发自动配置加载"]`
- `key_terms`: `[{"term":"自动装配","explanation":"Spring Boot 根据依赖自动配置 Bean 的机制"}]`
- `citations`: `[{"text":"SpringFactoriesLoader 加载","anchor":"p:3:0:15"}]`

## 四、各层实现方案详解

### 4.1 DTO 层 — `ArticleSummaryDTO`

```java
@Data
public class ArticleSummaryDTO {
    private Long articleId;
    private String tldr;                          // 1~2句结论
    private List<String> highlights;              // 3~6条要点
    private String whoShouldRead;                 // 适用人群
    private List<KeyTermDTO> keyTerms;            // 术语列表
    private List<String> codeOrSteps;             // 代码/步骤
    private List<String> risksOrPitfalls;         // 坑点/注意
    private List<CitationDTO> citations;          // 引用锚点
    private Integer status;                       // 0生成中 1完成 -1失败

    @Data
    public static class KeyTermDTO {
        private String term;
        private String explanation;
    }

    @Data
    public static class CitationDTO {
        private String text;        // 引用文本
        private String anchor;      // 锚点定位（elementTag:elementIndex:startOffset:endOffset）
    }
}
```

### 4.2 数据库实体 — `ArticleSummaryDO`

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "article_summary", autoResultMap = true)
public class ArticleSummaryDO extends BaseDO {
    private Long articleId;
    private String tldr;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> highlights;

    private String whoShouldRead;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ArticleSummaryDTO.KeyTermDTO> keyTerms;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> codeOrSteps;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> risksOrPitfalls;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<ArticleSummaryDTO.CitationDTO> citations;

    private Integer version;
    private Integer status;  // 0=生成中, 1=完成, -1=失败
}
```

### 4.3 Redis 缓存与分布式锁设计

**缓存 Key 规范**（复用现有 `RedisClient` 工具）：

| 用途 | Key | 类型 | 过期时间 |
|------|-----|------|----------|
| 总结缓存 | `article.summary:{articleId}` | String (JSON) | 24h |
| 生成锁 | `article.summary.lock:{articleId}` | String | 120s (兜底) |
| 生成状态 | `article.summary.status:{articleId}` | String | 300s |

**分布式锁实现**（基于现有 `RedisClient`，新增 `setNx` 方法）：

```java
// 在 RedisClient 中新增
public static Boolean setNx(String key, String value, Long expireSeconds) {
    return template.execute((RedisCallback<Boolean>) con -> {
        Boolean result = con.setNX(keyBytes(key), valBytes(value));
        if (Boolean.TRUE.equals(result)) {
            con.expire(keyBytes(key), expireSeconds);
        }
        return result;
    });
}
```

### 4.4 Service 层 — `ArticleSummaryServiceImpl`

核心流程（伪代码）：

```java
@Service
public class ArticleSummaryServiceImpl implements ArticleSummaryService {

    private static final String CACHE_KEY = "article.summary:";
    private static final String LOCK_KEY  = "article.summary.lock:";
    private static final String STATUS_KEY = "article.summary.status:";

    public ArticleSummaryDTO querySummary(Long articleId) {
        // 1. 读 Redis 缓存
        ArticleSummaryDTO cached = RedisClient.getStr(CACHE_KEY + articleId) → 反序列化;
        if (cached != null) return cached;

        // 2. 读 MySQL
        ArticleSummaryDO record = summaryDao.getByArticleId(articleId);
        if (record != null && record.getStatus() == 1) {
            // 写回 Redis 缓存，24h 过期
            ArticleSummaryDTO dto = convert(record);
            RedisClient.setStrWithExpire(CACHE_KEY + articleId, JSON(dto), 86400L);
            return dto;
        }

        // 3. 正在生成中
        if (record != null && record.getStatus() == 0) {
            return new ArticleSummaryDTO() {{ setStatus(0); }};
        }

        // 4. 不存在 → 触发异步生成
        return triggerGenerate(articleId);
    }

    private ArticleSummaryDTO triggerGenerate(Long articleId) {
        // SETNX 分布式锁，120s 兜底过期
        Boolean locked = RedisClient.setNx(LOCK_KEY + articleId, "1", 120L);
        if (!Boolean.TRUE.equals(locked)) {
            // 有其他请求正在生成，返回"生成中"
            return new ArticleSummaryDTO() {{ setStatus(0); setArticleId(articleId); }};
        }

        try {
            // 先写一条 status=0 的记录（占位，防止重复写入）
            ArticleSummaryDO placeholder = new ArticleSummaryDO();
            placeholder.setArticleId(articleId);
            placeholder.setStatus(0);
            summaryDao.save(placeholder);

            // 异步执行 AI Agent
            AsyncUtil.execute(() -> {
                try {
                    ArticleSummaryDTO result = agentService.generateSummary(articleId);
                    // 更新 MySQL
                    updateRecord(articleId, result, 1);
                    // 写入 Redis 缓存
                    RedisClient.setStrWithExpire(CACHE_KEY + articleId, JSON(result), 86400L);
                } catch (Exception e) {
                    updateRecord(articleId, null, -1);
                } finally {
                    RedisClient.del(LOCK_KEY + articleId);
                }
            });

            return new ArticleSummaryDTO() {{ setStatus(0); setArticleId(articleId); }};
        } catch (Exception e) {
            RedisClient.del(LOCK_KEY + articleId);
            throw e;
        }
    }
}
```

### 4.5 Spring AI Agent 编排 — `ArticleSummaryAgentService`

```java
@Service
@RequiredArgsConstructor
public class ArticleSummaryAgentService {
    private final ChatClient.Builder chatClientBuilder;
    private final EmbeddingModel embeddingModel;
    private final ArticleReadService articleReadService;

    // Agent 步骤编排
    public ArticleSummaryDTO generateSummary(Long articleId) {
        // ========== Step 1: 获取文章内容 ==========
        String articleContent = articleReadService.queryArticleContentForAI(articleId);
        ArticleDO article = articleReadService.queryBasicArticle(articleId);

        // ========== Step 2: RAG — 向量切分 + 检索关键片段 ==========
        List<Document> docs = splitDocuments(articleContent, 800);
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(docs);
        // 用文章标题做检索，拿核心片段
        List<Document> coreFragments = vectorStore.similaritySearch(
            SearchRequest.builder().query(article.getTitle()).topK(6).build());

        // ========== Step 3: 构建 Prompt + 调用大模型 ==========
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(article.getTitle(), articleContent, coreFragments);

        String jsonResponse = chatClientBuilder.build()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        // ========== Step 4: 解析 + 校验 JSON ==========
        ArticleSummaryDTO dto = parseAndValidate(jsonResponse, articleId);

        // ========== Step 5: 引用锚点匹配（citations 防止瞎编）==========
        enrichCitations(dto, articleContent);

        return dto;
    }
}
```

**System Prompt 设计**：

```text
你是一个专业的技术文章分析师。请根据用户提供的技术文章，生成一份结构化的总结卡片。
严格按照以下 JSON 格式返回，不要添加任何其他文字：
{
  "tldr": "1~2句话总结文章核心结论",
  "highlights": ["要点1（≤30字）", "要点2", ...],  // 3~6条
  "who_should_read": "适用人群描述和所需前置知识",
  "key_terms": [{"term": "术语", "explanation": "简短解释"}],
  "code_or_steps": ["步骤1", "步骤2", ...],   // 如无代码/步骤则返回空数组
  "risks_or_pitfalls": ["注意事项1", ...],     // 如无则返回空数组
  "citations": [{"text": "引用的原文片段(10~30字)", "paragraph_index": 段落序号}]
}

约束规则：
- highlights 每条不超过 30 个中文字符
- citations 中的 text 必须是文章中原文出现的片段，不可编造
- key_terms 最多 8 个
- code_or_steps 仅在文章包含代码示例或步骤说明时填写，3~5步
- 所有内容使用中文
```

**User Prompt 构建**：

```text
# 文章标题
{title}

# 文章全文
{articleContent}

# 核心片段（RAG检索结果）
{coreFragments}

请生成总结卡片 JSON。
```

### 4.6 Controller 层

```java
@RestController
@RequestMapping("article/api")
public class ArticleSummaryRestController {

    @Autowired
    private ArticleSummaryService summaryService;

    /**
     * 获取文章AI总结
     * 首次请求触发生成，返回 status=0；前端轮询直到 status=1
     */
    @GetMapping("summary/{articleId}")
    public ResVo<ArticleSummaryDTO> getSummary(@PathVariable Long articleId) {
        ArticleSummaryDTO dto = summaryService.querySummary(articleId);
        return ResVo.ok(dto);
    }

    /**
     * 重新生成总结（文章内容更新后调用）
     */
    @Permission(role = UserRole.LOGIN)
    @PostMapping("summary/regenerate/{articleId}")
    public ResVo<Boolean> regenerate(@PathVariable Long articleId) {
        summaryService.regenerate(articleId);
        return ResVo.ok(true);
    }
}
```

### 4.7 前端实现

**article-detail/index.html** 右侧栏新增卡片位置（在用户卡片之后、侧边推荐之前）：

```html
<!-- AI 总结卡片 -->
<div id="aiSummaryCard" class="right-container ai-summary-card" style="display:none;">
    <div class="widget">
        <h3 class="com-nav-bar-title">AI 总结</h3>
        <div id="summaryContent" class="summary-body">
            <!-- JS 动态渲染 -->
        </div>
    </div>
</div>
```

**article-summary.js** 核心逻辑：

```javascript
$(document).ready(function() {
    loadSummary();
});

function loadSummary() {
    $.get('/article/api/summary/' + articleId, function(res) {
        if (res.status.code === 0 && res.result) {
            const data = res.result;
            if (data.status === 1) {
                renderSummaryCard(data);
            } else if (data.status === 0) {
                showGenerating();
                // 3秒后轮询
                setTimeout(loadSummary, 3000);
            }
            // status=-1 失败时显示"重试"按钮
        }
    });
}

function renderSummaryCard(data) {
    let html = '';
    // TLDR
    html += '<div class="summary-tldr"><strong>TL;DR</strong><p>' + data.tldr + '</p></div>';
    // Highlights
    html += '<div class="summary-section"><strong>要点</strong><ul>';
    data.highlights.forEach(h => html += '<li>' + h + '</li>');
    html += '</ul></div>';
    // Who should read
    html += '<div class="summary-section"><strong>适合谁读</strong><p>' + data.whoShouldRead + '</p></div>';
    // Key terms (hover 显示解释)
    if (data.keyTerms && data.keyTerms.length) {
        html += '<div class="summary-section"><strong>关键术语</strong><div class="term-tags">';
        data.keyTerms.forEach(t =>
            html += '<span class="term-tag" title="' + t.explanation + '">' + t.term + '</span>');
        html += '</div></div>';
    }
    // Steps
    if (data.codeOrSteps && data.codeOrSteps.length) {
        html += '<div class="summary-section"><strong>关键步骤</strong><ol>';
        data.codeOrSteps.forEach(s => html += '<li>' + s + '</li>');
        html += '</ol></div>';
    }
    // Risks
    if (data.risksOrPitfalls && data.risksOrPitfalls.length) {
        html += '<div class="summary-section"><strong>注意事项</strong><ul class="risk-list">';
        data.risksOrPitfalls.forEach(r => html += '<li>⚠ ' + r + '</li>');
        html += '</ul></div>';
    }
    $('#summaryContent').html(html);
    $('#aiSummaryCard').show();
}
```

## 五、幂等与并发控制详细方案

```
时序图：多用户同时请求同一文章总结

User A ──GET /summary/100──► Service.querySummary(100)
                              │
                              ├─ Redis GET → miss
                              ├─ MySQL SELECT → null
                              ├─ Redis SETNX lock:100 → TRUE（拿到锁）
                              ├─ MySQL INSERT status=0
                              ├─ AsyncUtil.execute(AI Agent)
                              └─ return { status: 0 }

User B ──GET /summary/100──► Service.querySummary(100)
                              │
                              ├─ Redis GET → miss
                              ├─ MySQL SELECT → status=0
                              └─ return { status: 0 }  ← 直接返回，不重复调用AI

User C ──GET /summary/100──► (同 User B)

...3~10 秒后，AI Agent 完成...

Agent Thread:
  ├─ MySQL UPDATE status=1, 写入总结内容
  ├─ Redis SET cache → 24h TTL
  └─ Redis DEL lock:100

User A ──GET /summary/100──► (轮询)
                              ├─ Redis GET → hit!
                              └─ return { status: 1, tldr: "...", ... }
```

三层防线：
1. **Redis 缓存**：命中直接返回，无需查库
2. **MySQL status 字段**：`status=0` 表示已有任务在执行，直接返回"生成中"
3. **Redis SETNX 锁**：保证只有一个线程执行 AI 调用，120s 兜底过期防死锁

## 六、引用锚点（Citations）实现方案

为避免 AI "瞎编"引用，采用**后处理校验**策略：

```java
private void enrichCitations(ArticleSummaryDTO dto, String articleContent) {
    if (dto.getCitations() == null) return;

    Iterator<ArticleSummaryDTO.CitationDTO> it = dto.getCitations().iterator();
    // 将文章按段落切分
    String[] paragraphs = articleContent.split("\n");

    while (it.hasNext()) {
        CitationDTO citation = it.next();
        // 校验：引用文本必须在原文中存在
        int idx = articleContent.indexOf(citation.getText());
        if (idx < 0) {
            // 原文中找不到 → 移除这条引用
            it.remove();
            continue;
        }
        // 计算所在段落索引，构建锚点
        int paragraphIndex = findParagraphIndex(paragraphs, citation.getText());
        int startOffset = paragraphs[paragraphIndex].indexOf(citation.getText());
        citation.setAnchor("p:" + paragraphIndex + ":" + startOffset + ":"
            + (startOffset + citation.getText().length()));
    }
}
```

前端根据 `anchor` 定位并高亮：点击引用条目 → 滚动到对应段落 → 高亮选中文本。

## 七、配置项

在 `application-ai.yml` 中新增：

```yaml
ai:
  summary:
    enabled: true
    cache-ttl: 86400          # Redis 缓存 24h
    lock-ttl: 120             # 分布式锁 120s
    max-article-length: 30000 # 超长文章截断
    model: deepseek-chat      # 使用的模型
```

## 八、实现步骤（开发顺序）

1. **Liquibase 建表** — `update_schema_260220.sql`
2. **API 层** — `ArticleSummaryDTO`（含内嵌 KeyTermDTO、CitationDTO）
3. **Redis 工具扩展** — `RedisClient.setNx()`
4. **数据库层** — `ArticleSummaryDO` + `ArticleSummaryMapper` + `ArticleSummaryDao`
5. **AI Agent 服务** — `ArticleSummaryAgentService`（Spring AI ChatClient + RAG）
6. **业务 Service** — `ArticleSummaryServiceImpl`（缓存 + 锁 + 异步调度）
7. **Controller** — `ArticleSummaryRestController`
8. **前端** — HTML 模板 + JS + CSS
9. **详情页集成** — 修改 `article-detail/index.html` 引入卡片
