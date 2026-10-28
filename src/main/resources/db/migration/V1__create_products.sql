CREATE TABLE products (
    id BIGINT PRIMARY KEY CHECK (id > 0),
    name VARCHAR(200) NOT NULL,
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$')
);

-- Synthetic lab fixture; no customer or payment data.
INSERT INTO products (id, name, price, currency)
VALUES (1, 'TraceLens Demo Product', 19.99, 'USD');
