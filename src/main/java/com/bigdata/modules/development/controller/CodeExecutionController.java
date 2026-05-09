package com.bigdata.modules.development.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.development.dto.CodeExecutionRequest;
import com.bigdata.modules.development.service.execution.CodeExecutionResult;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import com.bigdata.modules.development.service.execution.EngineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/development/execution")
public class CodeExecutionController {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionController.class);

    @Autowired
    private CodeExecutionService codeExecutionService;

    @PostMapping("/execute")
    public Result<CodeExecutionResult> executeCode(
            @RequestBody CodeExecutionRequest request,
            Principal principal) {
        try {
            String username = getUsernameFromPrincipal(principal);
            Long userId = getUserIdFromPrincipal(principal);

            log.info("📥 收到代码执行请求");
            log.info("   - 代码类型: {}", request.getCodeType());
            log.info("   - 引擎类型: {}", request.getEngineType());
            log.info("   - 数据源: {}", request.getDatasources());
            log.info("   - 代码长度: {} 字符", request.getCodeContent() != null ? request.getCodeContent().length() : 0);
            log.info("   - 请求用户: {} ({})", username, userId);

            validateExecuteRequest(request);

            CodeExecutionResult result = codeExecutionService.executeCode(request, userId, username);

            log.info("📤 返回执行结果: executionId={}, success={}",
                    result.getExecutionId(), result.isSuccess());

            return Result.success(result);
        } catch (Exception e) {
            log.error("❌ 代码执行接口异常", e);
            return Result.error("代码执行失败: " + e.getMessage());
        }
    }

    @GetMapping("/engines")
    public Result<List<EngineType>> getAvailableEngines() {
        try {
            List<EngineType> engines = codeExecutionService.getAvailableEngines();
            log.info("🔧 获取可用引擎列表: {} 个", engines.size());
            return Result.success(engines);
        } catch (Exception e) {
            log.error("❌ 获取引擎列表失败", e);
            return Result.error("获取引擎列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/support")
    public Result<Boolean> checkSupport(
            @RequestParam String engineCode,
            @RequestParam String codeType) {
        try {
            boolean supported = codeExecutionService.isExecutionSupported(engineCode, codeType);
            log.info("🔍 检查执行支持: engine={}, type={}, supported={}",
                    engineCode, codeType, supported);
            return Result.success(supported);
        } catch (Exception e) {
            log.error("❌ 检查执行支持失败", e);
            return Result.error("检查支持失败: " + e.getMessage());
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

        log.debug("✅ 执行请求校验通过");
    }

    private String getUsernameFromPrincipal(Principal principal) {
        if (principal == null) {
            return "System";
        }
        return principal.getName();
    }

    private Long getUserIdFromPrincipal(Principal principal) {
        if (principal == null) {
            return 1L;
        }
        return 1L;
    }
}
