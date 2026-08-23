package io.arex.inst.config.spring;

import com.google.auto.service.AutoService;
import io.arex.inst.runtime.model.ArexConstants;
import io.arex.inst.runtime.request.RequestHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * jakarta.servlet (Servlet 5+ / Spring Boot 3+) counterpart of
 * SpringConfigServletV3RequestHandler - see there for the full rationale.
 */
@AutoService(RequestHandler.class)
public class SpringConfigServletV5RequestHandler implements RequestHandler<HttpServletRequest, HttpServletResponse> {

    @Override
    public String name() {
        return ArexConstants.SERVLET_V5;
    }

    @Override
    public void preHandle(HttpServletRequest request) {
        // no need implement
    }

    @Override
    public void handleAfterCreateContext(HttpServletRequest request) {
        // no need implement
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response) {
        SpringConfigExtractor.flushRecordBuffer();
    }
}
