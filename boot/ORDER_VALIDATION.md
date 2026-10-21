# 🛡️ Order Service Validation System

## 📋 **Tổng quan**

Order Service hiện có hệ thống validation 2 tầng:
1. **Annotation-based Validation** (Controller layer) - Bean Validation với `@Valid`
2. **Business Logic Validation** (Service layer) - Custom validation rules

## 🔧 **Annotation-based Validation**

### CreateOrderRequest Validation Rules

#### 📍 **Restaurant Information**
```java
@NotNull(message = "Restaurant ID không được để trống")
@Positive(message = "Restaurant ID phải là số dương")
private Long restaurantId;

@NotBlank(message = "Tên nhà hàng không được để trống")
@Size(max = 255, message = "Tên nhà hàng không được vượt quá 255 ký tự")
private String restaurantName;

@Pattern(regexp = "^(\\+84|0)[1-9][0-9]{8,9}$", message = "Số điện thoại nhà hàng không hợp lệ")
private String restaurantPhone;
```

#### 🚚 **Delivery Information** 
```java
@NotBlank(message = "Địa chỉ giao hàng không được để trống")
@Size(max = 500, message = "Địa chỉ giao hàng không được vượt quá 500 ký tự")
private String deliveryAddress;

@DecimalMin(value = "8.0", message = "Latitude giao hàng phải trong khoảng từ 8.0 đến 24.0")
@DecimalMax(value = "24.0", message = "Latitude giao hàng phải trong khoảng từ 8.0 đến 24.0")
private Double deliveryLat;

@DecimalMin(value = "102.0", message = "Longitude giao hàng phải trong khoảng từ 102.0 đến 110.0") 
@DecimalMax(value = "110.0", message = "Longitude giao hàng phải trong khoảng từ 102.0 đến 110.0")
private Double deliveryLng;
```

#### 🏪 **Pickup Information**
```java
@DecimalMin(value = "8.0", message = "Latitude pickup phải trong khoảng từ 8.0 đến 24.0")
@DecimalMax(value = "24.0", message = "Latitude pickup phải trong khoảng từ 8.0 đến 24.0")
private Double pickupLat;

@DecimalMin(value = "102.0", message = "Longitude pickup phải trong khoảng từ 102.0 đến 110.0")
@DecimalMax(value = "110.0", message = "Longitude pickup phải trong khoảng từ 102.0 đến 110.0")
private Double pickupLng;
```

#### 👤 **Customer Information**
```java
@NotBlank(message = "Tên khách hàng không được để trống")
@Size(max = 100, message = "Tên khách hàng không được vượt quá 100 ký tự")
private String customerName;

@NotBlank(message = "Số điện thoại khách hàng không được để trống")
@Pattern(regexp = "^(\\+84|0)[1-9][0-9]{8,9}$", message = "Số điện thoại khách hàng không hợp lệ")
private String customerPhone;
```

#### 💰 **Payment Information**
```java
@NotBlank(message = "Phương thức thanh toán không được để trống")
@Pattern(regexp = "^(COD|ONLINE)$", message = "Phương thức thanh toán phải là COD hoặc ONLINE")
private String paymentMethod;
```

#### 🛒 **Order Items**
```java
@NotNull(message = "Danh sách sản phẩm không được để trống")
@NotEmpty(message = "Phải có ít nhất một sản phẩm trong đơn hàng")
@Valid
private List<OrderItemRequest> items;
```

### OrderItemRequest Validation Rules

```java
@NotNull(message = "Menu Item ID không được để trống")
@Positive(message = "Menu Item ID phải là số dương")
private Long menuItemId;

@NotBlank(message = "Tên sản phẩm không được để trống")
@Size(max = 255, message = "Tên sản phẩm không được vượt quá 255 ký tự")
private String menuItemName;

@NotNull(message = "Số lượng không được để trống")
@Min(value = 1, message = "Số lượng phải lớn hơn 0")
@Max(value = 99, message = "Số lượng không được vượt quá 99")
private Integer quantity;

@NotNull(message = "Giá sản phẩm không được để trống")
@DecimalMin(value = "0.01", message = "Giá sản phẩm phải lớn hơn 0")
@Digits(integer = 10, fraction = 2, message = "Giá sản phẩm không hợp lệ")
private BigDecimal price;
```

## 🎯 **Business Logic Validation**

### OrderValidationService Rules

#### 1. **Required Fields Consistency**
- Restaurant ID phải là số dương
- Tọa độ delivery và pickup phải có đầy đủ lat/lng hoặc đều null

#### 2. **Business Rules**
- ✅ Tối thiểu 1 sản phẩm, tối đa 50 sản phẩm
- ✅ Validation từng sản phẩm trong đơn hàng
- ✅ Số điện thoại Việt Nam format: `(+84|84|0)(3|5|7|8|9)[0-9]{8}`

#### 3. **Coordinate Validation**
- ✅ Vietnam coordinate bounds: Lat(8.0-24.0), Lng(102.0-110.0)
- ✅ Maximum distance between pickup-delivery: 100km
- ✅ Haversine formula cho tính khoảng cách

