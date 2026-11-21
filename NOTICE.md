# NOTICE

Vivarium. Keeps single-file HTML tools on Android and runs each one in its own sandbox.

Copyright (C) 2026 Fox

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU Affero General Public License, version 3 or (at your
option) any later version, as published by the Free Software Foundation. The
full license text is in `LICENSE` (also at
<https://www.gnu.org/licenses/agpl-3.0.html>).

---

## Third-party components

### JetBrains Mono

Bundled as `vivarium/jbm.woff2` (Latin subset, 100 to 800 weight axis) so
tools that ask for it get it offline.

Copyright 2020 The JetBrains Mono Project Authors
(https://github.com/JetBrains/JetBrainsMono). Licensed under the SIL Open Font
License, Version 1.1 (https://openfontlicense.org). The license text also
travels inside the font file's own name table.

### Capacitor

The Android app is built with Capacitor (`@capacitor/android`, `core`, `cli`,
`app`, `filesystem`, `haptics`), installed from npm at build time and
compiled into the APK.

Copyright (c) 2017-present Drifty Co. Licensed under the MIT License.
Source: <https://github.com/ionic-team/capacitor>
