package com.dunghaiquyen.ecommerce.modules.interaction.repository;

import com.dunghaiquyen.ecommerce.modules.interaction.entity.UserInteraction;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInteractionRepository extends JpaRepository<UserInteraction, UUID> {
    long deleteByOccurredAtBefore(Instant cutoff);
}
