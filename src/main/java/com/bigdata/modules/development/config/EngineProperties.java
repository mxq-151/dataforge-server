package com.bigdata.modules.development.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {

    private Spark spark = new Spark();
    private Flink flink = new Flink();
    private Doris doris = new Doris();
    private HBase hbase = new HBase();
    private Jar jar = new Jar();

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

    public Doris getDoris() {
        return doris;
    }

    public void setDoris(Doris doris) {
        this.doris = doris;
    }

    public HBase getHbase() {
        return hbase;
    }

    public void setHbase(HBase hbase) {
        this.hbase = hbase;
    }

    public Jar getJar() {
        return jar;
    }

    public void setJar(Jar jar) {
        this.jar = jar;
    }

    public static class Jar {
        private String basePath;

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
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

        public String getSparkSqlCommand() {
            if (home != null && !home.trim().isEmpty()) {
                return home.trim().endsWith("/") ? home + "bin/spark-sql" : home + "/bin/spark-sql";
            }
            return "spark-sql";
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

    public static class Doris {
        private Integer queryTimeout = 600;
        private Integer insertTimeout = 600;
        private Integer batchSize = 4096;
        private Integer parallelFragmentExecInstanceNum = 4;

        public Integer getQueryTimeout() {
            return queryTimeout;
        }

        public void setQueryTimeout(Integer queryTimeout) {
            this.queryTimeout = queryTimeout;
        }

        public Integer getInsertTimeout() {
            return insertTimeout;
        }

        public void setInsertTimeout(Integer insertTimeout) {
            this.insertTimeout = insertTimeout;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }

        public Integer getParallelFragmentExecInstanceNum() {
            return parallelFragmentExecInstanceNum;
        }

        public void setParallelFragmentExecInstanceNum(Integer parallelFragmentExecInstanceNum) {
            this.parallelFragmentExecInstanceNum = parallelFragmentExecInstanceNum;
        }
    }

    public static class HBase {
        private String zkQuorum = "localhost";
        private String zkPort = "2181";
        private String namespace = "default";

        public String getZkQuorum() {
            return zkQuorum;
        }

        public void setZkQuorum(String zkQuorum) {
            this.zkQuorum = zkQuorum;
        }

        public String getZkPort() {
            return zkPort;
        }

        public void setZkPort(String zkPort) {
            this.zkPort = zkPort;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }
}