#### 4. **Financial Validation**
- ✅ Giá trị đơn hàng tối thiểu: 10,000 VND
- ✅ Giá trị đơn hàng tối đa: 100,000,000 VND
- ✅ Giá sản phẩm tối đa: 10,000,000 VND
- ✅ Total giá trị từng item tối đa: 50,000,000 VND

#### 5. **User Context Validation**
- ✅ User ID hợp lệ và > 0
- ✅ Có thể mở rộng cho credit limit, delivery zone restrictions

## 🚨 **Exception Handling**

### GlobalExceptionHandler

#### 1. **MethodArgumentNotValidException**
```java
// Trả về field-level validation errors
{
  "status": 0,
  "data": {
    "restaurantId": "Restaurant ID không được để trống",
    "customerPhone": "Số điện thoại khách hàng không hợp lệ"
  },
  "message": "Dữ liệu đầu vào không hợp lệ"
}
```

#### 2. **ValidationException** 
```java
// Trả về business logic validation errors
{
  "status": 0,
  "data": null,
  "message": "Dữ liệu đơn hàng không hợp lệ: Giá trị đơn hàng tối thiểu là 10,000 VND, Khoảng cách giữa điểm lấy hàng và giao hàng không được vượt quá 100km"
}
```

## 🔄 **Validation Flow**

```mermaid
graph TD
    A[POST /api/orders] --> B[@Valid Annotation]
    B --> C{Bean Validation Pass?}
    C -->|❌| D[MethodArgumentNotValidException]
    C -->|✅| E[OrderValidationService]
    E --> F{Business Rules Pass?}
    F -->|❌| G[ValidationException]
    F -->|✅| H[Create Order]
    
    D --> I[400 Bad Request with field errors]
    G --> J[400 Bad Request with business error]
    H --> K[201 Created with OrderResponse]
```

## 🧪 **Testing Validation**

### Test Cases

#### ✅ **Valid Request**
```json
{
  "restaurantId": 1,
  "restaurantName": "Nhà hàng ABC",
  "restaurantAddress": "123 Nguyễn Văn A, Q1, TP.HCM",
  "restaurantPhone": "0901234567", 
  "deliveryAddress": "456 Lê Văn B, Q3, TP.HCM",
  "deliveryLat": 10.762622,
  "deliveryLng": 106.660172,
  "pickupLat": 10.772234,
  "pickupLng": 106.698345,
  "customerName": "Nguyễn Văn C",
  "customerPhone": "0912345678",
  "paymentMethod": "COD",
  "notes": "Ghi chú đơn hàng",
  "items": [
    {
      "menuItemId": 1,
      "menuItemName": "Phở bò",
      "quantity": 2,
      "price": 50000,
      "notes": "Không hành"
    }
  ]
}
```

#### ❌ **Invalid Requests**

1. **Missing Required Fields**
```json
{
  "restaurantId": null,  // ❌ Restaurant ID null
  "items": []            // ❌ Empty items
}
```

2. **Invalid Coordinates**
```json
{
  "deliveryLat": 50.0,   // ❌ Outside Vietnam bounds
  "deliveryLng": 120.0   // ❌ Outside Vietnam bounds
}
```

3. **Invalid Financial Data**
```json
{
  "items": [
    {
      "price": -1000,      // ❌ Negative price
      "quantity": 0        // ❌ Zero quantity
    }
  ]
}
```

## 🚀 **Integration với Kafka**

Validation được thực hiện **TRƯỚC KHI** publish OrderCreatedEvent:

```java
@Override
@Transactional
public OrderResponse createOrder(CreateOrderRequest request, Long userId, String role) {
    // ✅ Validate TRƯỚC
    orderValidationService.validateCreateOrderRequest(request, userId);
    
    // Tạo order
    Order order = orderMapper.createOrderRequestToOrder(request);
    // ... xử lý business logic
    
    // ✅ Publish event CHÍNH XÁC
    orderEventPublisher.publishOrderCreatedEvent(savedOrder);
    
    return orderMapper.orderToOrderResponse(savedOrder);
}
```

## 📊 **Monitoring & Logging**

### Validation Logs

```java
// ✅ Success log
log.info("✅ Order validation passed for user: {}", userId);

// 🚨 Validation error log  
log.error("🚨 Order validation failed for user {}: {}", userId, errorMessage);

// 🚨 Exception handling logs
log.error("🚨 Validation error: {}", errors);
log.error("🚨 Business validation error: {}", ex.getMessage());
```

### Key Metrics to Monitor

1. **Validation Success Rate**: % requests passing validation
2. **Common Validation Errors**: Top validation failure reasons
3. **Performance Impact**: Validation time impact on request processing
4. **Business Rule Violations**: Frequency of specific business rule failures

---

**🎯 Kết luận**: Order Service giờ có hệ thống validation 2 tầng comprehensive, đảm bảo data integrity trước khi xử lý business logic và publish Kafka events!
