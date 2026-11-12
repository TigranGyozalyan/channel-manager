package com.tgyozalyan.channelmanager.web.filter;

import io.elsci.web.OperationTimer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * In browser DevTools->Network Panel when clicking on a request it's possible to see Timing. We can add our own
 * timing there too, so that it's possible to learn how much the app or SQL is taking compared to the whole request.
 * <p>
 * Potentially this leaks some info that allows attackers to easier learn which params/body take longer to process,
 * so maybe we'll disable this with a system variable at some point.
 * </p>
 * See <href a="https://developer.mozilla.org/en-US/docs/Web/API/Performance_API/Server_timing">Server-Timing HTTP Header</href>
 */
@SuppressWarnings("NullableProblems")
@ControllerAdvice
public class TimingResponseHeaderAddingAdvice implements ResponseBodyAdvice<Object>, HandlerInterceptor {

    private final ThreadLocal<Long> START_TIME = new ThreadLocal<>();
    private OperationTimer timer;

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        START_TIME.set(System.nanoTime());
        return true;
    }

    public void setTimer(OperationTimer timer) {
        this.timer = timer;
    }

    /**
     * beforeBodyWrite() is never called by Spring when the response body is null (e.g. 204 No Content) -
     * in that case we set it here
     */
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        if (START_TIME.get() != null) {
            addAppAndSpringTimings();
            if (!timer.isEmpty())
                response.setHeader("Server-Timing", timer.timingHeader());
        }
        START_TIME.remove();
    }

    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType
            , Class<? extends HttpMessageConverter<?>> selectedConverterType
            , ServerHttpRequest request, ServerHttpResponse response) {
        addAppAndSpringTimings();
        if (!timer.isEmpty())
            response.getHeaders().add("Server-Timing", timer.timingHeader());
        // since some requests aren't passing through preHandle() we don't want to accidentally use the value
        // left by the previous request - hence removing the value explicitly
        START_TIME.remove();
        return body;
    }

    public boolean supports(MethodParameter mp, Class<? extends HttpMessageConverter<?>> ct) {
        return true;
    }

    private void addAppAndSpringTimings() {
        long now = System.nanoTime();
        Long start = START_TIME.get();
        if (start != null)
            timer.add("app", start, now);
        // START_TIME in the current file is set way after Spring's own dispatch overhead, so this listener
        // (registered directly with the servlet container) lets us see how much that overhead is. We can't
        // add a metric that includes Spring's after-processing too, since HTTP headers need to be written
        // before the body.
        Long springStart = RequestTimingListener.START_TIME.get();
        if (springStart != null)
            timer.add("wsprng", springStart, now);
    }
}
