package com.tgyozalyan.channelmanager.web.instanceinfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestOperations;

import java.util.Map;

public class ActiveInstanceChecker {
    private final static Map<Boolean, String> STATE_STR = Map.of(true, "active", false, "inactive");
    private final InstanceInfo currentInstanceInfo;
    private final RestOperations restOps;
    private static final Logger LOGGER = LoggerFactory.getLogger(ActiveInstanceChecker.class);
    private final String selfUrl;
    private volatile boolean wasActivePreviously;

    public ActiveInstanceChecker(InstanceInfo currentInstanceInfo, RestOperations restOps, String selfUrl) {
        this.currentInstanceInfo = currentInstanceInfo;
        this.restOps = restOps;
        this.selfUrl = selfUrl;
    }

    /**
     * Check if the instance that currently responds on the {@code {our url}/version} is this instance. Because
     * we use Blue-Green deployment, it's possible that another instance is currently active. We don't want
     * background jobs to run in the non-active instance, and thus in those jobs need to check if we're active.
     *
     * @return whether currently deployed instance is the active one or a stand-by/previously deployed version
     */
    public boolean isInstanceActive() {
        InstanceInfo remoteInstanceInfo = restOps.getForObject(selfUrl, InstanceInfo.class);
        if (remoteInstanceInfo == null)
            throw new IllegalStateException("Requesting active instance info (/version) returned null");
        boolean active = currentInstanceInfo.isSameInstance(remoteInstanceInfo);
        if(active != wasActivePreviously)
            LOGGER.info("\n\n\n\t\tTHE INSTANCE WAS {}, AND NOW IT'S {}!!!\n\n", STATE_STR.get(wasActivePreviously), STATE_STR.get(active));
        LOGGER.debug("The instance is {}", STATE_STR.get(active));
        wasActivePreviously = active;
        return active;
    }
}
