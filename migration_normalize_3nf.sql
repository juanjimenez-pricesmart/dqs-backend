-- Migración 3FN: normalización de quotations y quotation_items
-- Ejecutar en orden. Requiere que las tablas originales existan con datos.

-- ─── 1. Nuevas tablas ────────────────────────────────────────────────────────

CREATE TABLE quotation_customers (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quotation_id     BIGINT       NOT NULL UNIQUE,
    customer_name    VARCHAR(200) NOT NULL,
    customer_membership VARCHAR(50)  DEFAULT NULL,
    customer_business   VARCHAR(200) DEFAULT NULL,
    CONSTRAINT fk_qc_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id)
);

CREATE TABLE quotation_totals (
    id                  BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quotation_id        BIGINT         NOT NULL UNIQUE,
    tax_rate            DECIMAL(10,4)  DEFAULT NULL,
    aplicar_impuestos   TINYINT        DEFAULT NULL,
    excent              TINYINT        DEFAULT NULL,
    gross_amount        DECIMAL(15,4)  DEFAULT NULL,
    net_amount          DECIMAL(15,4)  DEFAULT NULL,
    discount            DECIMAL(15,4)  DEFAULT NULL,
    vat_charge_rate     DECIMAL(10,4)  DEFAULT NULL,
    vat_charge          DECIMAL(15,4)  DEFAULT NULL,
    service_charge_rate DECIMAL(10,4)  DEFAULT NULL,
    service_charge      DECIMAL(15,4)  DEFAULT NULL,
    delivery_amount     DECIMAL(15,4)  DEFAULT NULL,
    CONSTRAINT fk_qt_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id)
);

CREATE TABLE quotation_payment (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quotation_id      BIGINT       NOT NULL UNIQUE,
    paid_status       INT          DEFAULT NULL,
    quote_type_id     INT          DEFAULT NULL,
    payment_number    VARCHAR(100) DEFAULT NULL,
    payment_method_id VARCHAR(20)  DEFAULT NULL,
    service_id        INT          DEFAULT NULL,
    quote_no          VARCHAR(50)  DEFAULT NULL,
    CONSTRAINT fk_qp_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(id)
);

CREATE TABLE quotation_item_taxes (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id          BIGINT        NOT NULL UNIQUE,
    tax_porcentaje   DECIMAL(10,4) DEFAULT NULL,
    tax_factor       DECIMAL(15,4) DEFAULT NULL,
    tax_amount       DECIMAL(15,4) DEFAULT NULL,
    tax_ico          DECIMAL(15,4) DEFAULT NULL,
    excent_porcentaje DECIMAL(10,4) DEFAULT NULL,
    excent_amount    DECIMAL(15,4) DEFAULT NULL,
    CONSTRAINT fk_qit_item FOREIGN KEY (item_id) REFERENCES quotation_items(id)
);

CREATE TABLE quotation_item_product (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id      BIGINT        NOT NULL UNIQUE,
    description  VARCHAR(500)  DEFAULT NULL,
    cu_ea        DECIMAL(10,4) DEFAULT NULL,
    pl           DECIMAL(10,4) DEFAULT NULL,
    weight_ea    DECIMAL(10,4) DEFAULT NULL,
    weight_result DECIMAL(15,4) DEFAULT NULL,
    palletxqty   DECIMAL(10,4) DEFAULT NULL,
    onhand       DECIMAL(10,2) DEFAULT NULL,
    soldbyweight VARCHAR(1)    DEFAULT NULL,
    recipe       VARCHAR(1)    DEFAULT NULL,
    storagetype  VARCHAR(10)   DEFAULT NULL,
    picture1     VARCHAR(500)  DEFAULT NULL,
    department   VARCHAR(100)  DEFAULT NULL,
    category     VARCHAR(100)  DEFAULT NULL,
    CONSTRAINT fk_qip_item FOREIGN KEY (item_id) REFERENCES quotation_items(id)
);

