package com.bigdata.modules.development.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {

    private Spark spark = new Spark();
    private Flink flink = new Flink();

    public Spark getSpark() {
        return spark;
    }

    public void setSpark(Spark spark) {
        this.spark = spark;
    }

    public Flink getFlink() {
        return flink;
    }

    public void setFlink(Flink flink) {
        this.flink = flink;
    }

    public static class Spark {
        private String home;

        public String getHome() {
            return home;
        }

        public void setHome(String home) {
            this.home = home;
        }

        public String getSparkSubmitCommand() {
            if (home != null && !home.trim().isEmpty()) {
                return home.trim().endsWith("/") ? home + "bin/spark-submit" : home + "/bin/spark-submit";
            }
            return "spark-submit";
        }
    }

    public static class Flink {
        private String home;

        public String getHome() {
            return home;
        }

        public void setHome(String home) {
            this.home = home;
        }

        public String getFlinkCommand() {
            if (home != null && !home.trim().isEmpty()) {
                return home.trim().endsWith("/") ? home + "bin/flink" : home + "/bin/flink";
            }
            return "flink";
        }
    }
}
