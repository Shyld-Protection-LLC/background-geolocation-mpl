/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
import Foundation

protocol LocationLogStorage: AnyObject {
    func append(_ location: [String: Any], maxEntries: Int) throws
    func entries(afterId: Int64, limit: Int) throws -> [[String: Any]]
    func clear(upToId: Int64?) throws
    func insert(_ location: [String: Any], maxEntries: Int) -> Int64?
    func complete(id: Int64, httpStatus: Int?, error: Error?)
    var tracksPostOutcomes: Bool { get }
    func open()
}

extension LocationLogStorage {
    func insert(_ location: [String: Any], maxEntries: Int) -> Int64? { nil }
    func complete(id: Int64, httpStatus: Int?, error: Error?) {}
    var tracksPostOutcomes: Bool { false }
    func open() {}
}
