package com.github.arkenuity.service.essentials;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author <a href="mailto:arkenuity@gmail.com">Rajesh Kumar Arcot</a>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Conform {

    int retryCount() default 0;

    long maxWaitTime() default 0;

    TimeUnit maxWaitTimeUnit() default TimeUnit.MILLISECONDS;
}

