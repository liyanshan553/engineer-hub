# AI 评论机器人完整链路分析

## 整体架构概览

```
┌──────────────────────────────────────────────────────────────────────┐
│                          前端 (paicoding-ui)                         │
│  comment-list.html  ──→  ai-comment.js  ──→  POST /comment/api/post │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ HTTP Request
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       Web层 (paicoding-web)                          │
│                    CommentRestController.save()                      │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Service层 (paicoding-service)                    │
│  CommentWriteServiceImpl.saveComment()                               │
│       ├─ 保存评论到DB                                                 │
│       └─ aiBotTrigger()  判断是否触发AI机器人                          │
│              │                                                       │
│              ▼                                                       │
│  AiBots.trigger()  ──→  AiBotService.trigger()                       │
│              │                                                       │
│              ▼  (异步线程)                                            │
│  AsyncUtil.execute() {                                               │
│      设置 ReqInfoContext(机器人身份)                                   │
│      SpringAiBotService.ask()                                        │
│          ├─ 构建 RAG 上下文(QA_BOT)                                   │
│          ├─ 调用 Spring AI ChatClient                                 │
│          └─ 回调 aiReply() ──→ saveComment() 保存AI回复               │
│  }                                                                   │
└──────────────────────────────────────────────────────────────────────┘
```

## 涉及的文件清单

| 模块 | 文件路径 | 职责 |
|------|----------|------|
| paicoding-api | `api/model/enums/ai/AiBotEnum.java` | 机器人枚举定义 |
| paicoding-api | `api/model/vo/comment/CommentSaveReq.java` | 评论保存请求DTO |
| paicoding-api | `api/model/vo/comment/dto/HighlightDto.java` | 划线评论数据 |
| paicoding-service | `service/chatai/bot/AiBotService.java` | 机器人初始化与异步触发 |
| paicoding-service | `service/chatai/bot/AiBots.java` | 机器人门面/调度 |
| paicoding-service | `service/chatai/springai/SpringAiBotService.java` | Spring AI集成与RAG |
| paicoding-service | `service/comment/service/impl/CommentWriteServiceImpl.java` | 评论保存与AI触发核心逻辑 |
| paicoding-web | `web/front/comment/rest/CommentRestController.java` | 评论REST接口 |
| paicoding-web | `resources-env/dev/application-ai.yml` | AI模型配置 |
| paicoding-ui | `templates/components/comment/comment-list.html` | 评论区HTML模板 |
| paicoding-ui | `static/js/biz/ai-comment.js` | AI机器人前端交互JS |
| paicoding-core | `core/async/AsyncUtil.java` | 异步执行工具类 |

## 各层详细分析

### 第一层：机器人定义 — AiBotEnum

定义了两个AI机器人：
- **HATER_BOT（杠精派）**：专业杠精角色，对用户评论进行反驳回复
- **QA_BOT（派聪明）**：问答机器人，基于文章内容做RAG检索后回答

### 第二层：机器人初始化 — AiBotService

应用启动后通过 `@EventListener(ApplicationReadyEvent.class)` 自动注册系统用户。

### 第三层：前端触发 — HTML + JavaScript

评论框旁的🤖按钮触发下拉菜单，选择后自动插入 `@机器人名` 标签。

### 第四层：Controller — CommentRestController

接收评论请求，HTML转义后调用Service层。

### 第五层：核心业务 — CommentWriteServiceImpl

双触发机制：
1. 顶级评论中检测 `@机器人` 关键词
2. 回复评论时检测被回复者是否为AI机器人

### 第六层：门面调度 — AiBots

系统提示词缓存 + 触发委托。

### 第七层：异步执行 — AiBotService.trigger()

线程池异步执行AI调用，设置机器人身份上下文。

### 第八层：Spring AI与RAG — SpringAiBotService

轻量级RAG：文档切分 → 向量化 → 相似检索 → Prompt增强。

## 关键设计亮点

1. **双触发机制**：首次@触发 + 后续回复自动触发，实现多轮对线
2. **会话隔离**：sourceBizId = "comment:{topCommentId}_{userId}"
3. **异步非阻塞**：AI调用不阻塞用户评论提交
4. **轻量RAG**：QA_BOT对文章全文做实时向量检索
5. **机器人即用户**：复用现有评论系统，无需额外数据模型
6. **上下文安全**：try-finally清理ReqInfoContext
