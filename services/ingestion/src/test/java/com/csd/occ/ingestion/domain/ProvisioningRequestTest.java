package com.csd.occ.ingestion.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProvisioningRequestTest {
    @Test void normalizesSafeFieldsAndPreservesPasswordExactly() {
        String password = "  密碼😀abcdefghijk  ";
        var request = new ProvisioningRequest("  Captain  ", " USER@Example.Test ", OccRole.CAPTAIN, password);
        assertThat(request.displayName()).isEqualTo("Captain");
        assertThat(request.email()).isEqualTo("user@example.test");
        assertThat(request.password()).isEqualTo(password);
        assertThat(request.toString()).doesNotContain(password).contains("REDACTED");
    }
    @Test void acceptsUnicodeCodePointBoundariesWithoutCompositionRules() {
        assertThat(new ProvisioningRequest("Name", "a@b.test", OccRole.CONTROLLER, "😀".repeat(8)).password()).isEqualTo("😀".repeat(8));
        assertThat(new ProvisioningRequest("Name", "a@b.test", OccRole.CAPTAIN, " ".repeat(128)).password()).hasSize(128);
        for (String password : new String[]{"😀".repeat(7), "a".repeat(129)})
            assertThatIllegalArgumentException().isThrownBy(() -> new ProvisioningRequest("Name", "a@b.test", OccRole.CAPTAIN, password));
    }
    @Test void rejectsEscalationAndMalformedSafeFieldsWithGenericErrors() {
        for (OccRole role : new OccRole[]{OccRole.ADMIN, OccRole.UNASSIGNED})
            assertThatIllegalArgumentException().isThrownBy(() -> new ProvisioningRequest("Name", "a@b.test", role, "sensitive-password"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProvisioningRequest("  ", "a@b.test", OccRole.CAPTAIN, "sensitive-password"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProvisioningRequest("Name", "invalid", OccRole.CAPTAIN, "sensitive-password"));
    }
}
