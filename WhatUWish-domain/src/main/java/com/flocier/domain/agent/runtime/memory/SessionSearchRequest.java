package com.flocier.domain.agent.runtime.memory;

import lombok.Builder;
import lombok.Data;

/** session_search 请求；通过参数组合推断发现、滚动、阅读或浏览模式。 */
@Data
@Builder
public class SessionSearchRequest {
    /** 显式指定的历史会话；与 query 组合时只搜索该会话，单独使用时阅读该会话。 */
    private String sessionId;
    /** 当前 ReAct 会话；scope=session 的默认目标，也是跨会话搜索排除当前活动消息的依据。 */
    private String currentSessionId;
    /** 关键词；存在时进入发现模式，不能与消息锚点同时使用。 */
    private String query;
    /** 发现或浏览模式的最大会话数，服务层负责默认值与上限。 */
    private Integer limit;
    /** 发现范围：user 表示本地已登记会话，session 表示单个会话。 */
    private String scope;
    /** 逗号分隔的可见角色；默认只读取 USER 和 ASSISTANT。 */
    private String roleFilter;
    /** 消息表 ID；与 sessionId 一起使用时进入锚点滚动阅读模式。 */
    private Long aroundMessageId;
    /** 滚动阅读时锚点前后各取的消息数。 */
    private Integer window;
}
