# Util

`ClubCapabilities.java` — source of truth for which features are enabled per club and country.

## Always ask before changing
- Adding or removing a capability flag affects every service that calls it
- Never add club/country-specific logic inline in a service — add a capability here and call it from the service
