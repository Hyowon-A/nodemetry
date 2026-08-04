package com.nodemetry.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpApiProperties {

    private final boolean readOnly;

    public HttpApiProperties(@Value("${app.http-api.read-only:false}") boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
