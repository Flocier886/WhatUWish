package com.flocier.domain.agent.runtime.agent.tool.impl;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class PythonToolResponseEntity {

    /**
     * Python 工具是否执行成功。
     */
    private boolean success;

    /**
     * Python 工具执行状态，允许工具自己声明 success / failed / partial_success。
     */
    private String status;

    /**
     * 供主链路直接展示或写入日志的文本输出。
     */
    private String output;

    /**
     * Python 工具给主智能体继续推理使用的 observation。
     */
    private String llmObservation;

    /**
     * Python 工具返回的结构化数据体。
     */
    private Map<String, Object> structured = new LinkedHashMap<>();

    /**
     * Python 工具沉淀给 ledger 的结构化事实。
     */
    private List<Map<String, Object>> ledgerFacts = new ArrayList<>();

    /**
     * Python 工具产物清单，后续可映射到 ledger artifact 账本。
     */
    private List<Map<String, Object>> artifacts = new ArrayList<>();

    /** 仅材料服务间合同使用：Local 输出原字节，Java 校验后自行存储。 */
    private List<Map<String, Object>> materialOutputs = new ArrayList<>();

    /**
     * 失败时的错误码。
     */
    private String errorCode;

    /**
     * 失败时的错误信息。
     */
    private String errorMessage;

    /**
     * 额外元数据，例如调试信息、响应透传信息等。
     */
    private Map<String, Object> metadata = new LinkedHashMap<>();
}
