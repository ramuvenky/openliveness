import SwiftUI

/// Dev harness entry point for the openliveness iOS SDK. The Android
/// counterpart is `MainActivity` in android-sample; the shape is
/// deliberately parallel so a reviewer flipping between the two can
/// confirm feature parity.
///
/// This is NOT a shippable app. The red "DEV HARNESS" banner stays
/// visible for the lifetime of ContentView so a screenshot of a running
/// build cannot be mistaken for a production integration.
@main
struct OpenLivenessSampleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
