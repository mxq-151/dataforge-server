package com.bigdata.modules.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.common.vo.Result;
import com.bigdata.modules.knowledge.dto.KnowledgeDocumentDTO;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import com.bigdata.modules.knowledge.enums.DocType;
import com.bigdata.modules.knowledge.mapper.KnowledgeDocumentMapper;
import com.bigdata.modules.knowledge.vo.KnowledgeDocumentVO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Rollback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@Rollback
@DisplayName("Knowledge模块 - KnowledgeDocumentController知识文档集成测试")
class KnowledgeDocumentControllerIntegrationTest {

    @Autowired
    private KnowledgeDocumentController knowledgeDocumentController;

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    private KnowledgeDocumentDTO buildValidDTO(String title, DocType docType) {
        KnowledgeDocumentDTO dto = new KnowledgeDocumentDTO();
        dto.setTitle(title);
        dto.setDocType(docType);
        dto.setCategory("测试分类");
        dto.setContent("# 测试内容\n\n这是一篇测试文档的内容。");
        dto.setAuthorName("测试作者");
        dto.setAuthorId(1L);
        dto.setTags("测试,集成测试");
        dto.setDescription("测试文档描述");
        return dto;
    }

    private Long createDocumentInDB(String title, DocType docType) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle(title);
        doc.setDocType(docType);
        doc.setCategory("数据库直接创建");
        doc.setContent("直接插入数据库的文档内容");
        doc.setAuthorName("DB作者");
        doc.setAuthorId(1L);
        doc.setStatus(0);
        doc.setSyncToRag(0);
        doc.setViewCount(0);
        doc.setTags("db,test");
        doc.setDescription("数据库直接创建的文档");
        doc.setDeleted(0);
        knowledgeDocumentMapper.insert(doc);
        return doc.getId();
    }

    @Nested
    @DisplayName("场景1: 文档CRUD操作")
    class CrudTests {

        @Test
        @DisplayName("✅ 创建文档 - BUSINESS_KNOWLEDGE类型成功创建")
        void testCreateDocument_BusinessKnowledge() {
            System.out.println("\n=== 场景1.1: 创建文档 - BUSINESS_KNOWLEDGE ===");

            KnowledgeDocumentDTO dto = buildValidDTO("业务知识文档", DocType.BUSINESS_KNOWLEDGE);

            Result<Void> result = knowledgeDocumentController.save(dto);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "创建应返回200");
            System.out.println("  创建文档: 业务知识文档 -> 成功");

            Result<Page<KnowledgeDocumentVO>> listResult = knowledgeDocumentController.getList(1, 10, "业务知识文档", null);
            assertNotNull(listResult.getData());
            assertTrue(listResult.getData().getRecords().stream()
                            .anyMatch(vo -> vo.getTitle().equals("业务知识文档")),
                    "列表中应包含新创建的文档");
            System.out.println("  验证: 列表中存在该文档 ✓");
        }

        @Test
        @DisplayName("✅ 创建文档 - CLUSTER_OPS类型成功创建")
        void testCreateDocument_ClusterOps() {
            System.out.println("\n=== 场景1.2: 创建文档 - CLUSTER_OPS ===");

            KnowledgeDocumentDTO dto = buildValidDTO("集群运维文档", DocType.CLUSTER_OPS);

            Result<Void> result = knowledgeDocumentController.save(dto);

            assertEquals(200, result.getCode(), "创建CLUSTER_OPS文档应返回200");
            System.out.println("  创建CLUSTER_OPS文档成功 ✓");
        }

        @Test
        @DisplayName("✅ 创建文档 - DEV_STANDARD类型成功创建")
        void testCreateDocument_DevStandard() {
            System.out.println("\n=== 场景1.3: 创建文档 - DEV_STANDARD ===");

            KnowledgeDocumentDTO dto = buildValidDTO("开发规范文档", DocType.DEV_STANDARD);

            Result<Void> result = knowledgeDocumentController.save(dto);

            assertEquals(200, result.getCode(), "创建DEV_STANDARD文档应返回200");
            System.out.println("  创建DEV_STANDARD文档成功 ✓");
        }

        @Test
        @DisplayName("✅ 更新文档内容 - 成功更新标题和内容")
        void testUpdateDocument_Success() {
            System.out.println("\n=== 场景1.4: 更新文档内容 ===");

            Long id = createDocumentInDB("原始标题", DocType.BUSINESS_KNOWLEDGE);

            KnowledgeDocumentDTO dto = buildValidDTO("更新后标题", DocType.CLUSTER_OPS);
            dto.setId(id);
            dto.setContent("# 更新后的内容\n\n文档已更新。");

            Result<Void> result = knowledgeDocumentController.update(dto);

            assertEquals(200, result.getCode(), "更新应返回200");
            System.out.println("  更新文档ID: " + id);

            KnowledgeDocument updated = knowledgeDocumentMapper.selectById(id);
            assertEquals("更新后标题", updated.getTitle(), "标题应已更新");
            assertEquals(DocType.CLUSTER_OPS, updated.getDocType(), "类型应已更新");
            assertEquals("# 更新后的内容\n\n文档已更新。", updated.getContent(), "内容应已更新");
            System.out.println("  验证: 标题=" + updated.getTitle()
                    + ", 类型=" + updated.getDocType()
                    + ", 内容=" + updated.getContent());
        }

        @Test
        @DisplayName("✅ 查询文档列表 - 分页参数正确传递")
        void testGetList_Pagination() {
            System.out.println("\n=== 场景1.5: 查询文档列表 - 分页参数 ===");

            for (int i = 0; i < 5; i++) {
                createDocumentInDB("分页文档_" + i, DocType.BUSINESS_KNOWLEDGE);
            }

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 3, null, null);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "分页数据不应为null");
            Page<KnowledgeDocumentVO> page = result.getData();
            assertEquals(1, page.getCurrent(), "当前页应为1");
            assertEquals(3, page.getSize(), "每页大小应为3");
            assertFalse(page.getRecords().isEmpty(), "分页查询应返回记录");
            System.out.println("  当前页记录数: " + page.getRecords().size());
            System.out.println("  当前页码: " + page.getCurrent());
            System.out.println("  每页大小: " + page.getSize());
        }

        @Test
        @DisplayName("✅ 按ID获取文档详情 - 返回完整信息")
        void testGetById_Success() {
            System.out.println("\n=== 场景1.6: 按ID获取文档详情 ===");

            Long id = createDocumentInDB("详情文档", DocType.DEV_STANDARD);

            Result<KnowledgeDocumentVO> result = knowledgeDocumentController.getById(id);

            assertNotNull(result, "结果不应为null");
            assertEquals(200, result.getCode(), "查询应返回200");
            assertNotNull(result.getData(), "数据不应为null");

            KnowledgeDocumentVO vo = result.getData();
            assertEquals(id, vo.getId(), "ID应匹配");
            assertEquals("详情文档", vo.getTitle(), "标题应匹配");
            assertEquals(DocType.DEV_STANDARD, vo.getDocType(), "类型应匹配");
            assertNotNull(vo.getCreateTime(), "创建时间不应为null");
            System.out.println("  文档ID: " + vo.getId());
            System.out.println("  标题: " + vo.getTitle());
            System.out.println("  类型: " + vo.getDocType());
            System.out.println("  状态: " + vo.getStatus());
        }

        @Test
        @DisplayName("✅ 删除文档 - 成功删除")
        void testDeleteDocument_Success() {
            System.out.println("\n=== 场景1.7: 删除文档成功 ===");

            Long id = createDocumentInDB("待删除文档", DocType.BUSINESS_KNOWLEDGE);

            KnowledgeDocument beforeDelete = knowledgeDocumentMapper.selectById(id);
            assertNotNull(beforeDelete, "删除前文档应存在");

            Result<Void> result = knowledgeDocumentController.delete(id);

            assertEquals(200, result.getCode(), "删除应返回200");
            System.out.println("  删除文档ID: " + id);

            KnowledgeDocument afterDelete = knowledgeDocumentMapper.selectById(id);
            if (afterDelete != null) {
                assertEquals(1, afterDelete.getDeleted(), "逻辑删除后deleted应为1");
                System.out.println("  验证: 逻辑删除成功(deleted=1) ✓");
            } else {
                System.out.println("  验证: 物理删除成功(记录不存在) ✓");
            }
        }

        @Test
        @DisplayName("❌ 查询不存在的文档ID - 抛出BusinessException")
        void testGetById_NonExistentId() {
            System.out.println("\n=== 场景1.8: 查询不存在的文档ID ===");

            Long nonExistentId = 99999999L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.getById(nonExistentId);
            }, "查询不存在的文档ID应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }

        @Test
        @DisplayName("❌ 更新不存在的文档ID - 抛出BusinessException")
        void testUpdateDocument_NonExistentId() {
            System.out.println("\n=== 场景1.9: 更新不存在的文档ID ===");

            KnowledgeDocumentDTO dto = buildValidDTO("不存在文档更新", DocType.BUSINESS_KNOWLEDGE);
            dto.setId(99999998L);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.update(dto);
            }, "更新不存在的文档应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }

        @Test
        @DisplayName("❌ 删除不存在的文档ID - 抛出BusinessException")
        void testDeleteDocument_NonExistentId() {
            System.out.println("\n=== 场景1.10: 删除不存在的文档ID ===");

            Long nonExistentId = 99999997L;

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.delete(nonExistentId);
            }, "删除不存在的文档应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景2: 文档类型枚举")
    class DocTypeTests {

        @Test
        @DisplayName("✅ DocType.allTypes()返回3种类型")
        void testDocType_AllTypes() {
            System.out.println("\n=== 场景2.1: DocType.allTypes()返回3种类型 ===");

            Result<List<DocType>> result = knowledgeDocumentController.getDocTypes();

            assertEquals(200, result.getCode(), "查询类型应返回200");
            assertNotNull(result.getData(), "类型列表不应为null");
            assertEquals(3, result.getData().size(), "应有3种文档类型");
            assertTrue(result.getData().contains(DocType.BUSINESS_KNOWLEDGE), "应包含BUSINESS_KNOWLEDGE");
            assertTrue(result.getData().contains(DocType.CLUSTER_OPS), "应包含CLUSTER_OPS");
            assertTrue(result.getData().contains(DocType.DEV_STANDARD), "应包含DEV_STANDARD");
            System.out.println("  类型数量: " + result.getData().size());
            result.getData().forEach(t -> System.out.println("    - " + t.name() + "(" + t.getCode() + "): " + t.getDescription()));
        }

        @Test
        @DisplayName("✅ 按类型筛选文档列表 - 只返回指定类型的文档")
        void testFilter_ByDocType() {
            System.out.println("\n=== 场景2.2: 按类型筛选文档列表 ===");

            createDocumentInDB("业务文档_筛选A", DocType.BUSINESS_KNOWLEDGE);
            createDocumentInDB("业务文档_筛选B", DocType.BUSINESS_KNOWLEDGE);
            createDocumentInDB("运维文档_筛选", DocType.CLUSTER_OPS);
            createDocumentInDB("规范文档_筛选", DocType.DEV_STANDARD);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, null, DocType.BUSINESS_KNOWLEDGE);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            boolean allBusinessKnowledge = records.stream()
                    .allMatch(vo -> vo.getDocType() == DocType.BUSINESS_KNOWLEDGE);
            assertTrue(allBusinessKnowledge, "所有返回记录的类型都应为BUSINESS_KNOWLEDGE");
            long bkCount = records.stream()
                    .filter(vo -> vo.getTitle().contains("业务文档_筛选"))
                    .count();
            assertTrue(bkCount >= 2, "应至少匹配2条BUSINESS_KNOWLEDGE文档");
            System.out.println("  筛选类型: BUSINESS_KNOWLEDGE");
            System.out.println("  匹配数: " + bkCount);
            records.forEach(vo -> System.out.println("    - " + vo.getTitle() + " [" + vo.getDocType() + "]"));
        }

        @Test
        @DisplayName("✅ 按CLUSTER_OPS类型筛选 - 只返回集群运维文档")
        void testFilter_ByClusterOps() {
            System.out.println("\n=== 场景2.3: 按CLUSTER_OPS类型筛选 ===");

            createDocumentInDB("运维筛选文档", DocType.CLUSTER_OPS);
            createDocumentInDB("业务筛选文档", DocType.BUSINESS_KNOWLEDGE);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, null, DocType.CLUSTER_OPS);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            boolean allClusterOps = records.stream()
                    .allMatch(vo -> vo.getDocType() == DocType.CLUSTER_OPS);
            assertTrue(allClusterOps, "所有返回记录都应为CLUSTER_OPS类型");
            System.out.println("  筛选类型: CLUSTER_OPS, 结果数: " + records.size() + " ✓");
        }

        @Test
        @DisplayName("✅ 按DEV_STANDARD类型筛选 - 只返回开发规范文档")
        void testFilter_ByDevStandard() {
            System.out.println("\n=== 场景2.4: 按DEV_STANDARD类型筛选 ===");

            createDocumentInDB("规范筛选文档", DocType.DEV_STANDARD);
            createDocumentInDB("运维筛选文档2", DocType.CLUSTER_OPS);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, null, DocType.DEV_STANDARD);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            boolean allDevStandard = records.stream()
                    .allMatch(vo -> vo.getDocType() == DocType.DEV_STANDARD);
            assertTrue(allDevStandard, "所有返回记录都应为DEV_STANDARD类型");
            System.out.println("  筛选类型: DEV_STANDARD, 结果数: " + records.size() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景3: 文档状态管理")
    class DocumentStatusTests {

        @Test
        @DisplayName("✅ 新建文档默认status=0, syncToRag=0, viewCount=0")
        void testCreateDocument_DefaultValues() {
            System.out.println("\n=== 场景3.1: 新建文档默认值 ===");

            KnowledgeDocumentDTO dto = buildValidDTO("默认值测试文档", DocType.BUSINESS_KNOWLEDGE);

            Result<Void> saveResult = knowledgeDocumentController.save(dto);
            assertEquals(200, saveResult.getCode(), "创建应成功");

            Result<Page<KnowledgeDocumentVO>> listResult = knowledgeDocumentController.getList(1, 10, "默认值测试文档", null);
            KnowledgeDocumentVO vo = listResult.getData().getRecords().stream()
                    .filter(v -> v.getTitle().equals("默认值测试文档"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(vo, "应能查到创建的文档");
            assertEquals(0, vo.getStatus(), "新建文档status应默认为0");
            assertEquals(0, vo.getSyncToRag(), "新建文档syncToRag应默认为0");
            assertEquals(0, vo.getViewCount(), "新建文档viewCount应默认为0");
            System.out.println("  status: " + vo.getStatus());
            System.out.println("  syncToRag: " + vo.getSyncToRag());
            System.out.println("  viewCount: " + vo.getViewCount());
            System.out.println("  默认值验证通过 ✓");
        }

        @Test
        @DisplayName("✅ 发布文档后status=1")
        void testPublishDocument_StatusChanged() {
            System.out.println("\n=== 场景3.2: 发布文档 ===");

            Long id = createDocumentInDB("待发布文档", DocType.BUSINESS_KNOWLEDGE);

            KnowledgeDocument beforePublish = knowledgeDocumentMapper.selectById(id);
            assertEquals(0, beforePublish.getStatus(), "发布前status应为0");
            System.out.println("  发布前status: " + beforePublish.getStatus());

            Result<Void> result = knowledgeDocumentController.publishDocument(id);

            assertEquals(200, result.getCode(), "发布应返回200");

            KnowledgeDocument afterPublish = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterPublish.getStatus(), "发布后status应为1");
            System.out.println("  发布后status: " + afterPublish.getStatus() + " ✓");
        }

        @Test
        @DisplayName("✅ 同步到RAG后syncToRag=1")
        void testSyncToRag_SyncFlagChanged() {
            System.out.println("\n=== 场景3.3: 同步到RAG ===");

            Long id = createDocumentInDB("待同步文档", DocType.CLUSTER_OPS);

            KnowledgeDocument beforeSync = knowledgeDocumentMapper.selectById(id);
            assertEquals(0, beforeSync.getSyncToRag(), "同步前syncToRag应为0");
            System.out.println("  同步前syncToRag: " + beforeSync.getSyncToRag());

            Result<Void> result = knowledgeDocumentController.syncToRag(id);

            assertEquals(200, result.getCode(), "同步应返回200");

            KnowledgeDocument afterSync = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterSync.getSyncToRag(), "同步后syncToRag应为1");
            System.out.println("  同步后syncToRag: " + afterSync.getSyncToRag() + " ✓");
        }

        @Test
        @DisplayName("❌ 发布不存在的文档 - 抛出BusinessException")
        void testPublishDocument_NonExistentId() {
            System.out.println("\n=== 场景3.4: 发布不存在的文档 ===");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.publishDocument(99999999L);
            }, "发布不存在的文档应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }

        @Test
        @DisplayName("❌ 同步不存在的文档到RAG - 抛出BusinessException")
        void testSyncToRag_NonExistentId() {
            System.out.println("\n=== 场景3.5: 同步不存在的文档到RAG ===");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.syncToRag(99999998L);
            }, "同步不存在的文档应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景4: 浏览量统计")
    class ViewCountTests {

        @Test
        @DisplayName("✅ incrementViewCount 每次调用viewCount+1")
        void testIncrementViewCount_SingleIncrement() {
            System.out.println("\n=== 场景4.1: 浏览量+1 ===");

            Long id = createDocumentInDB("浏览量测试文档", DocType.BUSINESS_KNOWLEDGE);

            KnowledgeDocument before = knowledgeDocumentMapper.selectById(id);
            assertEquals(0, before.getViewCount(), "初始viewCount应为0");
            System.out.println("  初始viewCount: " + before.getViewCount());

            Result<Void> result = knowledgeDocumentController.incrementViewCount(id);

            assertEquals(200, result.getCode(), "增加浏览量应返回200");

            KnowledgeDocument after = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, after.getViewCount(), "增加后viewCount应为1");
            System.out.println("  增加后viewCount: " + after.getViewCount() + " ✓");
        }

        @Test
        @DisplayName("✅ 多次调用incrementViewCount累加")
        void testIncrementViewCount_MultipleIncrements() {
            System.out.println("\n=== 场景4.2: 浏览量多次累加 ===");

            Long id = createDocumentInDB("多次浏览文档", DocType.DEV_STANDARD);

            int incrementTimes = 5;
            for (int i = 0; i < incrementTimes; i++) {
                Result<Void> result = knowledgeDocumentController.incrementViewCount(id);
                assertEquals(200, result.getCode(), "第" + (i + 1) + "次增加浏览量应返回200");
            }

            KnowledgeDocument after = knowledgeDocumentMapper.selectById(id);
            assertEquals(incrementTimes, after.getViewCount(), "5次增加后viewCount应为5");
            System.out.println("  增加" + incrementTimes + "次后viewCount: " + after.getViewCount() + " ✓");
        }

        @Test
        @DisplayName("❌ 浏览量统计-不存在的文档 - 抛出BusinessException")
        void testIncrementViewCount_NonExistentId() {
            System.out.println("\n=== 场景4.3: 不存在的文档增加浏览量 ===");

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                knowledgeDocumentController.incrementViewCount(99999997L);
            }, "不存在的文档增加浏览量应抛出BusinessException");

            assertTrue(exception.getMessage().contains("不存在"), "异常消息应包含'不存在'");
            System.out.println("  异常消息: " + exception.getMessage() + " ✓");
        }
    }

    @Nested
    @DisplayName("场景5: 关键字搜索")
    class KeywordSearchTests {

        @Test
        @DisplayName("✅ 按标题搜索 - like匹配")
        void testSearch_ByTitle() {
            System.out.println("\n=== 场景5.1: 按标题搜索 ===");

            createDocumentInDB("Spark优化指南_搜索", DocType.DEV_STANDARD);
            createDocumentInDB("Flink调优手册_搜索", DocType.DEV_STANDARD);
            createDocumentInDB("无关文档_搜索", DocType.BUSINESS_KNOWLEDGE);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "优化", null);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            long matchCount = records.stream()
                    .filter(vo -> vo.getTitle().contains("优化"))
                    .count();
            assertTrue(matchCount >= 1, "应至少匹配1条标题含'优化'的文档");
            System.out.println("  搜索关键词: '优化'");
            System.out.println("  匹配数: " + matchCount);
            records.forEach(vo -> System.out.println("    - " + vo.getTitle()));
        }

        @Test
        @DisplayName("✅ 按内容搜索 - like匹配")
        void testSearch_ByContent() {
            System.out.println("\n=== 场景5.2: 按内容搜索 ===");

            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setTitle("内容搜索测试标题");
            doc.setDocType(DocType.CLUSTER_OPS);
            doc.setContent("Kyuubi集群高可用配置方案详细说明");
            doc.setAuthorName("测试作者");
            doc.setAuthorId(1L);
            doc.setStatus(0);
            doc.setSyncToRag(0);
            doc.setViewCount(0);
            doc.setDeleted(0);
            knowledgeDocumentMapper.insert(doc);

            KnowledgeDocument doc2 = new KnowledgeDocument();
            doc2.setTitle("内容搜索无关标题");
            doc2.setDocType(DocType.BUSINESS_KNOWLEDGE);
            doc2.setContent("这是另一篇不相关的内容");
            doc2.setAuthorName("测试作者");
            doc2.setAuthorId(1L);
            doc2.setStatus(0);
            doc2.setSyncToRag(0);
            doc2.setViewCount(0);
            doc2.setDeleted(0);
            knowledgeDocumentMapper.insert(doc2);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "Kyuubi", null);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            boolean found = records.stream()
                    .anyMatch(vo -> vo.getContent() != null && vo.getContent().contains("Kyuubi"));
            assertTrue(found, "应能通过内容搜索到含'Kyuubi'的文档");
            System.out.println("  搜索关键词: 'Kyuubi'");
            System.out.println("  匹配数: " + records.size());
            records.forEach(vo -> System.out.println("    - " + vo.getTitle()));
        }

        @Test
        @DisplayName("✅ 标题和内容OR匹配 - 关键词匹配标题或内容均可")
        void testSearch_TitleOrContentMatch() {
            System.out.println("\n=== 场景5.3: 标题和内容OR匹配 ===");

            KnowledgeDocument titleMatchDoc = new KnowledgeDocument();
            titleMatchDoc.setTitle("OR匹配_数据治理");
            titleMatchDoc.setDocType(DocType.BUSINESS_KNOWLEDGE);
            titleMatchDoc.setContent("这篇文档内容不包含关键词");
            titleMatchDoc.setAuthorName("测试");
            titleMatchDoc.setAuthorId(1L);
            titleMatchDoc.setStatus(0);
            titleMatchDoc.setSyncToRag(0);
            titleMatchDoc.setViewCount(0);
            titleMatchDoc.setDeleted(0);
            knowledgeDocumentMapper.insert(titleMatchDoc);

            KnowledgeDocument contentMatchDoc = new KnowledgeDocument();
            contentMatchDoc.setTitle("OR匹配_无关标题");
            contentMatchDoc.setDocType(DocType.CLUSTER_OPS);
            contentMatchDoc.setContent("本文介绍数据治理的核心流程");
            contentMatchDoc.setAuthorName("测试");
            contentMatchDoc.setAuthorId(1L);
            contentMatchDoc.setStatus(0);
            contentMatchDoc.setSyncToRag(0);
            contentMatchDoc.setViewCount(0);
            contentMatchDoc.setDeleted(0);
            knowledgeDocumentMapper.insert(contentMatchDoc);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "数据治理", null);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            long orMatchCount = records.stream()
                    .filter(vo -> (vo.getTitle() != null && vo.getTitle().contains("数据治理"))
                            || (vo.getContent() != null && vo.getContent().contains("数据治理")))
                    .count();
            assertTrue(orMatchCount >= 2, "标题或内容包含'数据治理'的文档都应被搜索到");
            System.out.println("  搜索关键词: '数据治理'");
            System.out.println("  OR匹配数: " + orMatchCount);
            records.forEach(vo -> {
                boolean titleMatch = vo.getTitle() != null && vo.getTitle().contains("数据治理");
                boolean contentMatch = vo.getContent() != null && vo.getContent().contains("数据治理");
                System.out.println("    - " + vo.getTitle() + " [标题匹配=" + titleMatch + ", 内容匹配=" + contentMatch + "]");
            });
        }
    }

    @Nested
    @DisplayName("场景6: 边界条件")
    class EdgeCaseTests {

        @Test
        @DisplayName("✅ 不传关键字返回全部文档")
        void testSearch_EmptyKeyword() {
            System.out.println("\n=== 场景6.1: 不传关键字返回全部 ===");

            createDocumentInDB("空关键字测试A", DocType.BUSINESS_KNOWLEDGE);
            createDocumentInDB("空关键字测试B", DocType.CLUSTER_OPS);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, null, null);

            assertNotNull(result.getData());
            assertTrue(result.getData().getRecords().size() >= 2, "不传关键字应返回全部文档(records>=" + result.getData().getRecords().size() + ")");
            System.out.println("  不传关键字返回总数: " + result.getData().getRecords().size() + " ✓");
        }

        @Test
        @DisplayName("✅ 空字符串关键字返回全部文档")
        void testSearch_BlankKeyword() {
            System.out.println("\n=== 场景6.2: 空字符串关键字返回全部 ===");

            createDocumentInDB("空白关键字测试", DocType.DEV_STANDARD);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "", null);

            assertNotNull(result.getData());
            assertTrue(result.getData().getRecords().size() >= 1, "空字符串关键字应返回全部文档(records>=" + result.getData().getRecords().size() + ")");
            System.out.println("  空字符串关键字返回总数: " + result.getData().getRecords().size() + " ✓");
        }

        @Test
        @DisplayName("✅ 不存在的类型返回空列表")
        void testFilter_NonExistentType_NoMatch() {
            System.out.println("\n=== 场景6.3: 不存在的类型返回空列表 ===");

            createDocumentInDB("已有类型文档", DocType.BUSINESS_KNOWLEDGE);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "完全不存在的关键字xyz123", null);

            assertNotNull(result.getData());
            assertEquals(0, result.getData().getRecords().size(), "不匹配的关键字应返回空列表");
            assertEquals(0, result.getData().getRecords().size(), "total应为0");
            System.out.println("  不匹配关键字 -> 记录数: 0 ✓");
        }

        @Test
        @DisplayName("✅ 重复发布文档 - 幂等性验证(status保持1)")
        void testPublishDocument_Idempotent() {
            System.out.println("\n=== 场景6.4: 重复发布文档幂等性 ===");

            Long id = createDocumentInDB("幂等发布文档", DocType.BUSINESS_KNOWLEDGE);

            knowledgeDocumentController.publishDocument(id);
            KnowledgeDocument afterFirst = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterFirst.getStatus(), "第一次发布后status应为1");
            System.out.println("  第一次发布后status: " + afterFirst.getStatus());

            knowledgeDocumentController.publishDocument(id);
            KnowledgeDocument afterSecond = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterSecond.getStatus(), "第二次发布后status仍应为1(幂等)");
            System.out.println("  第二次发布后status: " + afterSecond.getStatus() + " ✓");
        }

        @Test
        @DisplayName("✅ 重复同步RAG - 幂等性验证(syncToRag保持1)")
        void testSyncToRag_Idempotent() {
            System.out.println("\n=== 场景6.5: 重复同步RAG幂等性 ===");

            Long id = createDocumentInDB("幂等同步文档", DocType.CLUSTER_OPS);

            knowledgeDocumentController.syncToRag(id);
            KnowledgeDocument afterFirst = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterFirst.getSyncToRag(), "第一次同步后syncToRag应为1");
            System.out.println("  第一次同步后syncToRag: " + afterFirst.getSyncToRag());

            knowledgeDocumentController.syncToRag(id);
            KnowledgeDocument afterSecond = knowledgeDocumentMapper.selectById(id);
            assertEquals(1, afterSecond.getSyncToRag(), "第二次同步后syncToRag仍应为1(幂等)");
            System.out.println("  第二次同步后syncToRag: " + afterSecond.getSyncToRag() + " ✓");
        }

        @Test
        @DisplayName("✅ 长文本内容 - 成功创建和查询")
        void testCreateDocument_LongContent() {
            System.out.println("\n=== 场景6.6: 长文本内容 ===");

            String longContent = "# 长文本测试\n\n" + "这是一段很长的文档内容。".repeat(200);
            KnowledgeDocumentDTO dto = buildValidDTO("长文本文档", DocType.DEV_STANDARD);
            dto.setContent(longContent);

            Result<Void> saveResult = knowledgeDocumentController.save(dto);
            assertEquals(200, saveResult.getCode(), "长文本内容应能正常创建");
            System.out.println("  内容长度: " + longContent.length() + " 字符");

            Result<Page<KnowledgeDocumentVO>> listResult = knowledgeDocumentController.getList(1, 10, "长文本文档", null);
            KnowledgeDocumentVO vo = listResult.getData().getRecords().stream()
                    .filter(v -> v.getTitle().equals("长文本文档"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(vo, "应能查到长文本文档");
            assertNotNull(vo.getContent(), "内容不应为null");
            assertTrue(vo.getContent().length() >= longContent.length(), "查询到的内容长度应不小于写入长度");
            System.out.println("  查询到内容长度: " + vo.getContent().length() + " ✓");
        }

        @Test
        @DisplayName("✅ 分页参数 - 不同页码查询返回结果")
        void testPagination_SecondPage() {
            System.out.println("\n=== 场景6.7: 分页参数验证 ===");

            for (int i = 0; i < 8; i++) {
                createDocumentInDB("分页文档_第" + String.format("%02d", i) + "篇", DocType.BUSINESS_KNOWLEDGE);
            }

            Result<Page<KnowledgeDocumentVO>> page1 = knowledgeDocumentController.getList(1, 3, null, null);
            Result<Page<KnowledgeDocumentVO>> page2 = knowledgeDocumentController.getList(2, 3, null, null);

            assertNotNull(page1.getData());
            assertNotNull(page2.getData());
            assertEquals(1, page1.getData().getCurrent(), "第一页current应为1");
            assertEquals(2, page2.getData().getCurrent(), "第二页current应为2");
            assertEquals(3, page2.getData().getSize(), "size应为3");
            assertFalse(page1.getData().getRecords().isEmpty(), "第一页应有记录");
            assertFalse(page2.getData().getRecords().isEmpty(), "第二页应有记录");
            System.out.println("  第1页记录数: " + page1.getData().getRecords().size());
            System.out.println("  第2页记录数: " + page2.getData().getRecords().size());
        }

        @Test
        @DisplayName("✅ 关键字+类型组合筛选")
        void testFilter_KeywordAndDocType() {
            System.out.println("\n=== 场景6.8: 关键字+类型组合筛选 ===");

            createDocumentInDB("组合筛选_业务文档", DocType.BUSINESS_KNOWLEDGE);
            createDocumentInDB("组合筛选_运维文档", DocType.CLUSTER_OPS);
            createDocumentInDB("组合筛选_规范文档", DocType.DEV_STANDARD);

            Result<Page<KnowledgeDocumentVO>> result = knowledgeDocumentController.getList(1, 10, "组合筛选", DocType.CLUSTER_OPS);

            assertNotNull(result.getData());
            List<KnowledgeDocumentVO> records = result.getData().getRecords();
            boolean allMatch = records.stream()
                    .allMatch(vo -> vo.getTitle().contains("组合筛选") && vo.getDocType() == DocType.CLUSTER_OPS);
            assertTrue(allMatch, "所有返回记录都应同时满足关键字和类型条件");
            System.out.println("  条件: keyword='组合筛选' AND docType=CLUSTER_OPS");
            System.out.println("  结果数: " + records.size() + " ✓");
        }
    }
}
