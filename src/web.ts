/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import { WebPlugin } from "@capacitor/core";

import type {
  AddGeofenceOptions,
  BackgroundGeolocationPermissionStatus,
  BackgroundGeolocationPlugin,
  CallbackError,
  GeofenceSetupOptions,
  GeofenceTransitionEvent,
  Location,
  LocationLogResult,
  MonitoredGeofencesResult,
  RemoveGeofenceOptions,
  SetPlannedRouteOptions,
  StartOptions,
  UpdateHeadersOptions,
} from "./definitions";
// Shyld: the route geometry moved to its own file (FORK.md).
import { distancePointToRoute, haversine } from "./route-geometry";

interface WebGeofence {
  latitude: number;
  longitude: number;
  radius: number;
  identifier: string;
  notifyOnEntry: boolean;
  notifyOnExit: boolean;
  payload?: Record<string, unknown>;
  inside?: boolean;
}

export class BackgroundGeolocationWeb extends WebPlugin implements BackgroundGeolocationPlugin {
  private static readonly EARTH_RADIUS_M = 6371000;

  private watchId: number | undefined;
  private geofenceWatchId: number | undefined;
  private plannedRoute: [number, number][] = [];
  private audio: HTMLAudioElement | undefined;
  private isOffRoute = true;
  private distanceThreshold = 50;
  private geofences = new Map<string, WebGeofence>();
  private geofenceUrl: string | undefined;
  private geofenceHeaders: Record<string, string> = {};
  private geofencePayload: Record<string, unknown> = {};
  private notifyOnEntry = true;
  private notifyOnExit = true;

