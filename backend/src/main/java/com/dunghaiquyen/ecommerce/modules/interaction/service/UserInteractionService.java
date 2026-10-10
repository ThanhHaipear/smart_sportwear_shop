package com.dunghaiquyen.ecommerce.modules.interaction.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.modules.interaction.entity.InteractionEventType;
import com.dunghaiquyen.ecommerce.modules.interaction.entity.UserInteraction;
import com.dunghaiquyen.ecommerce.modules.interaction.repository.UserInteractionRepository;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductDataSource;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductVariant;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserInteractionService {

    private final UserInteractionRepository repository;

    public UserInteractionService(UserInteractionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UUID append(
            User user,
            String anonymousId,
            InteractionEventType eventType,
            Product product,
            ProductVariant variant,
            UUID requestId,
            String source,
            ProductDataSource dataSource,
            Map<String, Object> metadata) {
        if (user == null && (anonymousId == null || anonymousId.isBlank())) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Interaction identity is required");
        }
        if (variant != null && (product == null || !variant.getProduct().getId().equals(product.getId()))) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Variant does not belong to product");
        }
        UserInteraction event = new UserInteraction();
        event.setUser(user);
        event.setAnonymousId(anonymousId == null ? null : anonymousId.trim());
        event.setEventType(eventType);
        event.setProduct(product);
        event.setVariant(variant);
        event.setRequestId(requestId);
        event.setSource(source);
        event.setDataSource(dataSource);
        event.setMetadata(metadata);
        return repository.save(event).getEventId();
    }
}
