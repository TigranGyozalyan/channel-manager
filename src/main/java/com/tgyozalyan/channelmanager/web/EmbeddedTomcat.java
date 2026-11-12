package com.tgyozalyan.channelmanager.web;

import com.tgyozalyan.channelmanager.web.filter.FileUploadFilter;
import jakarta.servlet.MultipartConfigElement;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.TaskThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.tgyozalyan.channelmanager.web.TomcatServerProperties.DAEMON;
import static com.tgyozalyan.channelmanager.web.TomcatServerProperties.THREAD_PRIORITY;
import static java.util.Collections.emptyMap;

public class EmbeddedTomcat {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedTomcat.class);
    private final Tomcat tomcat;
    private final Context context;

    public EmbeddedTomcat(String springConfigLocation) {
        this(springConfigLocation, TomcatServerProperties.fromSystemProperties());
    }

    public EmbeddedTomcat(String springConfigLocation, TomcatServerProperties properties) {
        tomcat = new Tomcat();
        tomcat.setBaseDir(properties.getBaseDirPath());
        context = tomcat.addContext(properties.getContextPath(), null);
        context.setSessionTimeout(properties.getSessionTimeoutMin());
        tomcat.setConnector(createConnector(properties));
        addSpringContext(springConfigLocation, properties);

        // Must be the first filter so it can catch Tomcat's multipart total-request-size violation
        // (raised inside the servlet container's parser — Spring's @ControllerAdvice doesn't see it).
        addFilter(FileUploadFilter.class, "fileUploadFilter", emptyMap());
        // This sets the Content-Type=...;charset=utf-8 and sets the HttpServletResponse charset. But SpringMVC
        // ignores it, at least for Controller methods that return String!
        // So for String responses (like in HtmlController) this must go together with StringHttpMessageConverter
        // using UTF-8. Otherwise, SpringMVC won't actually encode it in UTF-8 (see web-app-context.xml).
        addFilter(CharacterEncodingFilter.class, "encodingFilter", Map.of(
                "encoding", "UTF-8",
                "forceResponseEncoding", "true"
        ));
        addFilter(ForwardedHeaderFilter.class, "forwardedHeaderFilter", emptyMap());
        addFilter(CharacterEncodingFilter.class, "encodingFilter", Map.of("encoding", "UTF-8"));
        //IMPORTANT: order matters, needs to be before anything that uses session (e.g. springSecurityFilterChain)
//        addFilter(DelegatingFilterProxy.class, "springSessionRepositoryFilter"/*the name is important*/, emptyMap());
//        addFilter(DelegatingFilterProxy.class, "springSecurityFilterChain"/*the name is important*/, emptyMap());
    }
    private void addSwaggerServlet() {
        Context tomcatContext = this.context;
            Wrapper wrapper = tomcatContext.createWrapper();
            wrapper.setName("default");
            wrapper.setServletClass("org.apache.catalina.servlets.DefaultServlet");
            wrapper.addInitParameter("listings", "false");
            wrapper.setLoadOnStartup(1);
            tomcatContext.addChild(wrapper);
            tomcatContext.addServletMappingDecoded("/swagger-ui.html", "default");
    }

    public void startServer() {
        try {
            tomcat.start();
            tomcat.getServer().await();
        } catch (LifecycleException ex) {
            LOGGER.error("Can't start Tomcat", ex);
            throw new RuntimeException(ex);
        }
    }

    private void addSpringContext(String contextConfigLocation, TomcatServerProperties properties) {
        Context tomcatContext = this.context;
        Container springContext = tomcatContext.findChild("spring-dispatcher");
        if (springContext == null) {
            Wrapper spring = tomcatContext.createWrapper();
            spring.setName("spring-dispatcher");
            spring.setServletClass("org.springframework.web.servlet.DispatcherServlet");
            spring.setMultipartConfigElement(new MultipartConfigElement(
                properties.getMultipartLocation(),
                properties.getMultipartMaxFileSize(),
                properties.getMultipartMaxRequestSize(),
                properties.getMultipartFileThreshold()
            ));
            spring.addInitParameter("contextConfigLocation", contextConfigLocation);
            spring.setLoadOnStartup(1);
            tomcatContext.addChild(spring);
            tomcatContext.addServletMappingDecoded("/", "spring-dispatcher");
        }
    }


    private void addFilter(Class<?> filterClass, String filterName, Map<String, String> params) {
        FilterDef filterDef = new FilterDef();
        filterDef.setFilterClass(filterClass.getName());
        filterDef.setFilterName(filterName);
        for (Map.Entry<String, String> param : params.entrySet())
            filterDef.addInitParameter(param.getKey(), param.getValue());
        this.context.addFilterDef(filterDef);

        FilterMap filterMap = new FilterMap();
        filterMap.setFilterName(filterName);
        filterMap.addURLPattern("/*");// if we ever need something else, need to create a separate method w/ a param
        this.context.addFilterMap(filterMap);
    }
    private void addListener(Class<?> listenerClass) {
        this.context.addApplicationListener(listenerClass.getName());
    }
    private Connector createConnector(TomcatServerProperties properties) {
        Connector connector = new Connector();
        connector.setPort(properties.getPort());
        AbstractProtocol protocolHandler = (AbstractProtocol) connector.getProtocolHandler();
        protocolHandler.setExecutor(createExecutor(properties));
        return connector;
    }

    private Executor createExecutor(TomcatServerProperties properties) {
        TaskThreadFactory tf = new TaskThreadFactory(properties.getThreadPrefix(), DAEMON, THREAD_PRIORITY);
        return new ThreadPoolExecutor(properties.getMinThreads(), properties.getMaxThreads(),
                60, TimeUnit.SECONDS, new TaskQueue(), tf);
    }
}
