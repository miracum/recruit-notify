package org.miracum.recruit.notify.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@ConditionalOnProperty(value = "opentracing.jaeger.enabled", havingValue = "false")
@Configuration
public class TracerConfig {

  @Bean
  public io.opentracing.Tracer jaegerTracer() {
    return io.opentracing.noop.NoopTracerFactory.create();
  }
}
