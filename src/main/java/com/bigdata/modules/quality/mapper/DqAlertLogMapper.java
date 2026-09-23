package com.bigdata.modules.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bigdata.modules.quality.entity.DqAlertLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * DqAlertLog Mapper。
 *
 * <p>刻意只继承 BaseMapper、不声明自定义方法：全部走 MyBatis-Plus 生成的
 * 可移植 SQL，从而不需要为 MySQL / PostgreSQL / MariaDB / H2 各维护一份 XML。</p>
 *
 * @author dataforge
 */
@Mapper
public interface DqAlertLogMapper extends BaseMapper<DqAlertLog> {
}
