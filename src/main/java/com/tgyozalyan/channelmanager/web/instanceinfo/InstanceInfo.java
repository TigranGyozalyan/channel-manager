package com.tgyozalyan.channelmanager.web.instanceinfo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class InstanceInfo {
    @SuppressWarnings("unused"/*used in the endpoint*/)
    public final String version, commit;
    /**
     * @see ActiveInstanceChecker
     */
    public final String instanceId;

    public InstanceInfo(String version, String commit) {
        this.version = version;
        this.commit = commit;
        this.instanceId = UUID.randomUUID().toString();
    }

    /**
     * Used only when deserializing from JSON in {@link ActiveInstanceChecker}.
     */
    private InstanceInfo(@JsonProperty("version") String version,
                         @JsonProperty("commit") String commit,
                         @JsonProperty("instanceId") String instanceId) {
        this.version = version;
        this.commit = commit;
        this.instanceId = instanceId;
    }

    public boolean isSameInstance(InstanceInfo instanceInfo) {
        return this.instanceId.equals(instanceInfo.instanceId);
    }
}