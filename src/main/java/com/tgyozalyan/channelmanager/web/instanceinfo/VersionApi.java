package com.tgyozalyan.channelmanager.web.instanceinfo;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE, value = "/version")
class VersionApi {
    private final InstanceInfo instanceInfo;

    VersionApi(InstanceInfo instanceInfo) {
        this.instanceInfo = instanceInfo;
    }

    @GetMapping
    InstanceInfo version() {
        return instanceInfo;
    }
}
