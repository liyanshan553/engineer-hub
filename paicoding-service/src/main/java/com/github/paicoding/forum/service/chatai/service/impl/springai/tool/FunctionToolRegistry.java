package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolFunction;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Function Tool 注册中心
 * 自动扫描所有被 @FunctionTool 注解标注的 Bean，注册为大模型可调用的工具
 * 参考 Spring AI 的 ToolCallbackProvider 设计思路
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Slf4j
@Component
public class FunctionToolRegistry {

    private final List<IFunctionToolService> toolServices;
    private final Map<String, IFunctionToolService> toolMap = new HashMap<>();
    private final Map<String, FunctionTool> toolMetaMap = new HashMap<>();

    public FunctionToolRegistry(List<IFunctionToolService> toolServices) {
        this.toolServices = toolServices;
    }

    @PostConstruct
    public void init() {
        for (IFunctionToolService service : toolServices) {
            FunctionTool annotation = service.getClass().getAnnotation(FunctionTool.class);
            if (annotation != null) {
                toolMap.put(annotation.name(), service);
                toolMetaMap.put(annotation.name(), annotation);
                log.info("注册 Function Tool: {} - {}", annotation.name(), annotation.description());
            }
        }
        log.info("共注册 {} 个 Function Tool", toolMap.size());
    }

    /**
     * 根据工具名称获取工具服务
     */
    public IFunctionToolService getTool(String name) {
        return toolMap.get(name);
    }

    /**
     * 执行工具调用
     *
     * @param toolName  工具名称
     * @param arguments 大模型传入的 JSON 参数字符串
     * @return 工具执行结果
     */
    public String executeTool(String toolName, String arguments) {
        IFunctionToolService tool = toolMap.get(toolName);
        if (tool == null) {
            return "未找到工具: " + toolName;
        }
        try {
            Map<String, Object> params = JsonUtil.toObj(arguments, Map.class);
            if (params == null) {
                params = new HashMap<>();
            }
            return tool.execute(params);
        } catch (Exception e) {
            log.error("执行工具 {} 失败: {}", toolName, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 构建 DashScope Function Calling 所需的 ToolFunction 列表
     * 将所有注册的工具转换为大模型能识别的工具描述格式
     */
    public List<ToolFunction> buildToolFunctions() {
        List<ToolFunction> tools = new ArrayList<>();
        for (Map.Entry<String, FunctionTool> entry : toolMetaMap.entrySet()) {
            String name = entry.getKey();
            FunctionTool meta = entry.getValue();
            IFunctionToolService service = toolMap.get(name);

            // 将参数 schema 转为 JsonObject
            String schemaJson = JsonUtil.toStr(service.getParameterSchema());
            JsonObject parametersJsonObject = JsonParser.parseString(schemaJson).getAsJsonObject();

            FunctionDefinition fd = FunctionDefinition.builder()
                    .name(name)
                    .description(meta.description())
                    .parameters(parametersJsonObject)
                    .build();

            tools.add(ToolFunction.builder().function(fd).build());
        }
        return tools;
    }

    /**
     * 判断是否有注册的工具
     */
    public boolean hasTools() {
        return !toolMap.isEmpty();
    }

    /**
     * 获取所有已注册的工具名称
     */
    public List<String> getToolNames() {
        return new ArrayList<>(toolMap.keySet());
    }
}
