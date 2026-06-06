-- Tabla de credenciales GlobalPay por tienda
CREATE TABLE ps_globalpay_credenciales (
    id               INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id         INT          NOT NULL UNIQUE,
    server_appcode   VARCHAR(100) NOT NULL,
    server_appkey    VARCHAR(100) NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
