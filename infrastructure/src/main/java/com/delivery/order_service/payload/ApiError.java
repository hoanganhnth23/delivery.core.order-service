package com.delivery.order_service.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Typed public error metadata; clients branch on code, never message text. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, Object details) { }
