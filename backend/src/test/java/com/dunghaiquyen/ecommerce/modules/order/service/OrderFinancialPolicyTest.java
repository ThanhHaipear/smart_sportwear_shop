package com.dunghaiquyen.ecommerce.modules.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import org.junit.jupiter.api.Test;

class OrderFinancialPolicyTest {

    @Test
    void paidOrderCanBeCancelledBeforeRefundCompletes() {
        Order order = order(PaymentMethod.VNPAY, PaymentStatus.PAID);

        assertThatCode(() -> OrderFinancialPolicy.validateTransition(order, OrderStatus.CANCELLED))
                .doesNotThrowAnyException();
    }

    @Test
    void unpaidVnpayOrderCannotBeConfirmed() {
        Order order = order(PaymentMethod.VNPAY, PaymentStatus.PENDING);

        assertThatThrownBy(() -> OrderFinancialPolicy.validateTransition(order, OrderStatus.CONFIRMED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("VNPAY order must be paid before confirmation");
    }

    @Test
    void paidVnpayAndUnpaidCodOrdersCanBeConfirmed() {
        assertThatCode(() -> OrderFinancialPolicy.validateTransition(
                        order(PaymentMethod.VNPAY, PaymentStatus.PAID), OrderStatus.CONFIRMED))
                .doesNotThrowAnyException();
        assertThatCode(() -> OrderFinancialPolicy.validateTransition(
                        order(PaymentMethod.COD, PaymentStatus.UNPAID), OrderStatus.CONFIRMED))
                .doesNotThrowAnyException();
    }

    private Order order(PaymentMethod method, PaymentStatus status) {
        Order order = new Order();
        order.setPaymentMethod(method);
        order.setPaymentStatus(status);
        return order;
    }
}