-- ─── 2. Migrar datos de quotations → tablas normalizadas ─────────────────────

INSERT INTO quotation_customers (quotation_id, customer_name, customer_membership, customer_business)
SELECT id, customer_name, customer_membresia, customer_business
FROM quotations;

INSERT INTO quotation_totals (
    quotation_id, tax_rate, aplicar_impuestos, excent,
    gross_amount, net_amount, discount,
    vat_charge_rate, vat_charge,
    service_charge_rate, service_charge, delivery_amount
)
SELECT id, tax_rate, aplicar_impuestos, excent,
       gross_amount, net_amount, discount,
       vat_charge_rate, vat_charge,
       service_charge_rate, service_charge, delivery_amount
FROM quotations;

INSERT INTO quotation_payment (
    quotation_id, paid_status, quote_type_id,
    payment_number, payment_method_id, service_id, quote_no
)
SELECT id, paid_status, quote_type_id,
       payment_number, payment_method_id, service_id, quote_no
FROM quotations
WHERE paid_status IS NOT NULL
   OR quote_type_id IS NOT NULL
   OR payment_number IS NOT NULL
   OR payment_method_id IS NOT NULL
   OR service_id IS NOT NULL
   OR quote_no IS NOT NULL;

-- ─── 3. Migrar datos de quotation_items → tablas normalizadas ────────────────

INSERT INTO quotation_item_taxes (
    item_id, tax_porcentaje, tax_factor, tax_amount,
    tax_ico, excent_porcentaje, excent_amount
)
SELECT id, tax_porcentaje, tax_factor, tax_amount,
       tax_ico, excent_porcentaje, excent_amount
FROM quotation_items;

INSERT INTO quotation_item_product (
    item_id, description, cu_ea, pl, weight_ea,
    weight_result, palletxqty, onhand,
    soldbyweight, recipe, storagetype, picture1,
    department, category
)
SELECT id, description, cu_ea, pl, weight_ea,
       weight_result, palletxqty, onhand,
       soldbyweight, recipe, storagetype, picture1,
       department, category
FROM quotation_items;

-- ─── 4. Limpiar columnas migradas de quotations ──────────────────────────────

ALTER TABLE quotations
    DROP COLUMN customer_name,
    DROP COLUMN customer_membresia,
    DROP COLUMN customer_business,
    DROP COLUMN tax_rate,
    DROP COLUMN aplicar_impuestos,
    DROP COLUMN excent,
    DROP COLUMN gross_amount,
    DROP COLUMN net_amount,
    DROP COLUMN discount,
    DROP COLUMN vat_charge_rate,
    DROP COLUMN vat_charge,
    DROP COLUMN service_charge_rate,
    DROP COLUMN service_charge,
    DROP COLUMN delivery_amount,
    DROP COLUMN paid_status,
    DROP COLUMN quote_type_id,
    DROP COLUMN payment_number,
    DROP COLUMN payment_method_id,
    DROP COLUMN service_id,
    DROP COLUMN quote_no;

-- ─── 5. Limpiar columnas migradas de quotation_items ────────────────────────

ALTER TABLE quotation_items
    DROP COLUMN description,
    DROP COLUMN tax_porcentaje,
    DROP COLUMN tax_factor,
    DROP COLUMN tax_amount,
    DROP COLUMN tax_ico,
    DROP COLUMN excent_porcentaje,
    DROP COLUMN excent_amount,
    DROP COLUMN cu_ea,
    DROP COLUMN pl,
    DROP COLUMN weight_ea,
    DROP COLUMN weight_result,
    DROP COLUMN palletxqty,
    DROP COLUMN onhand,
    DROP COLUMN soldbyweight,
    DROP COLUMN recipe,
    DROP COLUMN storagetype,
    DROP COLUMN picture1,
    DROP COLUMN department,
    DROP COLUMN category;
