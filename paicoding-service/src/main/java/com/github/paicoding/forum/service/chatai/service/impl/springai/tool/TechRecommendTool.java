package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技术学习路线推荐工具 - 让 AI 能够根据技术方向给出学习推荐
 * 基于 Prompt 编写的纯逻辑工具，展示了 Function Tool 不依赖外部服务的用法
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Component
@FunctionTool(
        name = "tech_recommend",
        description = "推荐技术学习路线和资源。当用户询问某个技术方向的学习路线、入门建议、技术选型等时，调用此工具。参数 direction 为技术方向，如 'Java后端'、'前端'、'大数据' 等。"
)
public class TechRecommendTool implements IFunctionToolService {

    @Override
    public String execute(Map<String, Object> params) {
        String direction = (String) params.getOrDefault("direction", "Java后端");
        log.info("TechRecommendTool 被调用, direction={}", direction);

        // 基于不同技术方向，返回推荐的学习路线
        String lowerDirection = direction.toLowerCase();
        if (lowerDirection.contains("java") || lowerDirection.contains("后端") || lowerDirection.contains("backend")) {
            return buildJavaBackendRecommendation();
        } else if (lowerDirection.contains("前端") || lowerDirection.contains("front") || lowerDirection.contains("vue") || lowerDirection.contains("react")) {
            return buildFrontendRecommendation();
        } else if (lowerDirection.contains("大数据") || lowerDirection.contains("big data") || lowerDirection.contains("数据")) {
            return buildBigDataRecommendation();
        } else if (lowerDirection.contains("ai") || lowerDirection.contains("机器学习") || lowerDirection.contains("深度学习") || lowerDirection.contains("人工智能")) {
            return buildAIRecommendation();
        } else {
            return "关于「" + direction + "」方向的技术学习路线，建议:\n"
                    + "1. 先掌握该领域的基础概念和核心原理\n"
                    + "2. 通过官方文档和经典书籍系统学习\n"
                    + "3. 动手做项目实践，在技术派社区分享学习心得\n"
                    + "4. 关注该领域的技术博客和开源项目\n"
                    + "5. 加入技术社区交流，持续精进";
        }
    }

    private String buildJavaBackendRecommendation() {
        return "Java后端学习路线推荐:\n"
                + "【基础阶段】Java基础 → 集合框架 → JVM原理 → 并发编程\n"
                + "【框架阶段】Spring → Spring Boot → Spring Cloud → MyBatis-Plus\n"
                + "【中间件】Redis → RabbitMQ → ElasticSearch → MongoDB\n"
                + "【进阶】微服务架构 → 分布式事务 → 容器化(Docker/K8s)\n"
                + "【推荐项目】技术派(PaiCoding)是一个优秀的Spring Boot实战项目，涵盖了以上大部分技术栈";
    }

    private String buildFrontendRecommendation() {
        return "前端学习路线推荐:\n"
                + "【基础阶段】HTML/CSS → JavaScript(ES6+) → TypeScript\n"
                + "【框架阶段】Vue 3 / React → 状态管理(Pinia/Redux) → 路由\n"
                + "【工程化】Webpack/Vite → 组件库 → 自动化测试\n"
                + "【进阶】性能优化 → SSR → 微前端 → 跨端开发";
    }

    private String buildBigDataRecommendation() {
        return "大数据学习路线推荐:\n"
                + "【基础阶段】Linux → SQL → Python/Java\n"
                + "【核心组件】Hadoop → Hive → Spark → Flink\n"
                + "【存储】HBase → Kafka → ClickHouse\n"
                + "【进阶】数据仓库建模 → 实时计算 → 数据治理";
    }

    private String buildAIRecommendation() {
        return "AI/机器学习学习路线推荐:\n"
                + "【数学基础】线性代数 → 概率统计 → 微积分\n"
                + "【编程基础】Python → NumPy/Pandas → Matplotlib\n"
                + "【机器学习】Scikit-learn → 经典算法 → 特征工程\n"
                + "【深度学习】PyTorch/TensorFlow → CNN/RNN/Transformer\n"
                + "【前沿方向】大模型(LLM) → RAG → Agent → MCP(Model Context Protocol)";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "string");
        direction.put("description", "技术方向，如 'Java后端'、'前端'、'大数据'、'AI/机器学习' 等");
        properties.put("direction", direction);

        schema.put("properties", properties);
        schema.put("required", new String[]{"direction"});
        return schema;
    }
}
