package net.chrisrichardson.ftgo.orderservice.web;



import brave.Span;
import brave.Tracer;
import org.springframework.cloud.sleuth.instrument.web.TraceWebServletAutoConfiguration;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Order(TraceWebServletAutoConfiguration.TRACING_FILTER_ORDER + 1)
class TraceIdResponseFilter extends GenericFilterBean {

  private final Tracer tracer;

  public TraceIdResponseFilter(Tracer tracer) {
    this.tracer = tracer;
  }

  @Override public void doFilter(ServletRequest request, ServletResponse response,
                                 FilterChain chain) throws IOException, ServletException {
    Span currentSpan = this.tracer.currentSpan();
    if (currentSpan != null) {
      ((HttpServletResponse) response)
              .addHeader("ZIPKIN-TRACE-ID",
                      currentSpan.context().traceIdString());
    }
    chain.doFilter(request, response);
  }
}