package com.dunghaiquyen.ecommerce.modules.auth.repository;

import com.dunghaiquyen.ecommerce.modules.auth.entity.IdentityProvider;
import com.dunghaiquyen.ecommerce.modules.auth.entity.UserIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndProviderSubject(IdentityProvider provider, String providerSubject);

    Optional<UserIdentity> findByUserIdAndProvider(UUID userId, IdentityProvider provider);
}
