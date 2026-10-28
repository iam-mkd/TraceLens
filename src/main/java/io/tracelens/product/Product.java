package io.tracelens.product;

import java.math.BigDecimal;

public record Product(long id, String name, BigDecimal price, String currency) {
}
