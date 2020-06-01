package io.activej.di;

import io.activej.di.annotation.ScopeAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@ScopeAnnotation(threadsafe = false)
@Target({ElementType.METHOD})
@Retention(RUNTIME)
public @interface OrderScope {
}
