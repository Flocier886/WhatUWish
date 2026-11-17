package com.flocier.domain.agent.model.vo;

import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * KeywordRetriever（BM25/关键词召回）类型枚举：用于通过数据库配置的名字校验并匹配 IoC 中的实现。
 *
 * 约定：
 * - code：数据库中配置的字符串（例如：EsBm25KeywordRetriever）
 * - info：说明
 *
 * 具体 Bean 由 {@link RagAdvanceAdvisorBeansProvider} 按 code 解析。
 */
@Getter
@AllArgsConstructor
public enum AiKeywordRetrieverTypeEnumVO {

    ES_BM25_KEYWORD_RETRIEVER("EsBm25KeywordRetriever", "Elasticsearch BM25 关键词召回"),

    ;

    private final String code;
    private final String info;

    private static final Map<String, AiKeywordRetrieverTypeEnumVO> CODE_MAP = new HashMap<>();

    static {
        for (AiKeywordRetrieverTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }

    public static AiKeywordRetrieverTypeEnumVO getByCode(String code) {
        AiKeywordRetrieverTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! keywordRetrieverType " + code + " not exist!");
        }
        return enumVO;
    }
}
