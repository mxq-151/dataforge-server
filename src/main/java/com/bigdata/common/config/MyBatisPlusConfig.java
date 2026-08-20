package com.bigdata.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Properties;

@Component
public class MyBatisPlusConfig implements MetaObjectHandler {

    /**
     * MyBatis-Plus 分页插件
     * <p>
     * 注册 PaginationInnerInterceptor 后，Mapper 的 selectPage 方法才会真正生效分页。
     * 使用无参构造，MyBatis-Plus 会根据当前数据源连接自动探测方言（MySQL/MariaDB/PostgreSQL），
     * 从而生成对应数据库的 LIMIT 分页语句，实现多数据库适配。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 自动探测数据源方言，适配 mysql/mariadb/pg
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }

    /**
     * MyBatis 多数据库方言 Provider。
     * <p>
     * 通过识别 JDBC 驱动的 DatabaseProductName，将当前运行库映射为 databaseId，
     * MyBatis 会据此选择带对应 databaseId 的 XML 语句（如 mapper/mysql 下的 ON DUPLICATE KEY UPDATE），
     * 无 databaseId 的通用语句（mapper/common）在所有数据库上生效。
     */
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("MySQL", "mysql");
        properties.setProperty("MariaDB", "mariadb");
        properties.setProperty("PostgreSQL", "postgresql");
        properties.setProperty("H2", "h2");
        provider.setProperties(properties);
        return provider;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
