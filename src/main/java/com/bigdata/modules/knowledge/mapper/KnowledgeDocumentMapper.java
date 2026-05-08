package com.bigdata.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
