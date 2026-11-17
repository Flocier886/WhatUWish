package com.flocier.domain.agent.adapter.port.cli;

/** 宿主 CLI 执行端口。 */
public interface CliExecutionPort {

    CliResult exec(CliInvocation invocation);

    boolean isResolvable(String tool);
}
