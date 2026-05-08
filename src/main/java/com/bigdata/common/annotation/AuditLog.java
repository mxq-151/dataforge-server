package com.bigdata.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    String module() default "";

    String operation() default "";

    String targetType() default "";
}
