package com.csd.occ.gateway.api;

import com.csd.occ.gateway.domain.FrontendConfig;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FrontendConfigController {
    private final FrontendConfig config;

    public FrontendConfigController(FrontendConfig config) {
        this.config = config;
    }

    @GetMapping("/frontend-config")
    public ResponseEntity<FrontendConfig> frontendConfig() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(config);
    }
}
