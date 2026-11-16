package com.bigdata.modules.telemetry.config.properties;

import java.util.ArrayList;
import java.util.List;

public class LocalDiskConfig {
    private String id = "local-disk";
    private List<String> allowRootDirs = new ArrayList<>();
    private List<LogSourceConfig> sources = new ArrayList<>();
    private LogLimits limits = new LogLimits();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getAllowRootDirs() {
        return allowRootDirs;
    }

    public void setAllowRootDirs(List<String> allowRootDirs) {
        this.allowRootDirs = allowRootDirs;
    }

    public List<LogSourceConfig> getSources() {
        return sources;
    }

    public void setSources(List<LogSourceConfig> sources) {
        this.sources = sources;
    }

    public LogLimits getLimits() {
        return limits;
    }

    public void setLimits(LogLimits limits) {
        this.limits = limits;
    }
}
