package com.bigdata.modules.devops.vo;

import com.bigdata.modules.devops.enums.DatasourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link DatasourceTypeMetaVO} 能正确暴露合并后的元数据，
 * 供 {@code GET /devops/datasource/types} 返回给前端（exec / store / language）。
 */
public class DatasourceTypeMetaVOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void starrocksIsExecutableAndStorage() {
        DatasourceTypeMetaVO vo = DatasourceTypeMetaVO.from(DatasourceType.STARROCKS);
        assertTrue(vo.isExec(), "STARROCKS 应具备计算能力");
        assertTrue(vo.isStore(), "STARROCKS 应具备存储能力");
        assertEquals("starrocks", vo.getCode());
        assertEquals(List.of("sql"), vo.getLanguage());
    }

    @Test
    void serializesWithExecStoreAndLanguage() throws Exception {
        List<DatasourceTypeMetaVO> list = DatasourceType.allTypes().stream()
                .map(DatasourceTypeMetaVO::from)
                .collect(Collectors.toList());

        String json = mapper.writeValueAsString(list);

        // 旧逻辑只序列化 code 字符串，现在应含 exec/store/language
        assertTrue(json.contains("\"exec\":true"), "应包含 exec 标记");
        assertTrue(json.contains("\"store\":true"), "应包含 store 标记");
        assertTrue(json.contains("\"language\":[\"sql\"]"), "应包含 language 列表");
        assertTrue(json.contains("\"code\":\"starrocks\""), "应包含 starrocks 条目");
        // 确认 starrocks 现在是 exec=true（而非之前的 false）
        assertTrue(json.contains("\"code\":\"starrocks\",\"description\":\"StarRocks\",\"exec\":true"),
                "starrocks 应为 exec=true");
    }
}
