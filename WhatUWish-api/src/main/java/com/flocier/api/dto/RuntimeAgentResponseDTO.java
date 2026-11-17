package com.flocier.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/** Runtime Event API 对前端发布的可启动运行时能力。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RuntimeAgentResponseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String displayName;
    private String description;
    private String runtimeKind;
    private boolean available;
}