  async start(
    options: StartOptions,
    callback: (position?: Location, error?: CallbackError) => void,
  ): Promise<void> {
    if (!navigator.geolocation) {
      callback(undefined, {
        name: "GeolocationError",
        message: "Geolocation is not supported by this browser",
        code: "NOT_SUPPORTED",
      });
      return;
    }

    if (this.watchId) {
      callback(undefined, {
        name: "GeolocationError",
        message: "Geolocation already started",
        code: "ALREADY_STARTED",
      });
      return;
    }

    this.watchId = navigator.geolocation.watchPosition(
      (position) => {
        const location: Location = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          altitude: position.coords.altitude,
          altitudeAccuracy: position.coords.altitudeAccuracy,
          simulated: false,
          bearing: position.coords.heading,
          speed: position.coords.speed,
          time: position.timestamp,
        };
        if (this.audio && this.plannedRoute.length > 0) {
          const currentPoint: [number, number] = [position.coords.longitude, position.coords.latitude];
          const offRoute = distancePointToRoute(currentPoint, this.plannedRoute) > this.distanceThreshold;
          if (offRoute === true && this.isOffRoute === false) {
            this.audio.play();
          }
          this.isOffRoute = offRoute;
        }
        this.checkGeofences(position.coords.latitude, position.coords.longitude, position.timestamp);
        callback(location);
      },
      (error) => {
        const callbackError: CallbackError = {
          name: "GeolocationError",
          message: error.message,
          code: error.code.toString(),
        };
        callback(undefined, callbackError);
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: options.stale ? 300000 : 0,
      },
    );
  }

  async stop(): Promise<void> {
    if (this.watchId) {
      navigator.geolocation.clearWatch(this.watchId);
      delete this.watchId;
    }
  }

  async openSettings(): Promise<void> {
    console.log("openSettings: Web implementation cannot open native settings");
    window.alert("Please enable location permissions in your browser settings");
  }

  async setPlannedRoute(options: SetPlannedRouteOptions): Promise<void> {
    if (!options.soundFile) {
      throw new Error("Sound file is required");
    }
    if (this.audio) {
      this.audio.pause();
      this.audio.src = "";
      this.audio = undefined;
    }
    this.audio = new Audio(options.soundFile);
    this.plannedRoute = options.route || [];
    this.distanceThreshold = options.distance || 50;
  }

  async setupGeofencing(options: GeofenceSetupOptions): Promise<void> {
    if (options.url) {
      new URL(options.url);
    }
    this.geofenceUrl = options.url;
    this.geofenceHeaders = { ...(options.headers ?? {}) };
    this.notifyOnEntry = options.notifyOnEntry ?? true;
    this.notifyOnExit = options.notifyOnExit ?? true;
    this.geofencePayload = options.payload ?? {};
  }

  async updateHeaders(options: UpdateHeadersOptions): Promise<void> {
    this.geofenceHeaders = { ...(options.headers ?? {}) };
  }

  /** Resolves at once: the web implementation POSTs each transition immediately, with no queue. */
  async clearGeofenceQueue(): Promise<void> {
    return;
  }

  async addGeofence(options: AddGeofenceOptions): Promise<void> {
    if (!navigator.geolocation) {
      throw new Error("Geolocation is not supported by this browser");
    }
    this.validateGeofence(options.latitude, options.longitude, options.radius ?? 50, options.identifier);
    this.geofences.set(options.identifier, {
      latitude: options.latitude,
      longitude: options.longitude,
      radius: options.radius ?? 50,
      identifier: options.identifier,
      notifyOnEntry: options.notifyOnEntry ?? this.notifyOnEntry,
      notifyOnExit: options.notifyOnExit ?? this.notifyOnExit,
      payload: options.payload,
    });
    this.startGeofenceWatch();
  }

  async removeGeofence(options: RemoveGeofenceOptions): Promise<void> {
    if (!options.identifier) {
      throw new Error("Identifier is required");
    }
    this.geofences.delete(options.identifier);
    this.stopGeofenceWatchIfIdle();
  }

  async removeAllGeofences(): Promise<void> {
    this.geofences.clear();
    this.stopGeofenceWatchIfIdle();
  }

  async getMonitoredGeofences(): Promise<MonitoredGeofencesResult> {
    return { regions: Array.from(this.geofences.keys()) };
  }

  async getLocationLog(): Promise<LocationLogResult> {
    return { entries: [] };
  }

  async clearLocationLog(): Promise<void> {
    return;
  }

  async checkPermissions(): Promise<BackgroundGeolocationPermissionStatus> {
    if (!navigator.permissions) {
      return {
        location: "prompt",
        backgroundLocation: "prompt",
        notification: "granted",
      };
    }

    try {
      const status = await navigator.permissions.query({ name: "geolocation" as PermissionName });
      const location =
        status.state === "granted" ? "granted" : status.state === "denied" ? "denied" : "prompt";
      return {
        location,
        backgroundLocation: location,
        notification: "granted",
      };
    } catch {
      return {
        location: "prompt",
        backgroundLocation: "prompt",
        notification: "granted",
      };
    }
  }

  async requestPermissions(): Promise<BackgroundGeolocationPermissionStatus> {
    return this.checkPermissions();
  }

  private validateGeofence(latitude: number, longitude: number, radius: number, identifier: string): void {
    if (!identifier) {
      throw new Error("Identifier is required");
    }
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
      throw new Error("Latitude must be between -90 and 90");
    }
    if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
      throw new Error("Longitude must be between -180 and 180");
    }
    if (!Number.isFinite(radius) || radius <= 0) {
      throw new Error("Radius must be greater than 0");
    }
  }

  private startGeofenceWatch(): void {
    if (this.geofenceWatchId !== undefined || this.geofences.size === 0 || !navigator.geolocation) {
      return;
    }
    this.geofenceWatchId = navigator.geolocation.watchPosition(
      (position) =>
        this.checkGeofences(position.coords.latitude, position.coords.longitude, position.timestamp),
      () => undefined,
      {
        enableHighAccuracy: false,
        timeout: 30000,
        maximumAge: 60000,
      },
    );
  }

  private stopGeofenceWatchIfIdle(): void {
    if (this.geofences.size > 0 || this.geofenceWatchId === undefined) {
      return;
    }
    navigator.geolocation.clearWatch(this.geofenceWatchId);
    this.geofenceWatchId = undefined;
  }

  private checkGeofences(latitude: number, longitude: number, time: number): void {
    const point: [number, number] = [longitude, latitude];
    this.geofences.forEach((geofence) => {
      const distance = haversine(point, [geofence.longitude, geofence.latitude]);
      const inside = distance <= geofence.radius;
      const previousInside = geofence.inside;
      geofence.inside = inside;

      if (inside && previousInside !== true && geofence.notifyOnEntry) {
        this.emitGeofenceTransition(geofence, true, time);
      } else if (!inside && previousInside === true && geofence.notifyOnExit) {
        this.emitGeofenceTransition(geofence, false, time);
      }
    });
  }

  private emitGeofenceTransition(geofence: WebGeofence, enter: boolean, time: number): void {
    const payload = {
      ...this.geofencePayload,
      ...(geofence.payload ?? {}),
    };
    const event: GeofenceTransitionEvent = {
      ...payload,
      identifier: geofence.identifier,
      transition: enter ? "enter" : "exit",
      enter,
      latitude: geofence.latitude,
      longitude: geofence.longitude,
      radius: geofence.radius,
      payload,
      time,
    };
    void this.notifyListeners("geofenceTransition", event);
    if (this.geofenceUrl) {
      void fetch(this.geofenceUrl, {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          ...this.geofenceHeaders,
        },
        body: JSON.stringify(event),
      }).catch(() => undefined);
    }
  }

  async getPluginVersion(): Promise<{ version: string }> {
    return { version: "web" };
  }
}
