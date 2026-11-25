/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import XCTest
@testable import CapgoBackgroundGeolocationPlugin

/// Shyld (BL-092): the tracker's stops, the same cases as Android's ShyldFixFilterTest.
final class ShyldHandOffTests: XCTestCase {
    func testAStopFromTheServerStopsTheTrackerOnlyWhenNoShiftIsLeft() {
        XCTAssertTrue(ShyldHandOff.stopsNow(tracking: false))
        XCTAssertFalse(ShyldHandOff.stopsNow(tracking: true))
        XCTAssertFalse(ShyldHandOff.stopsNow(tracking: nil))
        // VALP-1233: the earlier shift's late 409 just after the next start doesn't stop it.
        let started = Date(timeIntervalSince1970: 1_000)
        XCTAssertFalse(ShyldHandOff.stopsNow(tracking: false, startedAt: started, now: started + 5))
        XCTAssertFalse(ShyldHandOff.stopsNow(tracking: false, startedAt: started, now: started + 119.999))
        XCTAssertTrue(ShyldHandOff.stopsNow(tracking: false, startedAt: started, now: started + 120))
        XCTAssertTrue(ShyldHandOff.stopsNow(tracking: false, startedAt: nil, now: started))
        XCTAssertFalse(ShyldHandOff.stopsNow(tracking: true, startedAt: nil, now: started))
        // The outbox posts this name (OutboxRules.trackerStopName).
        XCTAssertEqual(ShyldHandOff.stopName.rawValue, "ShyldTrackerStop")
    }

    func testATrackerNoShiftWantsStopsAfterTenMinutes() {
        let since = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertFalse(ShyldHandOff.stopsUntracked(tracking: false, untrackedSince: since, now: since + 599.999))
        XCTAssertTrue(ShyldHandOff.stopsUntracked(tracking: false, untrackedSince: since, now: since + 600))
        XCTAssertFalse(ShyldHandOff.stopsUntracked(tracking: true, untrackedSince: since, now: since + 3_600))
        XCTAssertFalse(ShyldHandOff.stopsUntracked(tracking: nil, untrackedSince: since, now: since + 3_600))
        XCTAssertFalse(ShyldHandOff.stopsUntracked(tracking: false, untrackedSince: nil, now: since + 3_600))
    }

    func testTheOutboxsStopWithNoTrackerRunningDoesNothing() {
        let plugin = BackgroundGeolocation()
        plugin.stopForOutbox(tracking: false)
        XCTAssertNil(plugin.lastError)
    }
}
