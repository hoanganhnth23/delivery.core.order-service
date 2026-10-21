package com.delivery.order_service.payload;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> source) {
        return new PageResponse<>(
                List.copyOf(source.getContent()),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext());
    }
}
