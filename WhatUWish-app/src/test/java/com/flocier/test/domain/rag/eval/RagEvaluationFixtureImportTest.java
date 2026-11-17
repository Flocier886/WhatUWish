package com.flocier.test.domain.rag.eval;

import com.flocier.domain.agent.service.IRagService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class RagEvaluationFixtureImportTest {

    private static final String RAG_NAME = "rag_eval_suite_v3";
    private static final String KNOWLEDGE_TAG = "rag_eval_suite_v3";
    private static final String DOCS_RESOURCE_DIR = "evaluation/docs";

    @Autowired
    private IRagService ragService;

    @Test
    public void test_importEvaluationFixtures() throws Exception {
        List<String> filenames = listFixtureDocNames();
        Assume.assumeFalse("评测文档目录为空，请先补充 " + DOCS_RESOURCE_DIR, filenames.isEmpty());

        List<MockMultipartFile> files = new ArrayList<>();
        for (String filename : filenames) {
            ClassPathResource resource = new ClassPathResource(DOCS_RESOURCE_DIR + "/" + filename);
            try (InputStream inputStream = resource.getInputStream()) {
                files.add(new MockMultipartFile(
                        "files",
                        filename,
                        "text/plain",
                        inputStream
                ));
            }
        }

        ragService.storeRagFile(RAG_NAME, KNOWLEDGE_TAG, new ArrayList<>(files));
        log.info("RAG 评测文档导入完成 ragName:{} knowledgeTag:{} fileCount:{} files:{}",
                RAG_NAME, KNOWLEDGE_TAG, files.size(), filenames);
    }

    private List<String> listFixtureDocNames() throws Exception {
        ClassPathResource resource = new ClassPathResource(DOCS_RESOURCE_DIR);
        URI uri = resource.getURI();
        Path dir = Paths.get(uri);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }
}
