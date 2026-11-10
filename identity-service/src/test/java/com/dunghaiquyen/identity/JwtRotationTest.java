package com.dunghaiquyen.identity;

import static org.assertj.core.api.Assertions.assertThat;
import com.dunghaiquyen.identity.config.IdentityProperties;
import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import com.dunghaiquyen.identity.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtRotationTest {
    @Test void newlyRotatedKeyStillAcceptsTokenFromPreviousKey() {
        String oldAccess="old-access-secret-at-least-thirty-two-bytes-long",oldRefresh="old-refresh-secret-at-least-thirty-two-bytes-long";
        JwtService old=new JwtService(new IdentityProperties.Jwt(oldAccess,oldRefresh,"","",15,30)); UUID id=UUID.randomUUID();
        String access=old.access(id,Role.CUSTOMER),refresh=old.refresh(id);
        JwtService rotated=new JwtService(new IdentityProperties.Jwt("new-access-secret-at-least-thirty-two-bytes-long","new-refresh-secret-at-least-thirty-two-bytes-long",oldAccess,oldRefresh,15,30));
        assertThat(rotated.parseAccess(access)).get().extracting(JwtService.Principal::userId).isEqualTo(id);
        assertThat(rotated.parseRefresh(refresh)).contains(id);
    }
}
