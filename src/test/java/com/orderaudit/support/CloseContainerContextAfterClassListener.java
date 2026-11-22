package com.orderaudit.support;

import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Closes the Spring context of a {@link Testcontainers} test class as soon as that class finishes.
 *
 * Every integration test class has its own static PostgreSQL container, so its Spring context can never be reused
 * by another class. Without this listener Spring keeps up to 32 such contexts cached until the JVM exits. By then
 * their containers are gone, and shutting down each connection pool against a dead database takes seconds. Surefire
 * then waits 30 seconds and kills the test JVM ("Surefire is going to kill self fork JVM").
 *
 * Closing here is safe: Testcontainers stops a static container only after Spring's after-class callbacks, so the
 * database is still running while the context shuts down. Same effect as {@code @DirtiesContext} on each class.
 *
 * Registered in src/test/resources/META-INF/spring.factories.
 */
public class CloseContainerContextAfterClassListener implements TestExecutionListener, Ordered {

    @Override
    public void afterTestClass(TestContext testContext) {
        if (testContext.hasApplicationContext()
                && testContext.getTestClass().isAnnotationPresent(Testcontainers.class)) {
            testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
