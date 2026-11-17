package com.flocier.test.domain;

import com.flocier.domain.agent.model.vo.AiClientToolMcpVO;
import com.flocier.infrastructure.adapter.repository.AgentRepository;
import com.flocier.infrastructure.dao.IAiClientToolMcpDao;
import com.flocier.infrastructure.dao.po.AiClientToolMcp;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class AgentRepositoryMcpTest {

    @Test
    public void shouldQueryAndParseOnlyEnabledMcpRowsFromExistingDao() {
        IAiClientToolMcpDao dao = Mockito.mock(IAiClientToolMcpDao.class);
        Mockito.when(dao.queryEnabledMcps()).thenReturn(List.of(
                AiClientToolMcp.builder()
                        .mcpId("stdio-1").mcpName("filesystem").transportType("stdio")
                        .transportConfig("{\"filesystem\":{\"command\":\"npx\",\"args\":[\"-y\",\"server\"],\"env\":{}}}")
                        .requestTimeout(2).status(1).build(),
                AiClientToolMcp.builder()
                        .mcpId("http-1").mcpName("remote").transportType("streamableHttp")
                        .transportConfig("{\"baseUri\":\"https://example.com\",\"endPoint\":\"/mcp\",\"headers\":{\"Authorization\":\"secret\"}}")
                        .requestTimeout(3).status(1).build()
        ));
        AgentRepository repository = new AgentRepository();
        ReflectionTestUtils.setField(repository, "aiClientToolMcpDao", dao);

        List<AiClientToolMcpVO> result = repository.queryEnabledAiClientToolMcpVOList();

        assertEquals(2, result.size());
        assertEquals("npx", result.get(0).getTransportConfigStdio().getStdio().get("filesystem").getCommand());
        assertEquals("/mcp", result.get(1).getTransportConfigStreamableHttp().getEndPoint());
        Mockito.verify(dao).queryEnabledMcps();
    }
}
