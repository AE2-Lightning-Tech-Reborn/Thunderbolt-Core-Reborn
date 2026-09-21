# Forge 1.20.1 main integration

Reference: main `3798e5b` (final state), applied on the existing Forge port.

- Port V2/CP-SAT material allocation, executable cycle and replenishment certificates, provider caching and planned input handling.
- Port arbitrary-precision cell storage, exact crafting previews and certified execution programs.
- Retain Java 17, Forge 47, AE2 15, legacy NBT and the Forge-specific optional integration bridges and persistence repair behavior.
- Adapt confirmation packet extensions to AE2 15 constructor serialization and preserve other summary extensions.
- Reuse an exact DAG replenishment certificate instead of discarding it after a redundant optional probe times out. Scale the bounded independent-component verification allowance for Java 17 while respecting caller cancellation and shared solver work limits.

Validation: 837 JUnit tests, Forge build and local Maven publication. Cross-mod Forge startup is validated together with AE2LT.
