import { registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export type ParticipantType = 'customer' | 'instructor';

export interface StartOptions {
  /** Unique session/booking identifier for this tracking session. */
  sessionId: string;
  participantType: ParticipantType;
  /** Full URL to POST location updates to. */
  postUrl: string;
  /** API key header value (sent as `apikey` header). */
  apiKey: string;
  /** Authorization header value (e.g. Bearer token). */
  authorization: string;
  /** Throttle between native POSTs in ms. Default 5000. */
  throttleMs?: number;
  /** iOS notification message shown in the system blue bar. */
  notificationMessage?: string;
  /** Android foreground notification title. */
  notificationTitle?: string;
}

export interface StatusResult {
  active: boolean;
  sessionId: string | null;
  participantType: ParticipantType | null;
  lastFixAt: number | null;
  lastWriteAt: number | null;
  lastError: string | null;
}

export interface BackgroundLocationPlugin {
  start(options: StartOptions): Promise<void>;
  stop(): Promise<void>;
  getStatus(): Promise<StatusResult>;
  /** Fired when the native POST gets 401; JS should refresh and call start() again. */
  addListener(
    event: 'authExpired',
    listener: () => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
  /** Fired on every fix received natively, so JS UI can update when foregrounded. */
  addListener(
    event: 'fix',
    listener: (data: {
      latitude: number;
      longitude: number;
      accuracy: number;
      heading: number | null;
      timestamp: number;
      didWrite: boolean;
    }) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
  /** Fired when permission is denied. */
  addListener(
    event: 'permissionDenied',
    listener: () => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
  removeAllListeners(): Promise<void>;
}

export const BackgroundLocation = registerPlugin<BackgroundLocationPlugin>(
  'BackgroundLocation',
);
