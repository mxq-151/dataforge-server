package com.bigdata.modules.integration.service.submit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JobIdParserTest {

    private static final String FLINK_ID = "9d3f1c0a2b7e4d5f8a6c1b2d3e4f5a6b";

    @Test
    void 解析flink提交输出() {
        assertEquals(FLINK_ID, JobIdParser.parseFlink(
                "Job has been submitted with JobID " + FLINK_ID));
        assertEquals(FLINK_ID, JobIdParser.parseFlink(
                "Job ID: " + FLINK_ID + "\n"));
        assertEquals(FLINK_ID, JobIdParser.parseFlink(
                "http://localhost:8081/#/job/" + FLINK_ID + "/overview"));
    }

    @Test
    void flink无jobId时返回null() {
        assertNull(JobIdParser.parseFlink("Job submitted, waiting..."));
        assertNull(JobIdParser.parseFlink(""));
        assertNull(JobIdParser.parseFlink(null));
    }

    @Test
    void 从flink_list输出里解析状态() {
        String output = "------------------ Running Jobs ------------------\n"
                + ": " + FLINK_ID + " : RUNNING : order-sync\n"
                + "--------------------------------------------------";
        assertEquals("RUNNING", JobIdParser.findFlinkState(output, FLINK_ID));
        assertNull(JobIdParser.findFlinkState(output, "00000000000000000000000000000000"));
    }

    @Test
    void 从flink18列表格式里解析状态() {
        String output = "Waiting for response...\n"
                + "------------------ Running/Restarting Jobs -------------------\n"
                + "09.11.2026 13:36:00 : " + FLINK_ID + " : order-sync (RUNNING)\n"
                + "--------------------------------------------------------------";
        assertEquals("RUNNING", JobIdParser.findFlinkState(output, FLINK_ID));
    }

    @Test
    void 解析通用状态字段() {
        assertEquals("RUNNING", JobIdParser.findGenericState("status: running"));
        assertEquals("FAILED", JobIdParser.findGenericState("state=FAILED"));
        assertNull(JobIdParser.findGenericState("no state here"));
    }
}
