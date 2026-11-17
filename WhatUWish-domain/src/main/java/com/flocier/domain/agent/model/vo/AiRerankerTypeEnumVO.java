package com.flocier.domain.agent.model.vo;

import com.flocier.domain.agent.service.armory.support.RagAdvanceAdvisorBeansProvider;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Reranker 类型枚举：用于通过数据库配置的名字校验并匹配 IoC 中的实现。
 *
 * 约定：
 * - code：数据库中配置的字符串（例如：BgeRerankerV2M3Client）
 * - info：说明
 *
 * 具体 Bean 由 {@link RagAdvanceAdvisorBeansProvider} 按 code 解析。
 */
@Getter
@AllArgsConstructor
public enum AiRerankerTypeEnumVO {

    BGE_RERANKER_V2_M3_CLIENT("BgeRerankerV2M3Client", "自托管 bge-reranker-v2-m3 容器 HTTP 精排"),

    ;

    private final String code;
    private final String info;

    private static final Map<String, AiRerankerTypeEnumVO> CODE_MAP = new HashMap<>();

    static {
        for (AiRerankerTypeEnumVO enumVO : values()) {
            CODE_MAP.put(enumVO.getCode(), enumVO);
        }
    }

    public static AiRerankerTypeEnumVO getByCode(String code) {
        AiRerankerTypeEnumVO enumVO = CODE_MAP.get(code);
        if (enumVO == null) {
            throw new RuntimeException("err! rerankerType " + code + " not exist!");
        }
        return enumVO;
    }
}
