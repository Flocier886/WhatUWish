package com.flocier.test.domain.file;

import com.flocier.infrastructure.dao.IConversationFileDao;
import com.flocier.infrastructure.dao.po.ConversationFilePO;
import com.flocier.infrastructure.dao.po.ConversationFileSessionPO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.*;

public class ConversationFilePersistenceMappingTest {
    @Test
    public void mapperBindsSessionAndFileWithoutRunIdentity() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/conversation_file_mapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        assertTrue(configuration.hasMapper(IConversationFileDao.class));
        String prefix = IConversationFileDao.class.getName() + ".";
        var sessionInsert = configuration.getMappedStatement(prefix + "insertSession").getBoundSql(new ConversationFileSessionPO());
        var fileInsert = configuration.getMappedStatement(prefix + "insertFile").getBoundSql(new ConversationFilePO());
        assertTrue(sessionInsert.getSql().contains("conversation_file_session"));
        assertTrue(fileInsert.getSql().contains("conversation_file"));
        assertFalse(fileInsert.getSql().contains("run_id"));
        for (var mapping : fileInsert.getParameterMappings()) {
            assertTrue(mapping.getProperty(), configuration.newMetaObject(new ConversationFilePO()).hasGetter(mapping.getProperty()));
        }
    }
}
