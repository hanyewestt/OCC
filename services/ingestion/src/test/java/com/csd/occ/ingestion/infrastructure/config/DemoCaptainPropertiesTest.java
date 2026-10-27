package com.csd.occ.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DemoCaptainPropertiesTest {
    @Test
    void acceptsAnUnassignedDemoButRejectsEmailAsIdentity() {
        assertThat(new DemoCaptainProperties("").subject()).isEmpty();
        assertThatThrownBy(() -> new DemoCaptainProperties("captain1@occ.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DemoCaptainProperties("00000000-0000-0000-0000-000000000001").subject()).isPresent();
    }
}
