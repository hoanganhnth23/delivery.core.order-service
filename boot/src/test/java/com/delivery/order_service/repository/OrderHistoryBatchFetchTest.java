package com.delivery.order_service.repository;

import com.delivery.order_service.entity.Order;
import com.delivery.order_service.entity.OrderItem;
import com.delivery.order_service.entity.OrderStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
class OrderHistoryBatchFetchTest {

    @Autowired OrderRepository orderRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @Test
    void boundedHistoryLoadsItemsInOneBatchInsteadOfNPlusOne() {
        for (int i = 0; i < 10; i++) {
            Order order = new Order();
            order.setUserId(77L);
            order.setRestaurantId(88L);
            order.setCreatorId(88L);
            order.setStatus(OrderStatus.PENDING);

            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setMenuItemId(100L + i);
            item.setMenuItemName("item-" + i);
            item.setQuantity(1);
            item.setPrice(BigDecimal.TEN);
            order.getItems().add(item);
            orderRepository.save(order);
        }
        orderRepository.flush();
        entityManager.clear();

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var page = orderRepository.findByUserIdOrderByCreatedAtDesc(77L, PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(10);
        assertThat(page.getContent()).allSatisfy(order -> assertThat(order.getItems()).hasSize(1));

        // Page query + count query + one batched collection query.
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }
}
