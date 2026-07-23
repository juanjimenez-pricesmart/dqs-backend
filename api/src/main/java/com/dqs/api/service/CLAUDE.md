# Services

Business logic layer.

## Always ask before changing
- `PriceSmartPaymentService` — payment flow, invoice ID generation, attempt tracking
- `GlobalPayService` — Payment Link (tender_key 144) flow, only used for Cartago
- `OmsService` + `OmsPayloadBuilder` — OMS submission logic
- `FiscalService` — fiscal data handling; El Salvador (SLV) is the only country with extra fields (NRC, economic activity, city/zone/neighborhood)
- Any method signature change — callers may span multiple controllers

## Apply directly
- Internal refactors with no behavior change
- Adding a new private helper method that doesn't alter existing method contracts

## Key rule
Always check `ClubCapabilities.java` before adding any club/country-specific conditional.
