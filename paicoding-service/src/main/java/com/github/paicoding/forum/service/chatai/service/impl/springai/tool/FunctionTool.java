package com.github.paicoding.forum.service.chatai.service.impl.springai.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自定义 Function Tool 注解，用于标注 AI 可调用的工具方法
 * 类似 Spring AI 的 @Tool 注解，通过 Prompt 描述工具的功能，让大模型自动选择调用
 *
 * @author paicoding
 * @date 2026/3/5
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FunctionTool {

    /**
     * 工具名称，需要唯一，大模型会根据名称来识别和调用工具
     */
    String name();

    /**
     * 工具描述（Prompt），大模型根据描述决定何时调用此工具
     */
    String description();
}
