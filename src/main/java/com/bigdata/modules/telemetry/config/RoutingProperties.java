package com.bigdata.modules.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由配置（{@code telemetry.routing.*}）。
 *
 * <p>规则按 {@code serviceType + componentId} > {@code serviceType} > 默认顺序匹配；
 * 等价组必须显式声明，未声明的后端即使目录里写了同一个 group 也不会互相回退。</p>
 */
@Component
@ConfigurationProperties(prefix = "telemetry")
public class RoutingProperties {

    private Routing routing = new Routing();

    public Routing getRouting() {
        return routing;
    }

    public void setRouting(Routing routing) {
        this.routing = routing;
    }

    public static class Routing {
        private Category metric = new Category();
        private Category log = new Category();
        private Circuit circuit = new Circuit();
        private Map<String, EquivalenceGroup> equivalenceGroups = new LinkedHashMap<>();

        public Category getMetric() {
            return metric;
        }

        public void setMetric(Category metric) {
            this.metric = metric;
        }

        public Category getLog() {
            return log;
        }

        public void setLog(Category log) {
            this.log = log;
        }

        public Circuit getCircuit() {
            return circuit;
        }

        public void setCircuit(Circuit circuit) {
            this.circuit = circuit;
        }

        public Map<String, EquivalenceGroup> getEquivalenceGroups() {
            return equivalenceGroups;
        }

        public void setEquivalenceGroups(Map<String, EquivalenceGroup> equivalenceGroups) {
            this.equivalenceGroups = equivalenceGroups;
        }
    }

    public static class Category {
        private List<String> defaultOrder = new ArrayList<>();
        private List<Rule> rules = new ArrayList<>();

        public List<String> getDefaultOrder() {
            return defaultOrder;
        }

        public void setDefaultOrder(List<String> defaultOrder) {
            this.defaultOrder = defaultOrder;
        }

        public List<Rule> getRules() {
            return rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = rules;
        }
    }

    public static class Rule {
        private String serviceType = "";
        private String componentId = "";
        /** 指标名 glob；日志规则留空。 */
        private String metric = "";
        /** 日志流名；指标规则留空。 */
        private String stream = "";
        private List<String> order = new ArrayList<>();

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }

        public String getComponentId() {
            return componentId;
        }

        public void setComponentId(String componentId) {
            this.componentId = componentId;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }

        public String getStream() {
            return stream;
        }

        public void setStream(String stream) {
            this.stream = stream;
        }

        public List<String> getOrder() {
            return order;
        }

        public void setOrder(List<String> order) {
            this.order = order;
        }
    }

    public static class EquivalenceGroup {
        /** 实例 id 或后端类型 wire 名。 */
        private List<String> backends = new ArrayList<>();
        /** 为空表示不限制服务类型。 */
        private String serviceType = "";

        public List<String> getBackends() {
            return backends;
        }

        public void setBackends(List<String> backends) {
            this.backends = backends;
        }

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }
    }

    public static class Circuit {
        private int failureThreshold = 5;
        private long openMs = 30000L;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenMs() {
            return openMs;
        }

        public void setOpenMs(long openMs) {
            this.openMs = openMs;
        }
    }
}
