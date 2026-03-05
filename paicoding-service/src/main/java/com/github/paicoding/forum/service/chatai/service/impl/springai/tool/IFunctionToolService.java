package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import java.util.Map;

/**
 * Function Tool 服务接口，所有工具类都需要实现此接口
 * 大模型在需要调用外部能力时，会通过 Function Calling 机制自动调用对应的工具
 *
 * @author paicoding
 * @date 2026/3/5
 */
public interface IFunctionToolService {

    /**
     * 执行工具调用
     *
     * @param params 大模型传入的参数（JSON 解析后的 Map）
     * @return 工具执行的结果字符串，会返回给大模型继续生成回答
     */
    String execute(Map<String, Object> params);

    /**
     * 获取工具参数的 JSON Schema 描述，用于告知大模型该工具需要哪些参数
     * 格式遵循 DashScope Function Calling 规范
     *
     * @return 参数的 JSON Schema
     */
    Map<String, Object> getParameterSchema();
}
