package com.delivery.order_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantResponse {
    private Long id;
    private String name;
    private String address;
    private String description;
    private String phone;
    private Long creatorId;
    private LocalTime openingHour;
    private LocalTime closingHour;
    private String image;
    private Double addressLat;
    private Double addressLng;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
