package com.bigdata.modules.devops.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.vo.DatasourceTypeMetaVO;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.devops.enums.DatasourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ExecutionEngineControllerTest {

    @Mock
    private CodeExecutionService codeExecutionService;

    @InjectMocks
    private ExecutionEngineController controller;

    @Test
    void getEnginesDelegatesAndMapsToMetaVO() {
        when(codeExecutionService.getAvailableEngines())
                .thenReturn(Arrays.asList(DatasourceType.SPARK, DatasourceType.DORIS));

        Result<List<DatasourceTypeMetaVO>> result = controller.getEngines();

        assertEquals(200, result.getCode());
        List<DatasourceTypeMetaVO> data = result.getData();
        assertEquals(2, data.size());
        assertEquals("spark", data.get(0).getCode());
        assertEquals("doris", data.get(1).getCode());
        // SPARK 支持 JAVA/SCALA/PYTHON/SQL，DORIS 仅 SQL
        assertTrue(data.get(0).getLanguage().contains("java"));
        assertTrue(data.get(1).getLanguage().contains("sql"));
    }

    @Test
    void isSupportedDelegatesToService() {
        when(codeExecutionService.isExecutionSupported("spark", "sql")).thenReturn(true);

        Result<Boolean> result = controller.isSupported("spark", "sql");

        assertEquals(Boolean.TRUE, result.getData());
    }
}
