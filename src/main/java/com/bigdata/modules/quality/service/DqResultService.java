package com.bigdata.modules.quality.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.common.utils.SecurityUtils;
import com.bigdata.modules.quality.dto.QualityResultReportDTO;
import com.bigdata.modules.quality.entity.DqRule;
import com.bigdata.modules.quality.entity.DqRuleResult;
import com.bigdata.modules.quality.enums.QualityCheckStatus;
import com.bigdata.modules.quality.mapper.DqRuleMapper;
import com.bigdata.modules.quality.mapper.DqRuleResultMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则级校验结果 Service —— S3 侧校验程序的结果回传入口。
 *
 * <h3>为什么单独存在</h3>
 * <p>平台自身不再执行质检任务（质检任务 / 运行记录 / 告警订阅三条链路已移除），
 * 但「校验结果留痕」（{@code dq_rule_result}）仍要有人写：</p>
 * <ul>
 *   <li>{@link DqExecutionService#resolveBaseline} 靠它算「行数波动 / 行数环比」的试跑基线；</li>
 *   <li>资产目录的质量摘要靠它统计最近一次校验的异常与失败数。</li>
 * </ul>
 * <p>因此回传入口保留为一条<b>极简写入路径</b>：解析规则归属 → 校验表级读权限 → 落一行结果。
 * 不再涉及运行实例合并、任务归属与告警登记。</p>
 *
 * <h3>两个刻意保留的判定</h3>
 * <ol>
 *   <li><b>规则存在性不阻塞回传</b>：按「规则名 + 库 + 表」查不到规则时 ruleId 落 null 且不报错 ——
 *       回传失败不得改变校验任务本身的成败判定，这是提示词里的明确约定。</li>
 *   <li><b>表级读权限 fail-close</b>：回传是<b>写入</b>行为，能写一条自己读不到的表的结果
 *       就等于可以伪造它。缺表名时无从判定，不校验。</li>
 * </ol>
 */
@Service
public class DqResultService {

    @Autowired
    private DqRuleResultMapper dqRuleResultMapper;

    @Autowired
    private DqRuleMapper dqRuleMapper;

    @Autowired
    private DataAccessPermissionService permissionService;

    /**
     * 结果回传：外部校验任务把单条规则的校验结果写入平台。
     *
     * <p>路径沿用历史的 {@code POST /quality/run/report}，以免已部署环境的
     * {@code DATAFORGE_QUALITY_REPORT_URL} 失效。</p>
     */
    public void reportResult(QualityResultReportDTO dto) {
        if (dto == null || dto.getRuleName() == null || dto.getRuleName().isBlank()) {
            throw new BusinessException("上报内容缺少规则名");
        }
        assertTableReadable(dto);

        Long ruleId = resolveRuleId(dto);
        QualityCheckStatus status = Boolean.TRUE.equals(dto.getPassed())
                ? QualityCheckStatus.PASS : QualityCheckStatus.ERROR;

        DqRuleResult row = new DqRuleResult();
        row.setBatchNo(dto.getBatchNo() == null || dto.getBatchNo().isBlank()
                ? DqBatchSupport.todayBatchNo() : dto.getBatchNo().trim());
        row.setRuleId(ruleId);
        row.setRuleName(dto.getRuleName());
        row.setRuleType(dto.getRuleType());
        row.setCheckObject(dto.getCheckObject());
        row.setThreshold(dto.getThreshold());
        row.setActualValue(dto.getActualValue());
        // 原始度量值：跨表/行数类规则的实际值是涨跌幅，基线要用的是当期原值。
        // 回传侧没有单列这个字段，按实际值原样留痕（程序回传的实际值本就是原始度量）。
        row.setMetricValue(dto.getActualValue());
        row.setStatus(status.getCode());
        row.setSeverity(Boolean.TRUE.equals(dto.getPassed()) ? null : status.getCode());
        row.setCheckRecordCount(dto.getCheckRecordCount());
        row.setFailRecordCount(dto.getFailRecordCount());
        row.setErrorMsg(dto.getFailDetail());
        row.setExecSql(dto.getExecSql());
        row.setCostMs(0L);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        dqRuleResultMapper.insert(row);
    }

    /**
     * 校验当前用户对回传目标表有读权限。
     *
     * <p>缺表名时不校验（无从判定目标）；其余情况一律 fail-close。</p>
     */
    private void assertTableReadable(QualityResultReportDTO dto) {
        if (dto.getTableName() == null || dto.getTableName().isBlank()) {
            return;
        }
        if (hasTableReadAccess(dto.getDatasourceName(), dto.getDatabaseName(), dto.getTableName().trim())) {
            return;
        }
        String target = (dto.getDatabaseName() == null || dto.getDatabaseName().isBlank())
                ? dto.getTableName().trim()
                : dto.getDatabaseName().trim() + "." + dto.getTableName().trim();
        throw new BusinessException("当前用户无权读取表 " + target + "，已拒绝登记其校验结果");
    }

    /**
     * 解析本次回传对应的规则 ID。
     *
     * <p>回传若只落规则名字符串，一旦改名（设计文档改了、平台里没跟着改）就与平台规则断链；
     * 而「行数波动 / 行数环比」的基线是按 rule_id 取的，断链后这些规则永远取不到程序回传的基线。</p>
     * <ul>
     *   <li>显式传了 {@code ruleId} 直接用（S3 登记过规则时走这条）；</li>
     *   <li>否则按「规则名 + 库 + 表」三段反查 —— 三段限定是为了避免跨库同名规则误配；</li>
     *   <li>查不到返回 {@code null} 且<strong>不报错</strong>。</li>
     * </ul>
     */
    private Long resolveRuleId(QualityResultReportDTO dto) {
        if (dto.getRuleId() != null) {
            return dto.getRuleId();
        }
        LambdaQueryWrapper<DqRule> wrapper = new LambdaQueryWrapper<DqRule>()
                .eq(DqRule::getRuleName, dto.getRuleName().trim());
        if (dto.getDatabaseName() != null && !dto.getDatabaseName().isBlank()) {
            wrapper.eq(DqRule::getDatabaseName, dto.getDatabaseName().trim());
        }
        if (dto.getTableName() != null && !dto.getTableName().isBlank()) {
            wrapper.eq(DqRule::getTableName, dto.getTableName().trim());
        }
        wrapper.orderByDesc(DqRule::getId);
        List<DqRule> found = dqRuleMapper.selectList(wrapper.last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0).getId();
    }

    /** 当前用户是否可读某张表的元数据（fail-close 判断） */
    private boolean hasTableReadAccess(String datasourceName, String databaseName, String tableName) {
        if (permissionService.isAdmin()) {
            return true;
        }
        if (SecurityUtils.getCurrentUserId() == null) {
            return false;
        }
        return permissionService.hasTablePermission(datasourceName, databaseName, tableName,
                DataAccessPermissionService.TABLE_OP_SELECT);
    }
}
