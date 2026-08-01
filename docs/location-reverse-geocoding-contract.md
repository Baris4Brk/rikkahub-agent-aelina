# Location reverse-geocoding contract

Baseline: `feature/owner-pet-library-control` containing `ebfc38bd`, Room v42.

This feature extends the existing `get_location` tool without changing its default behavior and adds an independent `reverse_geocode` tool. The independent tool transforms a caller-supplied WGS84 coordinate and therefore must not require Android location permission.

Privacy and compatibility boundaries:

- `get_location` keeps all existing parameters and compatibility fields. Address lookup is opt-in.
- Android `Geocoder` is an implementation-managed best-effort service. It must not be described as proven offline or as proven not to share coordinates.
- Configured external providers require both a global enablement and explicit permission on the individual call.
- Address results are nearby/best-guess map data. They are not proof that a device is inside a POI, building, or road.
- Exact coordinates, returned addresses, provider secrets, request URLs, and raw responses must not enter diagnostics or logs.
- Reverse-geocode caching is process-memory-only and must not become a location history, backup, or export surface.
- The active local second user keeps the existing DIRECT tool surface and automatic-execution policy. This feature adds no catalog-search step and no second-user-specific approval flow.
- HARDLINE, Emergency Stop, authority/epoch checks, lock state, invocation-origin restrictions, and Android system permissions remain authoritative.

The implementation is split into a platform-only P0 followed by an explicitly configured online-provider P1. P0 must pass its JVM and compilation checks before P1 begins.

## Implemented provider status

- Android platform Geocoder: implemented with API 33 callback support, legacy IO dispatch, stable timeout/cancellation handling, and `platform_geocoder_unknown` disclosure.
- Configured Amap Web Service: implemented against the official v3 reverse-geocoding contract. The endpoint is user-supplied, the Web Service key comes only from a `REVERSE_GEOCODER` Vault binding, longitude is sent before latitude, and WGS84 is converted to GCJ-02 only when the saved configuration explicitly requires it.
- Configured Nominatim: intentionally not implemented yet. The application has no dedicated contact/User-Agent setting required for a responsible public-instance integration; no public endpoint is bundled.
- Configured BigDataCloud: intentionally not implemented yet because this implementation pass did not establish a sufficiently precise current response/licensing contract. The settings discriminator is reserved, but the coordinator returns a stable unsupported/not-configured result instead of guessing fields.

Online requests use a dedicated OkHttp client derived from the application network stack. It removes all application and network interceptors, disables redirects and automatic retries, uses bounded timeouts, accepts JSON only, and caps response bodies at 512 KiB. Provider URLs, coordinates, secrets, response bodies, and returned addresses are never included in diagnostics.

Contract references checked during implementation:

- Amap reverse geocoding: https://lbs.amap.com/api/webservice/guide/api/georegeo/
- Amap status codes: https://lbs.amap.com/api/web-service/tools/info
- Android Geocoder: https://developer.android.com/reference/android/location/Geocoder
- Nominatim public-instance policy: https://operations.osmfoundation.org/policies/nominatim/
