package com.bigdata.modules.devops.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.devops.vo.DatasourceTypeMetaVO;
import com.bigdata.modules.development.service.execution.CodeExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 执行引擎元信息接口。
 * <p>
 * 把 {@link CodeExecutionService#getAvailableEngines()} 与
 * {@link CodeExecutionService#isExecutionSupported(String, String)} 暴露给前端，
 * 供前端动态渲染「可选执行引擎」及「每种引擎支持的语言」，无需在前端硬编码映射。
 * </p>
 */
@RestController
@RequestMapping("/devops/execution")
public class ExecutionEngineController {

    @Autowired
    private CodeExecutionService codeExecutionService;

    /**
     * 可选执行引擎列表（含各自支持的语言）。
     * 对应 {@code CodeExecutionService.getAvailableEngines()}。
     */
    @GetMapping("/engines")
    public Result<List<DatasourceTypeMetaVO>> getEngines() {
        List<DatasourceTypeMetaVO> engines = codeExecutionService.getAvailableEngines().stream()
                .map(DatasourceTypeMetaVO::from)
                .collect(Collectors.toList());
        return Result.success(engines);
    }

    /**
     * 判断某引擎是否支持某代码类型。
     * 对应 {@code CodeExecutionService.isExecutionSupported(engineCode, codeType)}。
     */
    @GetMapping("/engines/supported")
    public Result<Boolean> isSupported(@RequestParam String engineCode,
                                       @RequestParam String codeType) {
        return Result.success(codeExecutionService.isExecutionSupported(engineCode, codeType));
    }
}
