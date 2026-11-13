package com.bigdata.modules.runtime.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.runtime.dto.RuntimeQueryDTO;
import com.bigdata.modules.runtime.service.RuntimeQueryService;
import com.bigdata.modules.runtime.vo.RuntimeCompareVO;
import com.bigdata.modules.runtime.vo.RuntimeComponentsVO;
import com.bigdata.modules.runtime.vo.RuntimeConfigVO;
import com.bigdata.modules.runtime.vo.RuntimeDiscoveryVO;
import com.bigdata.modules.runtime.vo.RuntimeMetricDefinitionVO;
import com.bigdata.modules.runtime.vo.RuntimeSnapshotVO;
import com.bigdata.modules.runtime.vo.RuntimeSourceVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运行时指标接口（{@code /api/runtime/**}）。
 *
 * <p>与 {@code /api/logs/**} 分开：日志是文本流，这里是结构化指标快照，没有游标语义。</p>
 */
@RestController
@RequestMapping("/runtime")
public class RuntimeController {

    private final RuntimeQueryService queryService;

    public RuntimeController(RuntimeQueryService queryService) {
        this.queryService = queryService;
    }

    /** 已配置的运行时源；未配置地址的源返回 enabled=false 与原因 */
    @GetMapping("/sources")
    public Result<List<RuntimeSourceVO>> sources() {
        return Result.success(queryService.listSources());
    }

    /** 指标对照表：服务 / 指标族 / 原始指标名 / 层级 / 端点 / 单位 / 是否已验证 */
    @GetMapping("/metrics/catalog")
    public Result<List<RuntimeMetricDefinitionVO>> catalog() {
        return Result.success(queryService.catalog());
    }

    /** 作业发现；history 走 SHS，live 走 JobManager REST 或 YARN */
    @GetMapping("/jobs")
    public Result<RuntimeDiscoveryVO> jobs(@ModelAttribute RuntimeQueryDTO dto) {
        return Result.success(queryService.listJobs(dto));
    }

    /** TaskManager / executor 列表（可带指标） */
    @GetMapping("/jobs/{jobId}/components")
    public Result<RuntimeComponentsVO> components(@PathVariable String jobId,
                                                  @ModelAttribute RuntimeQueryDTO dto) {
        dto.setJobId(jobId);
        return Result.success(queryService.listComponents(dto));
    }

    /**
     * 单作业运行时快照：状态 + 组件 + 指定指标族 + checkpoint。
     *
     * <p>{@code families} 默认取配置；{@code metrics} 可显式追加原始指标名；
     * {@code components} 可限定只返回指定 TM / executor。</p>
     */
    @GetMapping("/jobs/{jobId}/snapshot")
    public Result<RuntimeSnapshotVO> snapshot(@PathVariable String jobId,
                                              @ModelAttribute RuntimeQueryDTO dto) {
        dto.setJobId(jobId);
        return Result.success(queryService.snapshot(dto));
    }

    /** 配置参数：作业配置 / 集群配置 / executor、TM 资源配置（已脱敏） */
    @GetMapping("/jobs/{jobId}/config")
    public Result<RuntimeConfigVO> config(@PathVariable String jobId,
                                          @ModelAttribute RuntimeQueryDTO dto) {
        dto.setJobId(jobId);
        return Result.success(queryService.config(dto));
    }

    /** 时间点对比：与进程内更早的一份快照做差（累计指标增量与每秒速率） */
    @GetMapping("/jobs/{jobId}/compare")
    public Result<RuntimeCompareVO> compare(@PathVariable String jobId,
                                            @ModelAttribute RuntimeQueryDTO dto) {
        dto.setJobId(jobId);
        return Result.success(queryService.compare(dto));
    }
}
