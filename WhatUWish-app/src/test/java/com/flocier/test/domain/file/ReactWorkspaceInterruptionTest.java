package com.flocier.test.domain.file;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.flocier.domain.agent.model.entity.ExecuteCommandEntity;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.file.ConversationRunInputRecorder;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.memory.MemoryFacade;
import com.flocier.domain.agent.service.execute.react.ReactAgentWorkflowExecuteStrategy;
import com.flocier.domain.agent.service.execute.react.step.factory.DefaultReactWorkflowStrategyFactory;
import com.flocier.domain.ledger.service.LedgerFactService;
import com.flocier.infrastructure.artifact.LocalArtifactContentStoreBackend;
import com.flocier.infrastructure.artifact.RoutingArtifactContentStore;
import com.flocier.infrastructure.persistent.memory.InMemoryConversationFileRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ReactWorkspaceInterruptionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    @SuppressWarnings("unchecked")
    public void interruptedWorkflowKeepsSessionFilesAndReleasesWorkspace() throws Exception {
        var backend = new LocalArtifactContentStoreBackend(temporary.newFolder("store").getPath());
        var runs = temporary.newFolder("runs").toPath();
        var workspace = new RunWorkspaceService(new RoutingArtifactContentStore(List.of(backend)),
                new InMemoryConversationFileRepository(), runs.toString());
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var factory = mock(DefaultReactWorkflowStrategyFactory.class);
        StrategyHandler<ExecuteCommandEntity, DefaultReactWorkflowStrategyFactory.DynamicContext, String> handler =
                mock(StrategyHandler.class);
        when(factory.workflowStrategyHandler()).thenReturn(handler);
        when(handler.apply(any(), any())).thenAnswer(call -> {
            entered.countDown();
            Thread.sleep(30_000);
            return "unreachable";
        });
        var strategy = new ReactAgentWorkflowExecuteStrategy();
        ReflectionTestUtils.setField(strategy, "defaultReactWorkflowStrategyFactory", factory);
        ReflectionTestUtils.setField(strategy, "ledgerFactService", mock(LedgerFactService.class));
        ReflectionTestUtils.setField(strategy, "memoryFacade", mock(MemoryFacade.class));
        ReflectionTestUtils.setField(strategy, "conversationRunInputRecorder", mock(ConversationRunInputRecorder.class));
        ReflectionTestUtils.setField(strategy, "runWorkspaceService", workspace);
        ReflectionTestUtils.setField(strategy, "workingMemoryEnabled", false);
        var context = AgentContext.builder().build();
        var command = ExecuteCommandEntity.builder().aiAgentId("react-runtime")
                .sessionId("interrupted-session").message("interrupted task").build();
        var worker = new Thread(() -> {
            try { strategy.execute(command, context); }
            catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        worker.start();
        try {
            assertTrue("workflow did not enter handler", entered.await(5, TimeUnit.SECONDS));
            assertTrue(Files.isDirectory(workspace.root(context)));
        } finally {
            worker.interrupt();
            assertTrue("interrupted workflow did not finish", finished.await(5, TimeUnit.SECONDS));
            worker.join(1000);
        }
        assertTrue(failure.get() instanceof InterruptedException);
        assertThrows(IllegalArgumentException.class, () -> workspace.root(context));
        try (var paths = Files.list(runs)) { assertEquals(1, paths.count()); }
    }
}
