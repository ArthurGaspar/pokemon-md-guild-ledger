package dev.arthurgaspar.expeditions.platform.tracing;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
        "org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration",
        "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration"})
public class TracingAutoConfiguration {

    @Bean
    @ConditionalOnBean({Tracer.class, Propagator.class})
    TraceContext traceContext(Tracer tracer, Propagator propagator) {
        return new TraceContext(tracer, propagator);
    }

    /** Connects the OTEL appender in logback-spring.xml to the SDK, so logs go to Loki with their trace ids. */
    @Bean
    @ConditionalOnClass(OpenTelemetryAppender.class)
    @ConditionalOnBean(OpenTelemetry.class)
    InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
