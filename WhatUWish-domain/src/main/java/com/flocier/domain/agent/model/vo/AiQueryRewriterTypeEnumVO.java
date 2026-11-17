package com.flocier.domain.agent.model.vo;

import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * QueryRewriter 类型枚举：用于通过数据库配置的名字校验并匹配 IoC 中的实现。
 *
 * 约定：
 * - code：数据库中配置的字符串（例如：OpenAiQueryRewriter）
 * - info：说明
 *
 * 具体 Bean 由 {@link RagAdvanceAdvisorBeansProvider} 按 code 解析。
 */
@Getter
@AllArgsConstructor
public enum AiQueryRewriterTypeEnumVO {

    OPENAI_QUERY_REWRITER("OpenAiQueryRewriter", "OpenAI gpt-4.1 query 改写"),

    ;

    private final String code;
    private final String info;

    private static final Map<String, AiQueryRewriterTypeEnumVO> CODE_MAP = new HashMap<>();

    static {
        for (AiQueryRewriterTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }

    public static AiQueryRewriterTypeEnumVO getByCode(String code) {
        AiQueryRewriterTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! queryRewriterType " + code + " not exist!");
        }
        return enumVO;
    }
}
