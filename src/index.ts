/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
// Shyld: `definitions` is types only, kept as a .d.ts (FORK.md).
import { registerPlugin } from "@capacitor/core";

import type { BackgroundGeolocationPlugin } from "./definitions";

const BackgroundGeolocation = registerPlugin<BackgroundGeolocationPlugin>("BackgroundGeolocation", {
  web: () => import("./web").then((m) => new m.BackgroundGeolocationWeb()),
});

export type * from "./definitions";
export { BackgroundGeolocation };
