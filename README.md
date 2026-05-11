# capacitor-background-location

A Capacitor 8 plugin for **continuous background location tracking** with **native HTTP POST** on iOS and Android. Location updates are sent directly from native code to your REST endpoint — no WebView or JS execution required while backgrounded.

## Features

- Continuous GPS tracking in the background (iOS + Android)
- Native HTTP POST of location data on every fix (throttled)
- Foreground service with persistent notification (Android)
- Background location indicator — blue bar (iOS)
- Automatic 401 detection with `authExpired` event for token refresh
- Configurable throttle interval
- Permission handling with `permissionDenied` event
- Real-time `fix` events forwarded to JS when the app is foregrounded

## Status

| Platform | Tested |
|----------|--------|
| iOS      | ✅ Yes  |
| Android  | ⚠️ Not yet — likely requires verification on Android 14+ (see [Android caveats](#android-caveats)) |

*Last updated: 11/5/26*

## Install

```bash
npm install capacitor-background-location
npx cap sync
```

## Gotchas Before You Start

Two things will silently hang your app if you get them wrong:

1. **Never `await` the plugin registration.** `registerPlugin()` returns a Proxy with a `then` getter — the microtask scheduler treats it as a thenable and waits forever.

```typescript
// ✅ Correct — synchronous, top-level module scope
import { registerPlugin } from '@capacitor/core';
const BackgroundLocation = registerPlugin<BackgroundLocationPlugin>('BackgroundLocation');
export { BackgroundLocation };

// ❌ WRONG — hangs forever
const BackgroundLocation = await import('@capacitor/core').then(c =>
  c.registerPlugin('BackgroundLocation')
);
```

2. **Never call `start()` before your auth session is confirmed ready.** If your auth client restores tokens from storage asynchronously, `getSession()` may never resolve on cold start. This is the most common cause of the plugin appearing to "hang on start" — it's the auth call that never resolves, not the plugin. See [Auth Session Timing](#auth-session-timing).

## iOS Setup

Add to your app's `Info.plist`:

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>We need your location to share your live position during sessions.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>We need your location to share your live position during sessions.</string>
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

### Plugin Registration (iOS)

Auto-discovery does **not** reliably work for this plugin. You must manually register it via a `CAPBridgeViewController` subclass:

1. Create a subclass (e.g. `AppBridgeViewController.swift`):

```swift
import UIKit
import Capacitor

class AppBridgeViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(BackgroundLocationPlugin())
    }
}
```

2. Set this as your root view controller in `AppDelegate.swift` or your storyboard.

This applies whether you install via npm or vendor the source in-repo.

### iOS Notes

- `showsBackgroundLocationIndicator = true` is set by the plugin. This is **required** for App Store review when using Always authorization.
- `pausesLocationUpdatesAutomatically = false` — the plugin disables auto-pause so tracking continues even when stationary (e.g. chairlift, waiting).
- `distanceFilter = kCLDistanceFilterNone` — every fix is emitted regardless of movement.
- `activityType = .otherNavigation` — prevents iOS from aggressively pausing updates.

Without these settings, iOS will pause location updates and users will report "tracking stopped".

## Android Setup

Add these permissions to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<!-- Android 10+ background location -->
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

The plugin's `AndroidManifest.xml` already declares the foreground service component — it merges automatically during build.

### Android Caveats

> ⚠️ Android is not yet tested in production. The following are known requirements that may need verification:

- **Android 14+**: `FOREGROUND_SERVICE_LOCATION` requires the service type to be declared in the manifest AND the service must call `startForeground()` within 10 seconds of `startForegroundService()` or the system kills it. The plugin does this, but edge cases (slow permission prompts) may need testing.
- The foreground service type `android:foregroundServiceType="location"` is declared in the plugin manifest.

## Usage

```typescript
import { BackgroundLocation } from 'capacitor-background-location';

// Start tracking
await BackgroundLocation.start({
  sessionId: 'abc-123',
  participantType: 'customer',
  postUrl: 'https://your-api.example.com/rest/v1/locations',
  apiKey: 'your-api-key',
  authorization: 'Bearer your-jwt-token',
  throttleMs: 5000,
  notificationTitle: 'Live Location',
  notificationMessage: 'Sharing your location',
});

// Listen for GPS fixes (useful for UI updates when foregrounded)
BackgroundLocation.addListener('fix', (data) => {
  // { latitude, longitude, accuracy, heading, timestamp, didWrite }
});

// Handle token expiry — refresh and call start() again
BackgroundLocation.addListener('authExpired', () => {
  // refresh token, then re-call start() with fresh authorization
});

// Handle permission denial
BackgroundLocation.addListener('permissionDenied', () => {
  // show explanation UI
});

// Stop tracking
await BackgroundLocation.stop();

// Check current state
const status = await BackgroundLocation.getStatus();
```

## Auth Expiry & 401 Backoff

When the native POST receives a 401:

1. The `authExpired` event fires to JS
2. **POSTs are suppressed for 60 seconds** to avoid hammering your backend
3. GPS fixes continue to be collected — they just aren't POSTed
4. **You must call `start()` again with a fresh `authorization` value** to clear the backoff and resume POSTing

Simply refreshing the token in JS is not enough — the native layer holds the old token. Calling `start()` with the new token clears the backoff immediately and resumes POSTing.

## Auth Session Timing

If your auth client restores sessions from storage asynchronously (common with token-based auth libraries), `getSession()` may hang on cold start before the session is restored.

Either:

1. **Wait for your auth state listener to confirm the session is ready** before calling `start()`, or
2. **Race the session call with a timeout**:

```typescript
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms)
    ),
  ]);
}

const session = await withTimeout(auth.getSession(), 5000, 'getSession');
```

Use this `withTimeout` pattern for any awaited call in your start flow — silent hangs are extremely hard to debug on mobile.

## Upsert / Duplicate Handling

The plugin sends a `Prefer: resolution=merge-duplicates` header on every POST. This is designed for REST APIs that support upsert semantics (e.g. PostgREST-compatible backends).

If your backend supports this, ensure your target table has a **unique constraint** on `(session_id, participant_type)` — otherwise upserts may fail silently with 409.

If your backend doesn't use this header, it's harmless and will be ignored.

## Best Practices

### Singleton Ownership Pattern

Own the location watcher in a **module-level singleton**, not inside a React/Vue component. React effect cleanups will tear down background tracking on navigation.

```typescript
// location-service.ts — singleton, outside component tree
import { BackgroundLocation } from 'capacitor-background-location';

let isTracking = false;

export async function startTracking(opts: StartOptions) {
  if (isTracking) return;
  await BackgroundLocation.start(opts);
  isTracking = true;
}

export async function stopTracking() {
  if (!isTracking) return;
  await BackgroundLocation.stop();
  await BackgroundLocation.removeAllListeners();
  isTracking = false;
}
```

### Consent Persistence

The plugin does not persist consent state. Store per-session consent in `localStorage` (not cookies — important for GDPR) and re-call `start()` on app resume:

```typescript
// On consent granted
localStorage.setItem('location_consent', JSON.stringify({ sessionId, grantedAt: Date.now() }));

// On app resume (e.g. Capacitor App.addListener('appStateChange'))
const consent = JSON.parse(localStorage.getItem('location_consent') || 'null');
if (consent && consent.sessionId === currentSessionId) {
  await startTracking({ ... });
}
```

## Distribution & Linking

### Via npm

```bash
npm install capacitor-background-location
npx cap sync
```

On **Android**, the `@CapacitorPlugin` annotation handles auto-discovery. On **iOS**, you still need the manual `registerPluginInstance()` step documented in [Plugin Registration (iOS)](#plugin-registration-ios).

### Vendored in-repo

If you vendor the plugin source directly into your project (e.g. under `native-plugins/`):

- **iOS**: Add a `.podspec` or SPM `Package.swift` entry that links the Swift source into your app target, plus the `registerPluginInstance()` bridge subclass
- **Android**: Add the module to `settings.gradle` and reference it as a project dependency

## StartOptions

| Property              | Type   | Required | Default                         | Description                                 |
|-----------------------|--------|----------|---------------------------------|---------------------------------------------|
| `sessionId`           | string | ✓        |                                 | Unique identifier for this tracking session |
| `participantType`     | string | ✓        |                                 | `'customer'` or `'instructor'`              |
| `postUrl`             | string | ✓        |                                 | Full URL to POST location updates to        |
| `apiKey`              | string | ✓        |                                 | Sent as `apikey` header                     |
| `authorization`       | string | ✓        |                                 | Sent as `Authorization` header              |
| `throttleMs`          | number |          | `5000`                          | Minimum ms between POSTs                    |
| `notificationTitle`   | string |          | `"Live Location"`               | Android foreground notification title       |
| `notificationMessage` | string |          | `"Sharing your live location."` | Notification body / iOS blue bar text       |

## StatusResult

| Property          | Type           | Description                               |
|-------------------|----------------|-------------------------------------------|
| `active`          | boolean        | Whether tracking is currently running     |
| `sessionId`       | string \| null | Current session ID                        |
| `participantType` | string \| null | Current participant type                  |
| `lastFixAt`       | number \| null | Unix ms timestamp of last GPS fix         |
| `lastWriteAt`     | number \| null | Unix ms timestamp of last successful POST |
| `lastError`       | string \| null | Last error message, or null if healthy    |

## POST Payload

```json
{
  "session_id": "abc-123",
  "participant_type": "customer",
  "latitude": 47.6062,
  "longitude": -122.3321,
  "accuracy": 5.0,
  "heading": 180.0,
  "updated_at": "2025-01-15T10:30:00.000Z"
}
```

Headers: `Content-Type: application/json`, `apikey`, `Authorization`, `Prefer: resolution=merge-duplicates`

## Customisation

| What                    | Where to change                                                                 |
|-------------------------|---------------------------------------------------------------------------------|
| POST payload shape      | `postLocation()` in `LiveLocationForegroundService.kt` and `BackgroundLocationPlugin.swift` |
| HTTP headers            | Same files, request builder section                                             |
| Android package name    | `namespace` in `android/build.gradle` + `package` in Kotlin files               |
| Additional start params | `StartOptions` in `src/index.ts` + both native `start()` methods                |

## License

MIT

---

## LLM Integration Guide

> This section is for AI coding assistants (Claude, GPT, Lovable, Kimi, Cursor, etc.) implementing this plugin into a Capacitor 8 app.

### Critical Rules

1. **NEVER `await` the plugin registration.** `registerPlugin()` returns a Proxy with a `then` getter — awaiting it hangs forever. Register synchronously at module top-level.
2. **NEVER call `start()` before auth is ready.** Wait for your auth state listener to confirm the session, or race `getSession()` with a 5s timeout. This is the most common cause of the plugin appearing to "hang" — it's actually the auth call that never resolves, not the plugin.
3. **iOS requires manual plugin registration.** Auto-discovery is unreliable. Use a `CAPBridgeViewController` subclass with `bridge?.registerPluginInstance(BackgroundLocationPlugin())` in `capacitorDidLoad()`. This applies for both npm installs and vendored source.
4. **ALWAYS call `start()` again after `authExpired`** — refreshing the token in JS is not enough. The native layer holds the old token string. Calling `start()` with the new token clears the 60s backoff and resumes POSTing immediately.
5. **ALWAYS own the watcher in a module-level singleton** — not inside a React/Vue component. Effect cleanups will kill background tracking on navigation.
6. **ALWAYS guard with `Capacitor.isNativePlatform()`** — this plugin has no web implementation.

### Quick Context

- **What**: Native background location plugin for Capacitor 8 (iOS + Android)
- **Why native**: WebView JS is suspended when backgrounded — this plugin POSTs from native code directly
- **Peer dep**: `@capacitor/core` ^8.0.0
- **No web implementation**: Will not work in `npx cap serve` or browser-only mode
- **iOS**: Tested and working
- **Android**: Not yet tested — likely works but may have edge cases on Android 14+

### Step-by-Step Integration

```
1. Install:
   npm install capacitor-background-location
   npx cap sync

2. Register plugin in JS (MUST be synchronous, top-level):
   import { registerPlugin } from '@capacitor/core';
   export const BackgroundLocation = registerPlugin('BackgroundLocation');

   OR use the package export:
   import { BackgroundLocation } from 'capacitor-background-location';

3. iOS — add to Info.plist:
   - NSLocationAlwaysAndWhenInUseUsageDescription (string)
   - NSLocationWhenInUseUsageDescription (string)
   - UIBackgroundModes: array containing "location"

4. iOS — register plugin natively (required, auto-discovery is unreliable):
   Create a CAPBridgeViewController subclass:
     class AppBridgeViewController: CAPBridgeViewController {
       override open func capacitorDidLoad() {
         bridge?.registerPluginInstance(BackgroundLocationPlugin())
       }
     }
   Set it as your root view controller.

5. Android — add to AndroidManifest.xml:
   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
   <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
   <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

6. Create a singleton service file (NOT inside a component):
   // src/services/location-service.ts
   import { BackgroundLocation } from 'capacitor-background-location';
   import { Capacitor } from '@capacitor/core';

   export async function startTracking(opts) {
     if (!Capacitor.isNativePlatform()) return;
     await BackgroundLocation.start(opts);
   }
   export async function stopTracking() {
     if (!Capacitor.isNativePlatform()) return;
     await BackgroundLocation.stop();
     await BackgroundLocation.removeAllListeners();
   }

7. Call start() ONLY after auth is confirmed ready:
   - Wait for your auth state change listener to fire, OR
   - Race getSession() with a 5-second timeout helper

8. Listen for events:
   - 'fix': location update (includes didWrite boolean)
   - 'authExpired': 401 received — MUST call start() again with fresh token
   - 'permissionDenied': user denied location permission

9. Call stop() on logout/session end.

10. Store consent in localStorage (not cookies) for GDPR.
    Re-call start() on app resume if consent is still valid.
```

### Timeout Helper (recommended for all awaited calls)

```typescript
function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return Promise.race([
    promise,
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms)
    ),
  ]);
}
```

### Common Patterns

**Starting tracking after auth confirmed:**
```typescript
import { BackgroundLocation } from 'capacitor-background-location';

async function startTracking(session: { id: string; role: 'customer' | 'instructor' }, token: string) {
  await BackgroundLocation.start({
    sessionId: session.id,
    participantType: session.role,
    postUrl: `${API_BASE}/rest/v1/live_locations`,
    apiKey: API_KEY,
    authorization: `Bearer ${token}`,
    throttleMs: 5000,
  });
}
```

**Handling auth refresh:**
```typescript
BackgroundLocation.addListener('authExpired', async () => {
  const { token } = await refreshAuthToken();
  // MUST call start() again — native layer holds the old token
  await BackgroundLocation.start({
    sessionId: currentSessionId,
    participantType: currentRole,
    postUrl: POST_URL,
    apiKey: API_KEY,
    authorization: `Bearer ${token}`,
  });
});
```

**Cleanup on logout/session end:**
```typescript
async function endSession() {
  await BackgroundLocation.stop();
  await BackgroundLocation.removeAllListeners();
}
```

### Important Implementation Notes

1. **Do not wrap in try/catch silently** — surface errors to the user, especially permission denials.
2. **`postUrl` must be a full URL** — not a relative path. Include protocol and host.
3. **The plugin handles permissions internally** — do not request location permission separately.
4. **`throttleMs` controls POST frequency, not GPS frequency** — GPS fires every ~5s regardless. The throttle only gates network writes.
5. **POST payload uses snake_case** (`session_id`, `participant_type`, `updated_at`) regardless of the camelCase JS interface.
6. **The `Prefer: resolution=merge-duplicates` header** is sent on every POST. Designed for PostgREST-compatible upsert APIs, harmless on other backends. If your backend uses it, ensure a unique constraint on `(session_id, participant_type)`.
7. **iOS `showsBackgroundLocationIndicator = true`** is set — required for App Store review with Always authorization.
8. **Vendored plugins** (not installed via npm) need manual linking via `.podspec` (iOS) or `settings.gradle` (Android), plus the `registerPluginInstance()` bridge subclass on iOS.
9. **401 backoff**: After a 401, native POSTs are suppressed for 60s. Calling `start()` with a fresh token clears the backoff immediately.
