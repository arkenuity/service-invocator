package com.github.arkenuity.service.essentials;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author <a href="mailto:arkenuity@gmail.com">Rajesh Kumar Arcot</a>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Instrumented {

    boolean timed() default true;

    boolean logged() default true;

    boolean count() default true;

    Class<?> clazz();

    String method();

}
