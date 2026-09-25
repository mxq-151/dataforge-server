package com.bigdata.modules.system.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.security.DataAccessPermissionService;
import com.bigdata.modules.system.dto.DataResourcePermissionCheckDTO;
import com.bigdata.modules.system.entity.SysUser;
import com.bigdata.modules.system.mapper.SysUserMapper;
import com.bigdata.modules.system.vo.DataResourcePermissionCheckVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 数据资源权限批量校验服务。
 * <p>
 * 供「作业在引擎侧执行、服务端拿不到结果集」的路径（如 Spark 作业监听器）调用：
 * 作业侧解析出实际访问的资源清单后回调本服务，按提交用户逐个判定。
 * </p>
 * <p>
 * <b>默认拒绝（fail-close）</b>：身份无法确定、资源描述不完整、操作类型不认识，
 * 一律计为拒绝，不做「判不了就放行」。
 * </p>
 */
@Service
public class DataResourcePermissionCheckService {

    private static final Logger log = LoggerFactory.getLogger(DataResourcePermissionCheckService.class);

    private static final String TYPE_TABLE = "TABLE";
    private static final String TYPE_FILE = "FILE";

    private static final String OP_READ = "READ";
    private static final String OP_WRITE = "WRITE";

    @Autowired
    private DataAccessPermissionService dataAccessPermissionService;

    @Autowired
    private SysUserMapper sysUserMapper;

    public DataResourcePermissionCheckVO check(DataResourcePermissionCheckDTO request) {
        DataResourcePermissionCheckVO result = new DataResourcePermissionCheckVO();

        Long userId = resolveUserId(request);
        if (userId == null) {
            throw new BusinessException(400, "无法确定校验用户：userId / username 均未提供或用户不存在");
        }

        if (request.getResources() == null || request.getResources().isEmpty()) {
            result.setAllowed(true);
            return result;
        }

        boolean allowed = true;
        for (DataResourcePermissionCheckDTO.Resource resource : request.getResources()) {
            if (resource == null) {
                continue;
            }
            String denial = checkOne(userId, resource);
            if (denial != null) {
                allowed = false;
                result.getViolations().add(new DataResourcePermissionCheckVO.Violation(
                        describe(resource), normalizeOperation(resource), denial));
            }
        }
        result.setAllowed(allowed);
        if (!allowed) {
            log.warn("数据资源权限校验未通过: userId={}, 违规数={}/{}",
                    userId, result.getViolations().size(), request.getResources().size());
        }
        return result;
    }

    /** 校验单个资源，返回 null 表示通过，否则返回拒绝原因。 */
    private String checkOne(Long userId, DataResourcePermissionCheckDTO.Resource resource) {
        String type = resource.getResourceType() == null
                ? "" : resource.getResourceType().trim().toUpperCase(Locale.ROOT);
        if (TYPE_TABLE.equals(type)) {
            if (isBlank(resource.getTableName())) {
                return "资源描述不完整：缺少表名，无法判定表权限";
            }
            String operation = normalizeOperation(resource);
            boolean permitted = dataAccessPermissionService.hasTablePermissionForUser(
                    userId, resource.getDatasourceName(), resource.getDatabaseName(),
                    resource.getTableName(), operation);
            return permitted ? null
                    : "无权对表 " + describe(resource) + " 执行 " + operation + " 操作";
        }
        if (TYPE_FILE.equals(type)) {
            if (isBlank(resource.getPath())) {
                return "资源描述不完整：缺少文件路径，无法判定文件权限";
            }
            String operation = normalizeOperation(resource);
            int requiredType = OP_WRITE.equals(operation)
                    ? DataAccessPermissionService.FILE_PERMISSION_WRITE
                    : DataAccessPermissionService.FILE_PERMISSION_READ;
            boolean permitted = dataAccessPermissionService.hasFilePermissionForUser(
                    userId, resource.getPath(), resource.getStorageConfigId(), requiredType);
            return permitted ? null : "无权访问文件: " + resource.getPath();
        }
        return "无法识别的资源类型: " + resource.getResourceType();
    }

    /** 解析校验用户：userId 优先，其次按用户名反查。 */
    private Long resolveUserId(DataResourcePermissionCheckDTO request) {
        if (request.getUserId() != null) {
            return request.getUserId();
        }
        if (isBlank(request.getUsername())) {
            return null;
        }
        SysUser user = sysUserMapper.selectByUsername(request.getUsername().trim());
        return user != null ? user.getId() : null;
    }

    /** 操作类型归一化：TABLE 缺省 SELECT，FILE 缺省 READ。 */
    private String normalizeOperation(DataResourcePermissionCheckDTO.Resource resource) {
        String operation = resource.getOperation();
        if (operation != null && !operation.trim().isEmpty()) {
            return operation.trim().toUpperCase(Locale.ROOT);
        }
        String type = resource.getResourceType() == null
                ? "" : resource.getResourceType().trim().toUpperCase(Locale.ROOT);
        return TYPE_FILE.equals(type) ? OP_READ : DataAccessPermissionService.TABLE_OP_SELECT;
    }

    /** 资源描述：表为 datasource:db.table，文件为路径。 */
    private String describe(DataResourcePermissionCheckDTO.Resource resource) {
        String type = resource.getResourceType() == null
                ? "" : resource.getResourceType().trim().toUpperCase(Locale.ROOT);
        if (TYPE_FILE.equals(type)) {
            return String.valueOf(resource.getPath());
        }
        StringBuilder sb = new StringBuilder();
        if (!isBlank(resource.getDatasourceName())) {
            sb.append(resource.getDatasourceName()).append(':');
        }
        if (!isBlank(resource.getDatabaseName())) {
            sb.append(resource.getDatabaseName()).append('.');
        }
        return sb.append(resource.getTableName()).toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}