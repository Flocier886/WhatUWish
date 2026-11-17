package com.flocier.test.domain.memory;

import com.flocier.domain.agent.runtime.memory.review.SkillReviewExecution;
import com.flocier.infrastructure.dao.ISkillReviewExecutionDao;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import java.io.InputStream;
import static org.junit.Assert.*;

/** 只解析 MyBatis 映射和参数，不创建数据源或连接数据库。 */
public class SkillReviewPersistenceMappingTest {
    @Test public void parsesMapperAndBindsEntityWithTerminalStatusGuard() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/memory_skill_review_execution_mapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        assertTrue(configuration.hasMapper(ISkillReviewExecutionDao.class));
        SkillReviewExecution row = new SkillReviewExecution(); row.setReviewId("review"); row.setStatus("TIMEOUT");
        String prefix = ISkillReviewExecutionDao.class.getName() + ".";
        for (String statement : new String[]{"insert", "finish"}) {
            BoundSql sql = configuration.getMappedStatement(prefix + statement).getBoundSql(row);
            assertTrue(sql.getSql().contains("memory_skill_review_execution"));
            for (var parameter : sql.getParameterMappings())
                assertTrue(parameter.getProperty(), configuration.newMetaObject(row).hasGetter(parameter.getProperty()));
        }
        assertTrue(configuration.getMappedStatement(prefix + "finish").getBoundSql(row).getSql().contains("status = 'RUNNING'"));
    }
}
