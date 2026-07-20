-- ─────────────────────────────────────────────────────────────────────────────
-- Migration: Corrección de máscaras de Tax ID — Costa Rica
-- Tarea          : B2B-646 — Ajuste facturación electrónica Costa Rica (Elera)
-- Base de datos  : quotes_dqs_osys
--
-- Las 4 filas de Costa Rica ya existían en ps_fel (felid 1-4), pero con
-- máscaras/rangos que no cumplen los requisitos del ticket:
--   - Cédula Jurídica (felid=2): máscara solo numérica -> bloquea letras.
--     Requisito: "El campo de Cédula Jurídica debe permitir el ingreso de letras".
--   - NITE (felid=4): minimo/maximo=9 -> debe ser 10 (Excel de requerimiento).
--   - Dimex (felid=3): máscara fija de 10 dígitos -> debe permitir 11 a 12.
--
-- `formato` usa la sintaxis de Inputmask.js: '9' = dígito requerido,
-- '*' = alfanumérico requerido, '[ ]' = sección opcional.
-- ─────────────────────────────────────────────────────────────────────────────

-- Cédula Jurídica: 10 alfanumérico (A-Z, 0-9)
UPDATE quotes_dqs_osys.ps_fel
SET formato = '**********', minimo = 10, maximo = 10
WHERE felid = 2 AND pais_iso2 = 'CR';

-- Dimex: 11 a 12 dígitos numéricos
UPDATE quotes_dqs_osys.ps_fel
SET formato = '99999999999[9]', minimo = 11, maximo = 12
WHERE felid = 3 AND pais_iso2 = 'CR';

-- NITE: 10 dígitos numéricos (antes 9)
UPDATE quotes_dqs_osys.ps_fel
SET formato = '9999999999', minimo = 10, maximo = 10
WHERE felid = 4 AND pais_iso2 = 'CR';

-- nombre_en alimenta el atributo taxIdType/EI-IdType enviado a OMS.
-- El equipo de POS validó que debe coincidir exactamente con el formato del
-- requerimiento: "PHYSICAL", "LEGAL", "Dimex", "NITE" (no "Physical"/"Legal").
UPDATE quotes_dqs_osys.ps_fel
SET nombre_en = 'PHYSICAL'
WHERE felid = 1 AND pais_iso2 = 'CR';

UPDATE quotes_dqs_osys.ps_fel
SET nombre_en = 'LEGAL'
WHERE felid = 2 AND pais_iso2 = 'CR';

-- ── Verificación ─────────────────────────────────────────────────────────────

SELECT felid, nombre_es, nombre_en, pais_iso2, minimo, maximo, formato
FROM quotes_dqs_osys.ps_fel
WHERE pais_iso2 = 'CR'
ORDER BY felid;
