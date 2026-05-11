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

## Install

```bash
npm install capacitor-background-location
npx cap sync
```

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

No additional registration needed — the plugin uses `CAPBridgedPlugin` and is auto-discovered by Capacitor 8.

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
  // refresh token, then re-call start()
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

## How It Works

**iOS** — `CLLocationManager` with `allowsBackgroundLocationUpdates`. Each fix triggers a native `URLSession` POST, independent of the JS bridge.

**Android** — Foreground service with `FusedLocationProviderClient` for GPS and OkHttp for the POST. Survives app backgrounding.

**Auth expiry** — Both platforms detect 401 responses, fire `authExpired` to JS, and back off from POSTing until a fresh token is provided via `start()`.

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

> This section is for AI coding assistants (Claude, GPT, Lovable, Kimi, Cursor, etc.) implementing this plugin into a Capacitor app.

### Quick Context

- **What**: Native background location plugin for Capacitor 8 (iOS + Android)
- **Why native**: WebView JS is suspended when backgrounded — this plugin POSTs from native code directly
- **Peer dep**: `@capacitor/core` ^8.0.0
- **No web implementation**: This plugin is native-only. It will not work in `npx cap serve` or browser-only mode.

### Step-by-Step Integration

```
1. Install:
   npm install capacitor-background-location
   npx cap sync

2. iOS — add to Info.plist:
   - NSLocationAlwaysAndWhenInUseUsageDescription
   - NSLocationWhenInUseUsageDescription
   - UIBackgroundModes: ["location"]

3. Android — add to AndroidManifest.xml:
   - ACCESS_FINE_LOCATION
   - ACCESS_COARSE_LOCATION
   - FOREGROUND_SERVICE
   - FOREGROUND_SERVICE_LOCATION
   - ACCESS_BACKGROUND_LOCATION (Android 10+)

4. Import and use:
   import { BackgroundLocation } from 'capacitor-background-location';

5. Call start() with required options:
   - sessionId: string (your unique tracking session ID)
   - participantType: 'customer' | 'instructor'
   - postUrl: string (your REST endpoint URL)
   - apiKey: string (sent as 'apikey' header)
   - authorization: string (sent as 'Authorization' header)

6. Listen for events:
   - 'fix': location update received (includes didWrite boolean)
   - 'authExpired': 401 received, refresh token and call start() again
   - 'permissionDenied': user denied location permission

7. Call stop() to end tracking.
```

### Common Patterns

**Starting tracking after login:**
```typescript
import { BackgroundLocation } from 'capacitor-background-location';

async function startTracking(session: { id: string; role: 'customer' | 'instructor' }, token: string) {
  await BackgroundLocation.start({
    sessionId: session.id,
    participantType: session.role,
    postUrl: `${API_BASE}/rest/v1/live_locations`,
    apiKey: SUPABASE_ANON_KEY,
    authorization: `Bearer ${token}`,
    throttleMs: 5000,
  });
}
```

**Handling auth refresh:**
```typescript
BackgroundLocation.addListener('authExpired', async () => {
  const { token } = await refreshAuthToken();
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

### Important Notes for AI Assistants

1. **Do not wrap in try/catch silently** — surface errors to the user, especially permission denials.
2. **Always listen for `authExpired`** — without this handler, location POSTs will silently stop after token expiry.
3. **Call `stop()` explicitly** — the service persists across app restarts on Android if not stopped.
4. **`postUrl` must be a full URL** — not a relative path. Include protocol and host.
5. **The plugin handles permissions internally** — do not request location permission separately. The plugin will prompt and emit `permissionDenied` if denied.
6. **`throttleMs` controls POST frequency, not GPS frequency** — GPS fires every ~5s regardless. The throttle only gates network writes.
7. **No web fallback** — guard usage with platform checks if your app also runs in browser:
   ```typescript
   import { Capacitor } from '@capacitor/core';
   if (Capacitor.isNativePlatform()) {
     await BackgroundLocation.start({ ... });
   }
   ```
8. **The `Prefer: resolution=merge-duplicates` header** is sent on every POST. This is designed for PostgREST/Supabase upsert but is harmless on other backends.
9. **POST payload uses snake_case** (`session_id`, `participant_type`, `updated_at`) regardless of the camelCase JS interface.
