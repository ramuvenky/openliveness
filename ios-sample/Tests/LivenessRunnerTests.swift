import XCTest
@testable import OpenLivenessSample

/// Covers the review fixes on LivenessRunner:
///   - M-2 enabler: `promptLabel(for:)` is now `static` + internal so it
///     can be exercised without instantiating the whole runner.
///   - H-5: initial `cameraStatus` is `.initial`, then transitions as
///     `prepareCamera()` runs. We only pin the initial-state invariant
///     here because the authorization-status transitions touch
///     AVCaptureDevice internals that aren't mockable in a plain unit
///     test; UI-tests would be the right vehicle for those.
///   - H-6: `cancel()` resets `isRunning` and `prompt` even when no
///     actual Task is in flight (idempotent / safe-from-onDisappear).
@MainActor
final class LivenessRunnerTests: XCTestCase {

    // MARK: - M-2 enabler

    func test_promptLabel_mapsKnownWireValues() {
        // Mirror of android-sample MainActivity.promptLabel's test
        // intent — the string map here IS the contract between SDK
        // wire values and the on-screen instruction. If any of these
        // drift, the dev harness shows the wrong instruction and a
        // human-visible UX bug slips through.
        XCTAssertEqual(LivenessRunner.promptLabel(for: "blink"), "Blink now")
        XCTAssertEqual(LivenessRunner.promptLabel(for: "look_left"), "Look left")
        XCTAssertEqual(LivenessRunner.promptLabel(for: "look_right"), "Look right")
        XCTAssertEqual(LivenessRunner.promptLabel(for: "smile"), "Smile")
        XCTAssertEqual(LivenessRunner.promptLabel(for: "nod"), "Nod")
    }

    func test_promptLabel_nilRendersEmpty() {
        // Nil input means "no current prompt" — must render empty, not
        // the word "nil" or "optional(…)". The view binds this to a
        // Text() so a stray debug-print representation would be visible
        // on screen.
        XCTAssertEqual(LivenessRunner.promptLabel(for: nil), "")
    }

    func test_promptLabel_unknownWireRendersVerbatim() {
        // Deliberate design: unknown values pass through so a dev using
        // a new sequence sees the raw string rather than a blank prompt.
        // This is a diagnostic signal — "the SDK is out of sync with
        // the relay enum" — and must be visible, not hidden.
        XCTAssertEqual(LivenessRunner.promptLabel(for: "wink"), "wink")
    }

    // MARK: - H-5 regression pin

    func test_cameraStatus_isInitialAtConstruction() {
        // Guards against a well-meaning refactor that eagerly resolves
        // the authorization status in the initializer — doing so would
        // trip the Info.plist usage-description prompt before the view
        // appears, and would also break the `.task { prepareCamera() }`
        // ordering the view depends on.
        let runner = LivenessRunner()
        XCTAssertEqual(runner.cameraStatus, .initial)
    }

    // MARK: - H-6 regression pin

    func test_cancel_isIdempotentWhenNotRunning() {
        // The view's `.onDisappear { runner.cancel() }` fires on every
        // scene teardown, including ones that never started a session.
        // Cancel must be safe to call unconditionally — no crash, no
        // spurious "Cancelled" status when nothing was running.
        let runner = LivenessRunner()
        let initialStatus = runner.status
        runner.cancel()
        XCTAssertFalse(runner.isRunning)
        XCTAssertEqual(
            runner.status, initialStatus,
            "cancel() must not overwrite status when there was nothing to cancel — otherwise onDisappear clobbers 'Ready. Paste QR payload.'"
        )
    }

    func test_cancel_resetsStateWhenRunning() {
        // The in-flight case: a session was running (isRunning=true,
        // prompt set), scene disappears, cancel() must flip isRunning
        // off and clear the prompt. Setting state directly rather than
        // kicking off a real session avoids touching the network — the
        // runTask handling is covered by the behavior that `cancel`
        // takes effect on the state the view observes.
        let runner = LivenessRunner()
        runner.isRunning = true
        runner.prompt = "Blink now"
        runner.cancel()
        XCTAssertFalse(runner.isRunning, "cancel() must flip isRunning off")
        XCTAssertEqual(runner.prompt, "", "cancel() must clear the stale prompt")
        XCTAssertEqual(runner.status, "Cancelled", "cancel() must advertise the cancellation to the user")
    }
}
