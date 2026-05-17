package com.bigdata.modules.development.controller;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/development/execution")
public class CodeExecutionController {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionController.class);

    private static final String JAR_UPLOAD_DIR = System.getProperty("java.io.tmpdir") + "/bigdata-platform/jars/";

    @Autowired
    private CodeExecutionService codeExecutionService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @PostMapping(value = "/execute", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    public Result<CodeExecutionResult> executeCode(
            @RequestPart(value = "request", required = false) CodeExecutionRequest request,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            String username = SecurityUtils.getCurrentUsername();
            if (userId == null) {
                return Result.error(401, "未登录或登录已过期");
            }

            if (request == null) {
                return Result.error(400, "请求参数不能为空");
            }

            if (file != null && !file.isEmpty()) {
                String jarPath = saveUploadedJar(file, userId);
                request.setUploadedJarPath(jarPath);
                if (request.getFileName() == null || request.getFileName().trim().isEmpty()) {
                    request.setFileName(file.getOriginalFilename());
                }
                log.info("JAR文件已上传: {}", jarPath);
            }

            log.info("收到代码执行请求");
            log.info("   - 代码类型: {}", request.getCodeType());
            log.info("   - 引擎类型: {}", request.getEngineType());
            log.info("   - 数据源: {}", request.getDatasources());
            log.info("   - 主类: {}", request.getMainClass());
            log.info("   - 文件名: {}", request.getFileName());
            log.info("   - JAR路径: {}", request.getUploadedJarPath());
            log.info("   - 请求用户: {} ({})", username, userId);

            validateExecuteRequest(request);
            checkDatasourceAccess(request.getDatasources());

            CodeExecutionResult result = codeExecutionService.executeCode(request, userId, username);

            log.info("返回执行结果: executionId={}, success={}",
                    result.getExecutionId(), result.isSuccess());

            return Result.success(result);
        } catch (BusinessException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("代码执行接口异常", e);
            return Result.error("代码执行失败: " + e.getMessage());
        }
    }

    private String saveUploadedJar(MultipartFile file, Long userId) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的JAR文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.endsWith(".jar")) {
            throw new IllegalArgumentException("仅支持 .jar 文件上传");
        }

        Path uploadDir = Paths.get(JAR_UPLOAD_DIR);
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }

        String storedName = userId + "_" + System.currentTimeMillis() + "_" + originalName;
        Path targetPath = uploadDir.resolve(storedName);
        Files.copy(file.getInputStream(), targetPath);

        log.info("JAR文件保存成功: originalName={}, path={}, size={}", originalName, targetPath, file.getSize());

        return targetPath.toAbsolutePath().toString();
    }

    private void checkDatasourceAccess(List<String> datasources) {
        if (datasources == null || datasources.isEmpty()) {
            return;
        }

        if (dataAccessPermissionService.isAdmin()) {
            return;
        }

        for (String dsName : datasources) {
            java.util.Set<String> accessibleDbNames = dataAccessPermissionService.getAccessibleDatabaseNames(dsName);
            if (accessibleDbNames == null || accessibleDbNames.isEmpty()) {
                throw new BusinessException(403, "无权访问数据源: " + dsName);
            }
        }
    }

    private void validateExecuteRequest(CodeExecutionRequest request) {
        if (request.getCodeType() == null || request.getCodeType().trim().isEmpty()) {
            throw new IllegalArgumentException("代码类型不能为空");
        }
        if (request.getEngineType() == null || request.getEngineType().trim().isEmpty()) {
            throw new IllegalArgumentException("引擎类型不能为空");
        }

        String codeTypeLower = request.getCodeType().toLowerCase();
        boolean isJarMode = request.getUploadedJarPath() != null && !request.getUploadedJarPath().isEmpty();

        switch (codeTypeLower) {
            case "java":
            case "scala":
                validateJavaOrScala(request, isJarMode);
                break;
            case "sql":
            case "python":
                validateSqlOrPython(request, isJarMode);
                break;
            default:
                validateDefault(request, isJarMode);
                break;
        }
    }

    private void validateJavaOrScala(CodeExecutionRequest request, boolean isJarMode) {
        if (!isJarMode && (request.getCodeContent() == null || request.getCodeContent().trim().isEmpty())) {
            throw new IllegalArgumentException("Java/Scala 非JAR模式必须提供代码内容(codeContent)");
        }
    }

    private void validateSqlOrPython(CodeExecutionRequest request, boolean isJarMode) {
        if (isJarMode) {
            throw new IllegalArgumentException("SQL/Python 不支持JAR执行模式，请使用内联代码模式");
        }
        if (request.getCodeContent() == null || request.getCodeContent().trim().isEmpty()) {
            throw new IllegalArgumentException("SQL/Python 模式必须提供代码内容(codeContent)");
        }
        if (request.getMainClass() != null && !request.getMainClass().trim().isEmpty()) {
            throw new IllegalArgumentException("SQL/Python 模式不需要指定主类(mainClass)");
        }
    }

    private void validateDefault(CodeExecutionRequest request, boolean isJarMode) {
        if (!isJarMode && (request.getCodeContent() == null || request.getCodeContent().trim().isEmpty())) {
            throw new IllegalArgumentException("非JAR模式必须提供代码内容(codeContent)");
        }
    }
}
