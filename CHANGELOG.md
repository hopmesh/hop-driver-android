# Changelog

Notable changes, generated from [conventional commits](https://www.conventionalcommits.org) by
git-cliff. Do not edit by hand.
## Unreleased

### CI
- bump create-github-app-token to v3.2.0 across all mirrored components (efc9f6c)

### Chore
- bump org.json:json in /apps/android/HopDemo (#107) (2a893c5)
- bump org.robolectric:robolectric in /apps/android/HopDemo (#110) (89cb246)
- bump androidx.test:core in /bearers/android (#113) (df1e0e0)
- drop the root license, license per-component (FSL-1.1-ALv2) (#146) (be2a5a7)
- finish the monorepo layout, kill platform stubs, unify the platform axis (O-1/O-3/O-4/O-5) (#115) (b56bb49)
- organize the monorepo, all apps under apps/<platform>/<App> + a CLAUDE.md at every tree node (#105) (73c3249)
- bump net.java.dev.jna:jna in /bearers/android (#32) (257bdea)

### Dependencies
- AGP 9.3 + Gradle 9.6.1 + Kotlin 2.4.10 + core-ktx 1.19.0 (13 held bumps) (471edb7)
- Kotlin 2.4/AGP 9.2.1/Compose BOM 2026.06/okhttp 5.4 toolchain migration (#90) (d4844bd)

### Documentation
- branded, marketable READMEs for every sub-repo (9c2a477)
- stop mentioning DNSSEC (no longer part of the design) (179a278)

### Features
- self-certifying reachability records (core + ABI) for DNS-free endpoint discovery (#126) (7c31123)

### Other
- CLA gate on contributions (preserve commercial relicensing of core) (5a9aa7d)
- SECURITY.md per component + enable-security in the bootstrap script (a1492e9)
- copyright holder is Hop Mesh, LLC (7d8c514)
- fill the Apache-2.0 copyright placeholder (2026 Jason Waldrip) (2fb7d1c)
- Apache-2.0 for everything except core/ (only the protocol stays FSL) (0fe9439)
- CHANGE_REQUEST sync-back + document merge/conversation + confidentiality (9e1dec2)
- make the TLS-served reach record the only name path (drop DNSSEC-over-DoH) (#139) (8998288)
- decompose the 1489-line HopBearer god-object into per-concern collaborators (C+ → A) (#76) (bb4c68d)
- remove the Wi-Fi Direct bearer (per-device approval dialog) (c059d69)
- app identifiers + code packages net.waldrip.* / co.hopmesh.* -> sh.hopme.* (9050fbd)
- thin HopDriver composing the SDK + all four bearers (mirror of Apple) (171b04a)

### Testing
- tolerate inbox journal compaction (6a33d64)
- Robolectric suite takes the driver from D (~16%) to A (94% line) (#65) (112ea0f)

