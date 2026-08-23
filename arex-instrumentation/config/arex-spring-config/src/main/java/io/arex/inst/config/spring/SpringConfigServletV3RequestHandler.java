package io.arex.inst.config.spring;

import com.google.auto.service.AutoService;
import io.arex.inst.runtime.model.ArexConstants;
import io.arex.inst.runtime.request.RequestHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Flushes Part A's per-request dynamic-read buffer (see SpringConfigExtractor#flushRecordBuffer)
 * at request exit. RequestHandlers are discovered unconditionally via ServiceLoader regardless
 * of the arex.spring.config flag - flushRecordBuffer() itself checks SpringConfigChecker first,
 * so this stays a true no-op when the feature is off, same as ApolloServletV3RequestHandler's
 * own pattern.
 */
@AutoService(RequestHandler.class)
public class SpringConfigServletV3RequestHandler implements RequestHandler<HttpServletRequest, HttpServletResponse> {

    @Override
    public String name() {
        return ArexConstants.SERVLET_V3;
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
