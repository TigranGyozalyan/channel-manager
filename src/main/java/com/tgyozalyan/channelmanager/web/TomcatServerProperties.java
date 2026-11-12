package com.tgyozalyan.channelmanager.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.tgyozalyan.channelmanager.web.PropertyReader.readProperty;

public class TomcatServerProperties {
    private static final Logger LOG = LoggerFactory.getLogger(TomcatServerProperties.class);
    final static boolean DAEMON = true;
    final static int THREAD_PRIORITY = Thread.NORM_PRIORITY;
    private int port;
    private int minThreads;
    private int maxThreads;
    private int sessionTimeoutMin;
    private String contextPath;
    private String baseDirPath;
    private String multipartLocation;

    /**
     * Spring doesn't pickup {@code spring.servlet.multipart.max-file-size}. Seems like when registering the servlet
     * container manually, we need to manually set the multipart properties, it is not possible to do it at the bean level
     * For more details, see {@link org.springframework.web.multipart.support.StandardServletMultipartResolver}
     *
     * Things like {@code spring.servlet.multipart.max-file-size} and {@code spring.servlet.multipart.max-request-size}
     * don't have any effect for multipart uploads,
     * seems like Spring boot picks them up and does the work mentioned above, so in our case they aren't useful.
     */
    private int multipartMaxFileSize;
    private int multipartMaxRequestSize;

    /**
     * Threshold after which file is stored in {@link TomcatServerProperties#multipartLocation} instead of in memory.
     */
    private int multipartFileThreshold;

    private TomcatServerProperties() { }

    public int getPort() { return port; }
    int getMinThreads() { return minThreads; }
    int getMaxThreads() { return maxThreads; }
    String getContextPath() { return contextPath; }
    String getBaseDirPath() { return baseDirPath; }
    String getThreadPrefix(){ return String.format("http-%s-", getPort()); }
    int getSessionTimeoutMin() { return sessionTimeoutMin; }
    String getMultipartLocation() { return multipartLocation; }
    int getMultipartMaxFileSize() { return multipartMaxFileSize; }
    int getMultipartMaxRequestSize() { return multipartMaxRequestSize; }
    int getMultipartFileThreshold() { return multipartFileThreshold; }

    public TomcatServerProperties setPort(int port) { this.port = port; return this;}
    private TomcatServerProperties setBaseDirPath(String baseDirPath) { this.baseDirPath = baseDirPath; return this;}
    private TomcatServerProperties setMinThreads(int minThreads) { this.minThreads = minThreads; return this; }
    private TomcatServerProperties setMaxThreads(int maxThreads) { this.maxThreads = maxThreads; return this; }
    private TomcatServerProperties setSessionTimeoutMin(int sessionTimeoutMin) { this.sessionTimeoutMin = sessionTimeoutMin; return this; }
    private TomcatServerProperties setMultipartLocation(String location) { this.multipartLocation = location; return this; }
    private TomcatServerProperties setMultipartMaxFileSize(int maxFile) { this.multipartMaxFileSize = maxFile; return this; }
    private TomcatServerProperties setMultipartMaxRequestSize(int maxRequest) { this.multipartMaxRequestSize = maxRequest; return this; }
    private TomcatServerProperties setMultipartFileThreshold(int fileThreshold) { this.multipartFileThreshold = fileThreshold; return this; }

    private TomcatServerProperties setContextPath(String contextPath) { this.contextPath = contextPath; return this; }
    static TomcatServerProperties fromSystemProperties() {
        String baseDirPathProp = "server.baseDirPath",
               tmpDirProp = "java.io.tmpdir";
        String tmpDir = System.getProperty(tmpDirProp);
        String baseDir = readProperty(baseDirPathProp).stringValueOr(tmpDir);
        LOG.info("Tomcat base dir: {}. To override set {} or {}", baseDir, baseDirPathProp, tmpDirProp);

        String minThreadsProp = "server.minThreads",
               maxThreadsProp = "server.maxThreads";
        int minThreads = readProperty(minThreadsProp).intValueOr(Runtime.getRuntime().availableProcessors());
        // There's a lot of UI (when working with DB), so setting thread pool size to CPUs * 3. Maybe it could
        // be higher, we haven't tested.
        int maxThreads = readProperty(maxThreadsProp).intValueOr(Runtime.getRuntime().availableProcessors()*3);
        LOG.info("Thread pool min={}, max={}. To override set {} and {}", minThreads, maxThreads, minThreadsProp, maxThreadsProp);

        // DB Pool Size depends on the Thread Pool size since each thread usually takes up only one DB connection.
        if(System.getProperty("db.pool.max_connections") == null)
            // +4 for Scheduled and Async code that can acess DB too
            System.setProperty("db.pool.max_connections", (maxThreads+4) + "");

        String sessionTimeoutProp = "server.servlet.session.timeout_min";
        int sessionTimeoutMin = readProperty(sessionTimeoutProp).intValueOr(30);
        LOG.info("Session timeout: {}. To override set {}", sessionTimeoutMin, sessionTimeoutProp);

        String multipartLocationProp = "multipart.upload.location";
        String multipartMaxRequestProp = "multipart.upload.max_request_size";
        String multipartMaxFileProp = "multipart.upload.max_file_size";
        String multipartFileThresholdProp = "multipart.upload.file_threshold_size";

        String multipartLocation = readProperty(multipartLocationProp).stringValueOr(tmpDir);
        int multipartMaxRequest = readProperty(multipartMaxRequestProp).intValueOr(20 * 1000 * 1000);
        int multipartMaxFile = readProperty(multipartMaxFileProp).intValueOr(15 * 1000 * 1000);
        int multipartFileThreshold = readProperty(multipartFileThresholdProp).intValueOr(1000 * 1000);

        LOG.info("Multipart request config: location {}, max request size: {}, single file max size: {}, file threshold size: {}. To override set '{}', '{}', '{}', '{}'",
                multipartLocation, multipartMaxRequest, multipartMaxFile, multipartFileThreshold,
                multipartLocationProp, multipartMaxRequestProp, multipartMaxFileProp, multipartFileThresholdProp
        );

        return new TomcatServerProperties()
                .setPort(readProperty("server.port").intValueOr(8080))
                .setBaseDirPath(baseDir)
                .setMinThreads(minThreads)
                .setMaxThreads(maxThreads)
                .setContextPath(readProperty("server.contextPath").stringValueOr(""))
                .setSessionTimeoutMin(sessionTimeoutMin)
                .setMultipartLocation(multipartLocation)
                .setMultipartMaxRequestSize(multipartMaxRequest)
                .setMultipartMaxFileSize(multipartMaxFile)
                .setMultipartFileThreshold(multipartFileThreshold);
    }
}
