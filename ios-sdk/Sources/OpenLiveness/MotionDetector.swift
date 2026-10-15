import Foundation
#if canImport(CoreMotion)
import CoreMotion
#endif

/// Reports `motion_detected` for the attestation's ambient_conditions.
/// Threshold: accelerometer magnitude deviates > 0.3g from the 1g baseline.
public final class MotionDetector {

    #if canImport(CoreMotion)
    private let motionManager = CMMotionManager()
    #endif
    public private(set) var motionDetected = false

    public init() {}

    public func start() {
        #if canImport(CoreMotion)
        guard motionManager.isAccelerometerAvailable else { return }
        motionManager.accelerometerUpdateInterval = 0.1
        motionManager.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self = self, let data = data else { return }
            let a = data.acceleration
            let magnitude = (a.x * a.x + a.y * a.y + a.z * a.z).squareRoot()
            self.motionDetected = abs(magnitude - 1.0) > 0.3
        }
        #endif
    }

    public func stop() {
        #if canImport(CoreMotion)
        motionManager.stopAccelerometerUpdates()
        #endif
    }
}
