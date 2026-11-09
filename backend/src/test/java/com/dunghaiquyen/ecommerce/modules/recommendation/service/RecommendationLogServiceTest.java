package com.dunghaiquyen.ecommerce.modules.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.modules.cart.entity.Cart;
import com.dunghaiquyen.ecommerce.modules.cart.api.CartReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.cart.web.CartOwner;
import com.dunghaiquyen.ecommerce.modules.product.entity.Product;
import com.dunghaiquyen.ecommerce.modules.product.entity.ProductStatus;
import com.dunghaiquyen.ecommerce.modules.product.api.ProductReferenceQuery;
import com.dunghaiquyen.ecommerce.modules.recommendation.dto.RecommendationLogRequest;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.AssociationRule;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.RecommendationAlgorithm;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.RecommendationEventType;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.RecommendationLog;
import com.dunghaiquyen.ecommerce.modules.recommendation.entity.RecommendationSourceType;
import com.dunghaiquyen.ecommerce.modules.recommendation.repository.AssociationRuleRepository;
import com.dunghaiquyen.ecommerce.modules.recommendation.repository.RecommendationLogRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationLogServiceTest {

    @Mock
    private RecommendationLogRepository logRepository;
    @Mock
    private ProductReferenceQuery productReferenceQuery;
    @Mock
    private AssociationRuleRepository ruleRepository;
    @Mock
    private CartReferenceQuery cartReferenceQuery;
    @Mock
    private EntityManager entityManager;

    private RecommendationLogService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationLogService(
                logRepository,
                productReferenceQuery,
                ruleRepository,
                cartReferenceQuery,
                entityManager
        );
    }

    @Test
    void productDetailEvent_derivesAlgorithmAndScoresFromServerRule() {
        UUID userId = UUID.randomUUID();
        Product source = activeProduct();
        Product recommended = activeProduct();
        AssociationRule rule = new AssociationRule();
        rule.setSupport(0.25);
        rule.setConfidence(0.75);
        rule.setLift(1.4);
        rule.setPairCount(9);

        when(productReferenceQuery.findProduct(recommended.getId())).thenReturn(Optional.of(recommended));
        when(productReferenceQuery.findProduct(source.getId())).thenReturn(Optional.of(source));
        when(ruleRepository.findBestActiveRule(eq(List.of(source.getId())), eq(recommended.getId()), any()))
                .thenReturn(List.of(rule));
        when(entityManager.getReference(User.class, userId)).thenReturn(new User());
        when(logRepository.saveAndFlush(any(RecommendationLog.class))).thenAnswer(invocation -> {
            RecommendationLog log = invocation.getArgument(0);
            log.setId(UUID.randomUUID());
            log.setCreatedAt(Instant.now());
            return log;
        });

        service.logEvent(
                new CartOwner(userId, null),
                new RecommendationLogRequest(
                        RecommendationEventType.CLICK,
                        RecommendationSourceType.PRODUCT_DETAIL,
                        source.getId(),
                        null,
                        recommended.getId(),
                        1
                )
        );

        ArgumentCaptor<RecommendationLog> captor = ArgumentCaptor.forClass(RecommendationLog.class);
        verify(logRepository).saveAndFlush(captor.capture());
        RecommendationLog saved = captor.getValue();
        assertThat(saved.getAlgorithm()).isEqualTo(RecommendationAlgorithm.ASSOCIATION_RULE);
        assertThat(saved.getSupport()).isEqualTo(0.25);
        assertThat(saved.getConfidence()).isEqualTo(0.75);
        assertThat(saved.getLift()).isEqualTo(1.4);
        assertThat(saved.getPairCount()).isEqualTo(9);
    }

    @Test
    void cartEvent_rejectsCartThatDoesNotBelongToCaller() {
        UUID userId = UUID.randomUUID();
        UUID requestedCartId = UUID.randomUUID();
        Product recommended = activeProduct();
        Cart anotherCart = new Cart();
        anotherCart.setId(UUID.randomUUID());

        when(productReferenceQuery.findProduct(recommended.getId())).thenReturn(Optional.of(recommended));
        when(cartReferenceQuery.findCart(any())).thenReturn(Optional.of(anotherCart));

        RecommendationLogRequest request = new RecommendationLogRequest(
                RecommendationEventType.IMPRESSION,
                RecommendationSourceType.CART,
                null,
                requestedCartId,
                recommended.getId(),
                1
        );

        assertThatThrownBy(() -> service.logEvent(new CartOwner(userId, null), request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Cart not found");
    }

    private Product activeProduct() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }
}
