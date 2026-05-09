package com.bigdata.modules.development.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.EngineType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeExecutionControllerTest {

    @Mock
    private CodeExecutionService codeExecutionService;

    @InjectMocks
    private CodeExecutionController codeExecutionController;

    private CodeExecutionRequest validRequest;
    private CodeExecutionResult mockResult;
    private Principal mockPrincipal;

    @BeforeEach
    void setUp() {
        validRequest = new CodeExecutionRequest();
        validRequest.setCodeContent("SELECT * FROM users LIMIT 100;");
        validRequest.setCodeType("sql");
        validRequest.setEngineType("spark");
        validRequest.setDatasources(Arrays.asList("test_mysql"));
        validRequest.setRemark("测试查询");

        Map<String, Object> runParams = new HashMap<>();
        runParams.put("date", Map.of("type", "string", "value", "2024-01-01"));
        validRequest.setRunParams(runParams);

        Map<String, Object> computeResources = new HashMap<>();
        computeResources.put("driver-memory", "4g");
        computeResources.put("executor-memory", "8g");
        validRequest.setComputeResources(computeResources);

        mockResult = new CodeExecutionResult();
        mockResult.setSuccess(true);
        mockResult.setExecutionId("EXEC_1704067200000_a3f2");
        mockResult.setStatus("SUCCESS");
        mockResult.setOutputLog("Query executed successfully. 100 rows returned.");
        mockResult.setDurationMs(1234);
        mockResult.setAffectedRows(100);

        mockPrincipal = new Principal() {
            @Override
            public String getName() {
                return "testUser";
            }
        };
    }

    @Test
    @DisplayName("executeCode - 正常执行代码任务")
    void testExecuteCode_Success() {
        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), eq(1L), eq("testUser")))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().isSuccess());
        assertEquals("EXEC_1704067200000_a3f2", result.getData().getExecutionId());
        assertEquals("SUCCESS", result.getData().getStatus());
        assertEquals(1234, result.getData().getDurationMs());

        verify(codeExecutionService).executeCode(any(CodeExecutionRequest.class), eq(1L), eq("testUser"));
    }

    @Test
    @DisplayName("executeCode - Principal为null时使用默认用户")
    void testExecuteCode_WithNullPrincipal() {
        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), eq(1L), eq("System")))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, null);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isSuccess());

        verify(codeExecutionService).executeCode(any(CodeExecutionRequest.class), eq(1L), eq("System"));
    }

    @Test
    @DisplayName("executeCode - 包含完整运行参数和计算资源")
    void testExecuteCode_WithFullConfiguration() {
        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getCodeContent().equals("SELECT * FROM users LIMIT 100;") &&
                        request.getCodeType().equals("sql") &&
                        request.getEngineType().equals("spark") &&
                        request.getDatasources().size() == 1 &&
                        request.getDatasources().contains("test_mysql") &&
                        request.getRunParams() != null &&
                        request.getComputeResources() != null &&
                        request.getRemark().equals("测试查询")
        ), eq(1L), eq("testUser"));
    }

    @Test
    @DisplayName("executeCode - 代码内容为空时返回错误")
    void testExecuteCode_EmptyCodeContent() {
        validRequest.setCodeContent("");
        validRequest.setCodeType("sql");
        validRequest.setEngineType("spark");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码内容不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 代码内容为null时返回错误")
    void testExecuteCode_NullCodeContent() {
        validRequest.setCodeContent(null);
        validRequest.setCodeType("sql");
        validRequest.setEngineType("spark");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码内容不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 代码类型为空时返回错误")
    void testExecuteCode_EmptyCodeType() {
        validRequest.setCodeContent("SELECT * FROM users;");
        validRequest.setCodeType("");
        validRequest.setEngineType("spark");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码类型不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 代码类型为null时返回错误")
    void testExecuteCode_NullCodeType() {
        validRequest.setCodeContent("SELECT * FROM users;");
        validRequest.setCodeType(null);
        validRequest.setEngineType("spark");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码类型不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 引擎类型为空时返回错误")
    void testExecuteCode_EmptyEngineType() {
        validRequest.setCodeContent("SELECT * FROM users;");
        validRequest.setCodeType("sql");
        validRequest.setEngineType("");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("引擎类型不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 引擎类型为null时返回错误")
    void testExecuteCode_NullEngineType() {
        validRequest.setCodeContent("SELECT * FROM users;");
        validRequest.setCodeType("sql");
        validRequest.setEngineType(null);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("引擎类型不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 所有必填字段都为null时返回错误")
    void testExecuteCode_AllFieldsNull() {
        CodeExecutionRequest emptyRequest = new CodeExecutionRequest();

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(emptyRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码内容不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - Service抛出业务异常时返回错误信息")
    void testExecuteCode_ServiceThrowsException() {
        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenThrow(new RuntimeException("执行引擎不支持该代码类型"));

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码执行失败"));
        assertTrue(result.getMessage().contains("执行引擎不支持该代码类型"));
    }

    @Test
    @DisplayName("executeCode - 执行失败结果（success=false）")
    void testExecuteCode_ExecutionFailed() {
        CodeExecutionResult failureResult = new CodeExecutionResult();
        failureResult.setSuccess(false);
        failureResult.setExecutionId("EXEC_1704067200000_b4c3");
        failureResult.setStatus("FAILED");
        failureResult.setErrorMessage("SQL syntax error");

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(failureResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertFalse(result.getData().isSuccess());
        assertEquals("FAILED", result.getData().getStatus());
        assertEquals("SQL syntax error", result.getData().getErrorMessage());
    }

    @Test
    @DisplayName("executeCode - 多数据源配置")
    void testExecuteCode_MultipleDatasources() {
        validRequest.setDatasources(Arrays.asList("prod_hive", "dim_mysql", "ods_doris"));

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getDatasources() != null &&
                        request.getDatasources().size() == 3 &&
                        request.getDatasources().containsAll(Arrays.asList("prod_hive", "dim_mysql", "ods_doris"))
        ), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 空数据源列表")
    void testExecuteCode_EmptyDatasources() {
        validRequest.setDatasources(null);

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    @Test
    @DisplayName("getAvailableEngines - 返回可用引擎列表")
    void testGetAvailableEngines_Success() {
        List<EngineType> engines = Arrays.asList(
                EngineType.SPARK,
                EngineType.FLINK,
                EngineType.KYUUBI
        );

        when(codeExecutionService.getAvailableEngines()).thenReturn(engines);

        Result<List<EngineType>> result = codeExecutionController.getAvailableEngines();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(3, result.getData().size());
        assertTrue(result.getData().contains(EngineType.SPARK));
        assertTrue(result.getData().contains(EngineType.FLINK));
        assertTrue(result.getData().contains(EngineType.KYUUBI));

        verify(codeExecutionService).getAvailableEngines();
    }

    @Test
    @DisplayName("getAvailableEngines - 空引擎列表")
    void testGetAvailableEngines_EmptyList() {
        when(codeExecutionService.getAvailableEngines()).thenReturn(List.of());

        Result<List<EngineType>> result = codeExecutionController.getAvailableEngines();

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @DisplayName("getAvailableEngines - Service抛异常时返回错误")
    void testGetAvailableEngines_ServiceException() {
        when(codeExecutionService.getAvailableEngines())
                .thenThrow(new RuntimeException("数据库连接失败"));

        Result<List<EngineType>> result = codeExecutionController.getAvailableEngines();

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("获取引擎列表失败"));
    }

    @Test
    @DisplayName("checkSupport - Spark支持Python")
    void testCheckSupport_SparkSupportsPython() {
        when(codeExecutionService.isExecutionSupported("spark", "python")).thenReturn(true);

        Result<Boolean> result = codeExecutionController.checkSupport("spark", "python");

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData());

        verify(codeExecutionService).isExecutionSupported("spark", "python");
    }

    @Test
    @DisplayName("checkSupport - Kyuubi不支持Java")
    void testCheckSupport_KyuubiNotSupportsJava() {
        when(codeExecutionService.isExecutionSupported("kyuubi", "java")).thenReturn(false);

        Result<Boolean> result = codeExecutionController.checkSupport("kyuubi", "java");

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertFalse(result.getData());

        verify(codeExecutionService).isExecutionSupported("kyuubi", "java");
    }

    @Test
    @DisplayName("checkSupport - Flink支持SQL")
    void testCheckSupport_FlinkSupportsSql() {
        when(codeExecutionService.isExecutionSupported("flink", "sql")).thenReturn(true);

        Result<Boolean> result = codeExecutionController.checkSupport("flink", "sql");

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.getData());

        verify(codeExecutionService).isExecutionSupported("flink", "sql");
    }

    @Test
    @DisplayName("checkSupport - Service抛异常时返回错误")
    void testCheckSupport_ServiceException() {
        when(codeExecutionService.isExecutionSupported(anyString(), anyString()))
                .thenThrow(new RuntimeException("引擎类型无效"));

        Result<Boolean> result = codeExecutionController.checkSupport("invalid_engine", "sql");

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("检查支持失败"));
    }

    @Test
    @DisplayName("executeCode - Python代码执行")
    void testExecuteCode_PythonCode() {
        validRequest.setCodeContent("from pyspark.sql import SparkSession\n\nspark = SparkSession.builder.appName('test').getOrCreate()\ndf = spark.sql('SELECT 1')\ndf.show()");
        validRequest.setCodeType("python");
        validRequest.setEngineType("spark");

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(result.getData().isSuccess());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getCodeType().equals("python") &&
                        request.getEngineType().equals("spark") &&
                        request.getCodeContent().contains("pyspark")
        ), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - Java代码执行")
    void testExecuteCode_JavaCode() {
        validRequest.setCodeContent("public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello\");\n    }\n}");
        validRequest.setCodeType("java");
        validRequest.setEngineType("flink");

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getCodeType().equals("java") &&
                        request.getEngineType().equals("flink")
        ), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - Scala代码执行")
    void testExecuteCode_ScalaCode() {
        validRequest.setCodeContent("object Main {\n  def main(args: Array[String]): Unit = {\n    println(\"Hello Scala\")\n  }\n}");
        validRequest.setCodeType("scala");
        validRequest.setEngineType("spark");

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getCodeType().equals("scala")
        ), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 带JAR依赖的执行请求")
    void testExecuteCode_WithJarDependencies() {
        validRequest.setJarDependencyCatalogs(Arrays.asList("hudi", "iceberg", "mysql-connector"));

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());

        verify(codeExecutionService).executeCode(argThat(request ->
                request.getJarDependencyCatalogs() != null &&
                        request.getJarDependencyCatalogs().size() == 3 &&
                        request.getJarDependencyCatalogs().containsAll(Arrays.asList("hudi", "iceberg", "mysql-connector"))
        ), anyLong(), anyString());
    }

    @Test
    @DisplayName("executeCode - 长代码内容执行")
    void testExecuteCode_LongCodeContent() {
        StringBuilder longCode = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longCode.append("SELECT * FROM table").append(i).append(" UNION ALL ");
        }
        longCode.append("SELECT 1");
        validRequest.setCodeContent(longCode.toString());

        when(codeExecutionService.executeCode(any(CodeExecutionRequest.class), anyLong(), anyString()))
                .thenReturn(mockResult);

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertTrue(validRequest.getCodeContent().length() > 10000);
    }

    @Test
    @DisplayName("executeCode - 只有空白字符的代码内容应返回错误")
    void testExecuteCode_WhitespaceOnlyContent() {
        validRequest.setCodeContent("   \n\t  ");
        validRequest.setCodeType("sql");
        validRequest.setEngineType("spark");

        Result<CodeExecutionResult> result = codeExecutionController.executeCode(validRequest, mockPrincipal);

        assertNotNull(result);
        assertNotEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("代码内容不能为空"));

        verify(codeExecutionService, never()).executeCode(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("验证请求对象字段完整性")
    void testRequestObject_FieldCompleteness() {
        assertEquals("SELECT * FROM users LIMIT 100;", validRequest.getCodeContent());
        assertEquals("sql", validRequest.getCodeType());
        assertEquals("spark", validRequest.getEngineType());
        assertEquals(1, validRequest.getDatasources().size());
        assertEquals("test_mysql", validRequest.getDatasources().get(0));
        assertNotNull(validRequest.getRunParams());
        assertNotNull(validRequest.getComputeResources());
        assertEquals("测试查询", validRequest.getRemark());
    }

    @Test
    @DisplayName("验证mockResult字段完整性")
    void testMockResult_FieldCompleteness() {
        assertTrue(mockResult.isSuccess());
        assertEquals("EXEC_1704067200000_a3f2", mockResult.getExecutionId());
        assertEquals("SUCCESS", mockResult.getStatus());
        assertEquals("Query executed successfully. 100 rows returned.", mockResult.getOutputLog());
        assertEquals(1234, mockResult.getDurationMs());
        assertEquals(100, mockResult.getAffectedRows());
    }

    @Test
    @DisplayName("验证Principal对象")
    void testPrincipal_Object() {
        assertNotNull(mockPrincipal);
        assertEquals("testUser", mockPrincipal.getName());
    }
}
