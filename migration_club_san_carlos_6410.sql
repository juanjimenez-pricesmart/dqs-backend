-- ─────────────────────────────────────────────────────────────────────────────
-- Migration: Apertura Club San Carlos (6410) — Costa Rica
-- Solicitado por : Juan Jiménez / B2B Costa Rica
-- Fecha          : 2026-06-22
-- Base de datos  : quotes_dqs_osys
--
-- Criterios de aceptación:
--   1. Club disponible para asignación a Ejecutivos B2B  (status = 'A')
--   2. Integración OMS habilitada (pais_iso2, moneda, idioma, impuesto_operacion)
--   3. Configuración siguiendo estructura de Costa Rica
--
-- ⚠️  Campos marcados con [CONFIRMAR] deben ser validados con el club antes
--     de ejecutar en producción.
-- ─────────────────────────────────────────────────────────────────────────────

    INSERT INTO quotes_dqs_osys.ps_tienda
(
    ps_tienda_id,
    nombre,
    pais,
    pais_iso2,
    latitud,
    longitud,
    moneda,
    direccion,
    telefono,
    fax,
    nit,
    status,
    actualizacion_precios,
    actualizacion_tasacambio,
    tasacambio,
    impuesto,
    impuesto_xtra,
    impuesto_operacion,
    disclaimer,
    tributo_siglas,
    ipaddress,
    titulo_cotizacion,
    idtracking,
    disclaimer1,
    delivery,
    delivery_options,
    delivery_disclaimer,
    titulo_delivery,
    delivery_ddc,
    delivery_options_ddc,
    delivery_disclaimer_dcc,
    titulo_delivery_ddc,
    ddc_inventory,
    hd_contrato,
    hd_contrato_option,
    temporadas,
    notificaciones,
    peso,
    volumen,
    delivery_op1,
    delivery_op2,
    delivery_op3,
    delivery_op4,
    delivery_opdescripcion,
    risk_status,
    risk_puntuacion,
    idioma,
    zonahoraria,
    verzonas
)
VALUES
(
    6410,                          -- ps_tienda_id
    'San Carlos',                  -- nombre
    'Costa Rica',                  -- pais  (mismo formato que demás clubes CR)
    'CR',                          -- pais_iso2  (ISO 3166-1 alpha-2 correcto)
    10.3294,                       -- latitud   [CONFIRMAR] coordenadas Ciudad Quesada, San Carlos
    -84.4307,                      -- longitud  [CONFIRMAR] coordenadas Ciudad Quesada, San Carlos
    'CRC',                         -- moneda  (Colón costarricense)
    'Sin confirmar',               -- direccion  [CONFIRMAR] dirección física del club
    'Sin confirmar',               -- telefono  [CONFIRMAR] teléfono del club
    '',                            -- fax
    'Sin confirmar',               -- nit  [CONFIRMAR] cédula jurídica PriceSmart CR
    'A',                           -- status  (Activo — disponible para asignación B2B)
    '2024-02-24 00:00:00',         -- actualizacion_precios  (mismo patrón clubes CR)
    '0000-00-00',                  -- actualizacion_tasacambio  (mismo patrón clubes CR)
    519.0000,                      -- tasacambio  [CONFIRMAR] tasa CRC/USD referencia jun-2026
    13,                            -- impuesto  (IVA 13% Costa Rica)
    0,                             -- impuesto_xtra  (mismo que demás clubes CR)
    '-',                           -- impuesto_operacion  (mismo patrón clubes CR — el IVA se maneja en el campo impuesto)
    'Los precios están sujetos a cambio sin previo aviso, las existencias dependen de las ventas diarias y están sujetas a confirmación. Si cancela por medio de depósito o transferencia, necesitamos nos envíe copia del movimiento para su confirmación, (este tiempo está sujeto a la recepción de la copia del movimiento), este proceso tarda 48 horas hábiles para su aprobación por parte de Contabilidad. Hasta contar con esta aprobación se puede confirmar el inventario.',
                                   -- disclaimer  (mismo formato clubes CR)
    'IVA',                         -- tributo_siglas  (Impuesto al Valor Agregado CR)
    '',                            -- ipaddress  [CONFIRMAR] IP del club si aplica
    'Cotizacion',                  -- titulo_cotizacion  (mismo que demás clubes CR)
    0,                             -- idtracking  [CONFIRMAR] asignar valor real del club
    '',                            -- disclaimer1  (mismo que demás clubes CR)
    2,                             -- delivery  (mismo que demás clubes CR)
    2,                             -- delivery_options  (mismo que demás clubes CR)
    '',                            -- delivery_disclaimer  (mismo que demás clubes CR)
    'Servicio de Envio a Domicilio', -- titulo_delivery  (mismo que demás clubes CR)
    1,                             -- delivery_ddc  (mismo que demás clubes CR)
    0,                             -- delivery_options_ddc  (mismo que demás clubes CR)
    '',                            -- delivery_disclaimer_dcc  (mismo que demás clubes CR)
    '',                            -- titulo_delivery_ddc  (mismo que demás clubes CR)
    0,                             -- ddc_inventory  (mismo que demás clubes CR)
    '',                            -- hd_contrato  (mismo que demás clubes CR)
    0,                             -- hd_contrato_option  (mismo que demás clubes CR)
    2,                             -- temporadas  (mismo que demás clubes CR)
    '',                            -- notificaciones  (mismo que demás clubes CR)
    'Kgs',                         -- peso  (Costa Rica usa Kgs, a diferencia de RD que usa Lbs)
    'Mtrs',                        -- volumen
    1,                             -- delivery_op1  (mismo que demás clubes CR)
    1,                             -- delivery_op2  (mismo que demás clubes CR)
    1,                             -- delivery_op3  (mismo que demás clubes CR)
    0,                             -- delivery_op4  (mismo que demás clubes CR)
    0,                             -- delivery_opdescripcion  (mismo que demás clubes CR)
    1,                             -- risk_status  (mismo que demás clubes CR)
    0,                             -- risk_puntuacion  (mismo que demás clubes CR)
    'es',                          -- idioma
    'America/Costa_Rica',          -- zonahoraria  (UTC-6, sin horario de verano)
    0                              -- verzonas
);

-- ── Verificación ─────────────────────────────────────────────────────────────

SELECT
    ps_tienda_id, nombre, pais, pais_iso2, moneda, status,
    impuesto, impuesto_xtra, impuesto_operacion, idioma, zonahoraria
FROM quotes_dqs_osys.ps_tienda
WHERE ps_tienda_id = 6410;
