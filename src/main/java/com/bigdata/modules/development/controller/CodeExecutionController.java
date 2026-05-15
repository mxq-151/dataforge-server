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
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/development/execution")
public class CodeExecutionController {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionController.class);

    @Autowired
    private CodeExecutionService codeExecutionService;

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @PostMapping("/execute")
    public Result<CodeExecutionResult> executeCode(@RequestBody CodeExecutionRequest request) {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            String username = SecurityUtils.getCurrentUsername();
            if (userId == null) {
                return Result.error(401, "未登录或登录已过期");
            }

            log.info("收到代码执行请求");
            log.info("   - 代码类型: {}", request.getCodeType());
            log.info("   - 引擎类型: {}", request.getEngineType());
            log.info("   - 数据源: {}", request.getDatasources());
            log.info("   - 请求用户: {} ({})", username, userId);

            validateExecuteRequest(request);
            checkDatasourceAccess(request.getDatasources());

            CodeExecutionResult result = codeExecutionService.executeCode(request, userId, username);

            log.info("返回执行结果: executionId={}, success={}",
                    result.getExecutionId(), result.isSuccess());

            return Result.success(result);
        } catch (BusinessException e) {
            return Result.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("代码执行接口异常", e);
            return Result.error("代码执行失败: " + e.getMessage());
        }
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
        if (request.getCodeContent() == null || request.getCodeContent().trim().isEmpty()) {
            throw new IllegalArgumentException("代码内容不能为空");
        }
        if (request.getCodeType() == null || request.getCodeType().trim().isEmpty()) {
            throw new IllegalArgumentException("代码类型不能为空");
        }
        if (request.getEngineType() == null || request.getEngineType().trim().isEmpty()) {
            throw new IllegalArgumentException("引擎类型不能为空");
        }
    }
}
