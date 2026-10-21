package com.delivery.order_service.payload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void mapsSpringPageToStablePublicContract() {
        var source = new PageImpl<>(List.of("order-1"), PageRequest.of(0, 20), 1);

        var response = PageResponse.from(source);

        assertEquals(List.of("order-1"), response.items());
        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertEquals(1, response.totalItems());
        assertEquals(1, response.totalPages());
        assertFalse(response.hasNext());
    }
}
