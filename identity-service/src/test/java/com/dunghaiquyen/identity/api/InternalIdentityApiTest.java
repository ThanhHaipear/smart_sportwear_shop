package com.dunghaiquyen.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.identity.domain.IdentityModels.Role;
import com.dunghaiquyen.identity.domain.IdentityModels.Status;
import com.dunghaiquyen.identity.domain.IdentityModels.User;
import com.dunghaiquyen.identity.persistence.IdentityRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalIdentityApiTest {
    @Test
    void returnsAServiceSafeUserSummary() {
        IdentityRepository repository = mock(IdentityRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.userById(id)).thenReturn(Optional.of(new User(id, "Khách hàng", "customer@example.com", "0900000000", "secret-hash", Role.CUSTOMER, Status.ACTIVE, "LOCAL", 1)));
        InternalIdentityApi api = new InternalIdentityApi(repository, "sales-token");

        InternalIdentityApi.UserSummary summary = api.user("sales-token", id);

        assertThat(summary).isEqualTo(new InternalIdentityApi.UserSummary(id, "Khách hàng", "customer@example.com", "0900000000"));
    }

    @Test
    void rejectsAnInvalidServiceToken() {
        InternalIdentityApi api = new InternalIdentityApi(mock(IdentityRepository.class), "sales-token");
        assertThatThrownBy(() -> api.user("wrong-token", UUID.randomUUID())).isInstanceOf(SecurityException.class);
    }
}
