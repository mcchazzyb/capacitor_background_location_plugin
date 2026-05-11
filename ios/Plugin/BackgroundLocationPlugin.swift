import Foundation
import Capacitor
import CoreLocation

/**
 * BackgroundLocationPlugin
 *
 * Owns a single CLLocationManager configured for continuous outdoor tracking.
 * On every fix, a throttled native URLSession POST upserts directly to a REST
 * endpoint — bypassing the WKWebView entirely so background data flow does not
 * depend on JS execution being alive.
 */
@objc(BackgroundLocationPlugin)
public class BackgroundLocationPlugin: CAPPlugin, CAPBridgedPlugin, CLLocationManagerDelegate {

    public let identifier = "BackgroundLocationPlugin"
    public let jsName = "BackgroundLocation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
    ]

    public override func load() {
        super.load()
        NSLog("⚡️ [BackgroundLocation] load() fired (jsName=%@)", jsName)
    }

    private var manager: CLLocationManager?
    private var pendingPermissionCall: CAPPluginCall?

    private var sessionId: String?
    private var participantType: String?
    private var postUrl: URL?
    private var apiKey: String?
    private var authorization: String?
    private var throttleMs: Double = 5000

    private var active: Bool = false
    private var lastPostAt: Date?
    private var lastFixAt: Date?
    private var lastWriteAt: Date?
    private var lastError: String?
    private var authFailedAt: Date?
    private let authBackoffSeconds: TimeInterval = 60

    private static func authStatusString(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "notDetermined"
        case .restricted:   return "restricted"
        case .denied:       return "denied"
        case .authorizedAlways: return "authorizedAlways"
        case .authorizedWhenInUse: return "authorizedWhenInUse"
        @unknown default: return "unknown(\(status.rawValue))"
        }
    }

    @objc func start(_ call: CAPPluginCall) {
        NSLog("⚡️ [BackgroundLocation] start() called")

        guard let sessionId = call.getString("sessionId"),
              let participantType = call.getString("participantType"),
              let postUrlString = call.getString("postUrl"),
              let postUrl = URL(string: postUrlString),
              let apiKey = call.getString("apiKey"),
              let authorization = call.getString("authorization") else {
            call.reject("Missing required start() options.")
            return
        }

        self.sessionId = sessionId
        self.participantType = participantType
        self.postUrl = postUrl
        self.apiKey = apiKey
        self.authorization = authorization
        self.throttleMs = call.getDouble("throttleMs") ?? 5000
        self.authFailedAt = nil

        DispatchQueue.main.async {
            let m = self.manager ?? CLLocationManager()
            m.delegate = self
            m.desiredAccuracy = kCLLocationAccuracyBest
            m.activityType = .otherNavigation
            m.pausesLocationUpdatesAutomatically = false
            m.distanceFilter = kCLDistanceFilterNone
            m.allowsBackgroundLocationUpdates = true
            if #available(iOS 11.0, *) {
                m.showsBackgroundLocationIndicator = true
            }
            self.manager = m

            let status: CLAuthorizationStatus
            if #available(iOS 14.0, *) {
                status = m.authorizationStatus
            } else {
                status = CLLocationManager.authorizationStatus()
            }
            NSLog("⚡️ [BackgroundLocation] auth status = %@", Self.authStatusString(status))

            switch status {
            case .notDetermined:
                self.pendingPermissionCall = call
                m.requestAlwaysAuthorization()

                DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                    guard let self = self, let pending = self.pendingPermissionCall else { return }
                    NSLog("⚡️ [BackgroundLocation] start() resolved via safety timer")
                    self.pendingPermissionCall = nil
                    m.startUpdatingLocation()
                    self.active = true
                    self.lastError = nil
                    pending.resolve()
                }
                return
            case .denied, .restricted:
                self.notifyListeners("permissionDenied", data: [:])
                call.reject("Location permission denied")
                return
            case .authorizedWhenInUse:
                m.requestAlwaysAuthorization()
                m.startUpdatingLocation()
            case .authorizedAlways:
                m.startUpdatingLocation()
            @unknown default:
                m.startUpdatingLocation()
            }

            self.active = true
            self.lastError = nil
            call.resolve()
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        NSLog("⚡️ [BackgroundLocation] stop() called")
        DispatchQueue.main.async {
            self.manager?.stopUpdatingLocation()
            self.active = false
            self.sessionId = nil
            self.participantType = nil
            self.postUrl = nil
            self.apiKey = nil
            self.authorization = nil
            self.lastPostAt = nil
            self.authFailedAt = nil
            call.resolve()
        }
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        call.resolve([
            "active": active,
            "sessionId": sessionId ?? NSNull(),
            "participantType": participantType ?? NSNull(),
            "lastFixAt": lastFixAt.map { Int($0.timeIntervalSince1970 * 1000) } ?? NSNull(),
            "lastWriteAt": lastWriteAt.map { Int($0.timeIntervalSince1970 * 1000) } ?? NSNull(),
            "lastError": lastError ?? NSNull(),
        ])
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        NSLog("⚡️ [BackgroundLocation] didChangeAuthorization = %@", Self.authStatusString(status))
        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            if let pending = pendingPermissionCall {
                pendingPermissionCall = nil
                manager.startUpdatingLocation()
                self.active = true
                self.lastError = nil
                pending.resolve()
            } else if active {
                manager.startUpdatingLocation()
            }
        case .denied, .restricted:
            notifyListeners("permissionDenied", data: [:])
            if let pending = pendingPermissionCall {
                pendingPermissionCall = nil
                pending.reject("Location permission denied")
            }
        default:
            break
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastFixAt = Date()
        let didWrite = postLocation(location)

        var data: [String: Any] = [
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "timestamp": Int(location.timestamp.timeIntervalSince1970 * 1000),
            "didWrite": didWrite,
        ]
        if location.course >= 0 {
            data["heading"] = location.course
        } else {
            data["heading"] = NSNull()
        }
        notifyListeners("fix", data: data)
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("⚡️ [BackgroundLocation] didFailWithError: %@", error.localizedDescription)
        lastError = error.localizedDescription
        notifyListeners("error", data: ["message": error.localizedDescription])
    }

    // MARK: - Native POST

    private func postLocation(_ location: CLLocation) -> Bool {
        guard active,
              let url = postUrl,
              let apiKey = apiKey,
              let auth = authorization,
              let sessionId = sessionId,
              let participantType = participantType else {
            return false
        }

        let now = Date()
        if let last = lastPostAt, now.timeIntervalSince(last) * 1000 < throttleMs {
            return false
        }

        if let failedAt = authFailedAt {
            if now.timeIntervalSince(failedAt) < authBackoffSeconds {
                return false
            }
        }
        lastPostAt = now

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(auth, forHTTPHeaderField: "Authorization")
        request.setValue(apiKey, forHTTPHeaderField: "apikey")
        request.setValue("resolution=merge-duplicates", forHTTPHeaderField: "Prefer")

        var body: [String: Any] = [
            "session_id": sessionId,
            "participant_type": participantType,
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "updated_at": ISO8601DateFormatter().string(from: now),
        ]
        if location.course >= 0 { body["heading"] = location.course }

        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let task = URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            guard let self = self else { return }
            if let error = error {
                self.lastError = error.localizedDescription
                self.notifyListeners("error", data: ["message": error.localizedDescription])
                return
            }
            if let http = response as? HTTPURLResponse {
                if http.statusCode == 401 {
                    self.authFailedAt = Date()
                    self.notifyListeners("authExpired", data: [:])
                    self.lastError = "auth expired"
                } else if http.statusCode >= 200 && http.statusCode < 300 {
                    self.lastWriteAt = Date()
                    self.lastError = nil
                    self.authFailedAt = nil
                } else {
                    let msg = "HTTP \(http.statusCode)"
                    self.lastError = msg
                    self.notifyListeners("error", data: ["message": msg])
                }
            }
        }
        task.resume()
        return true
    }
}
