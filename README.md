<p align="center">
  <img alt="Hop" src="https://hopme.sh/hop-mark.svg" width="200">
</p>

<h1 align="center">hop-driver</h1>

<p align="center">
  <b>The Android client for Hop: a node, every bearer, storage, and a clean send/receive API in one module.</b><br>
  Embed one thing and your app is a full mesh peer.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF" alt="Kotlin 2.4">
  <img src="https://img.shields.io/badge/Android-minSdk%2029-3ddc84" alt="Android minSdk 29">
  <img src="https://img.shields.io/badge/license-Apache--2.0-3ddc84" alt="license Apache-2.0">
</p>

---

Hop is a **delay-tolerant, end-to-end-encrypted mesh**: messages hop device to device over BLE, Wi-Fi,
and the internet until they reach the person or service you meant. Held, never dropped.

**hop-driver is the app-facing client.** It owns a `hop-core` node, wires up the BLE, LAN, Wi-Fi Direct,
and cloud-relay bearers, persists chat and identity, and exposes a `HopBearer` with `send`, `addContact`,
and Compose-observable `messages` / `peers`, so an app binds one object instead of stitching a node,
radios, and a store together itself.

## Install

Publishes as an Android library (Maven / AAR). Point Gradle at the package repo (`minSdk` 29, the floor
for L2CAP CoC):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://maven.pkg.github.com/hopmesh/hop-driver-android")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.hopme:hop-driver:0.0.1")
}
```

## Usage

`HopBearer` is a configure-once singleton: the foreground service owns it, the UI observes it.

```kotlin
import sh.hopme.driver.HopBearer
import sh.hopme.driver.HopConfig

// HopConfig.default reproduces the demo app's sources (device-derived identity,
// on-device hop.db, the device name); override any field you want to own.
val hop = HopBearer.shared(context, HopConfig.default(context))
hop.start()                                   // brings up the node + every enabled bearer

// address someone by their base58 address, or by a peer you've discovered
hop.addContact("Ada", "3Qm…")
hop.send("ping", to = peer)                   // forward-secret, delivered when they're reachable
```

State is exposed as Compose-observable lists, so a UI reads it directly:

```kotlin
@Composable
fun Inbox(hop: HopBearer) {
    LazyColumn {
        items(hop.peers) { peer -> Text(peer.name) }
        // hop.messages, hop.secured, hop.linkTransports, hop.unread are observable too
    }
}
```

## What it owns

One `HopBearer` composes the whole stack so your app doesn't:

- **The node.** A `hop-core` node over the UniFFI bindings, funneled through a dedicated core thread
  because the node isn't thread-safe.
- **The bearers.** BLE (GATT + L2CAP + iBeacon wake), LAN (NSD + TCP), Wi-Fi Direct, and the cloud
  relay, driven together through the `BearerManager`.
- **Storage.** Chat history and contacts in `hop.db`, encrypted at rest via SQLCipher when `libhop`
  is built with it; the db key is wrapped by the Android Keystore (StrongBox where present).
- **The surface.** Text, image, and multipart send; contacts; HNS name resolution; the HPS
  channel/topic layer; and per-peer transport and delivery state, all Compose-observable.

## Send and receive

| You call                              | What happens                                              |
| ------------------------------------- | --------------------------------------------------------- |
| `hop.send(text, to)`                  | forward-secret text to a peer, queued until deliverable   |
| `hop.sendTo(addrBase58, text)`        | send by raw address, no prior contact                     |
| `hop.sendImage(data, to)` / `sendMultipart` | image and mixed text+image messages                |
| `hop.addContact(name, base58)`        | pin a base58 address under a name                         |
| `hop.messages` (observable)           | the live conversation, incoming and outgoing              |

Device-to-device content is always forward-secret (Double Ratchet); a send without a live session is a
bug, never a static seal.

## The relay is opt-in

`HopConfig.relaysEnabled` defaults to false (pure P2P over BLE / LAN / Wi-Fi Direct), so a
`START_STICKY` service restart never wakes the radio to dial a dead endpoint. A caller opts into the
cloud relay explicitly, and the driver still ANDs it with a persisted runtime killswitch so the fleet
can be turned off without an app update.

## Status

Prototype, and verified cross-platform: device-to-device delivery works Android to iOS on real hardware
with a crypto delivery ACK, and identities survive a reinstall. The app-facing logic runs headlessly
under Robolectric behind a `HopNodeInterface` / `FakeHopNode` seam, gated on an aggregate and a per-file
coverage floor; the device-bound layers (the BLE radio, StrongBox) are excluded from the denominator
and covered by the on-device workflow.

## The Hop family

Hop is one protocol with many faces. The endpoint SDKs, same surface in your language:
[node](https://github.com/hopmesh/hop-sdk-node) ·
[python](https://github.com/hopmesh/hop-sdk-python) ·
[go](https://github.com/hopmesh/hop-sdk-go) ·
[ruby](https://github.com/hopmesh/hop-sdk-ruby) ·
[crystal](https://github.com/hopmesh/hop-sdk-crystal) ·
[elixir](https://github.com/hopmesh/hop-sdk-elixir) ·
[apple](https://github.com/hopmesh/hop-sdk-apple) ·
[android](https://github.com/hopmesh/hop-sdk-android).
The protocol core is [hop-core](https://github.com/hopmesh/hop-core) / [libhop](https://github.com/hopmesh/libhop).

## License

[Apache-2.0](./LICENSE.md), use it freely. Only the protocol core (`hop-core`) is FSL-1.1-ALv2,
source-available and converting to Apache-2.0 after two years.
