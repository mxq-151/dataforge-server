package com.bigdata.modules.system.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据资源权限批量校验结果。
 */
public class DataResourcePermissionCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否全部通过：任一资源被拒绝即为 false。 */
    private boolean allowed;

    /** 被拒绝的资源明细；全部通过时为空集合。 */
    private List<Violation> violations = new ArrayList<>();

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public void setViolations(List<Violation> violations) {
        this.violations = violations != null ? violations : new ArrayList<>();
    }

    /**
     * 一条被拒绝的资源。
     */
    public static class Violation implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 资源描述，例如 {@code db.table} 或文件路径。 */
        private String resource;

        /** 需要的操作类型。 */
        private String operation;

        /** 拒绝原因。 */
        private String reason;

        public Violation() {
        }

        public Violation(String resource, String operation, String reason) {
            this.resource = resource;
            this.operation = operation;
            this.reason = reason;
        }

        public String getResource() {
            return resource;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}