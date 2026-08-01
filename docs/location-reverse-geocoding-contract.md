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
