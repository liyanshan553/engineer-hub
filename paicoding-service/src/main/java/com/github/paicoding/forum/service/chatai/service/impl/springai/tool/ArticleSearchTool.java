package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import com.github.paicoding.forum.api.model.vo.article.dto.SimpleArticleDTO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章搜索工具 - 让 AI 能够搜索技术派社区的文章
 * 当用户问到与社区文章相关的内容时，大模型会自动调用此工具检索相关文章
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Component
@FunctionTool(
        name = "article_search",
        description = "在技术派社区中搜索技术文章。当用户询问社区中有哪些文章、搜索特定技术主题的文章时，调用此工具。参数 keyword 为搜索关键词。"
)
public class ArticleSearchTool implements IFunctionToolService {

    @Autowired
    private ArticleReadService articleReadService;

    @Override
    public String execute(Map<String, Object> params) {
        String keyword = (String) params.getOrDefault("keyword", "");
        log.info("ArticleSearchTool 被调用, keyword={}", keyword);

        try {
            List<SimpleArticleDTO> articles = articleReadService.querySimpleArticleBySearchKey(keyword);
            if (articles == null || articles.isEmpty()) {
                return "未找到与「" + keyword + "」相关的文章。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到以下相关文章:\n");
            int count = Math.min(articles.size(), 5);
            for (int i = 0; i < count; i++) {
                SimpleArticleDTO article = articles.get(i);
                sb.append(String.format("%d. 《%s》 - ID:%s\n", i + 1, article.getTitle(), article.getId()));
            }
            if (articles.size() > 5) {
                sb.append(String.format("...共找到 %d 篇相关文章\n", articles.size()));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("文章搜索失败: {}", e.getMessage(), e);
            return "文章搜索功能暂时不可用，请稍后再试。";
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词，如 'Spring Boot'、'Redis缓存' 等技术主题");
        properties.put("keyword", keyword);

        schema.put("properties", properties);

        Map<String, Object>[] required = new Map[]{new HashMap<String, Object>() {{
        }}};
        schema.put("required", new String[]{"keyword"});
        return schema;
    }
}
