<p align="center"><img src="assets/icon-only.png" width="120" alt=""></p>

<h1 align="center">Vivarium</h1>

<p align="center">Keeps single-file HTML tools on Android. Each one runs in its own sandbox.</p>

---

## Why

I built this to solve a point of frustration I've had with the current programming scene, and the ever-growing restrictions with app development on Android. Starting in 2027, Android phones will refuse to install apps unless the developer has handed Google their ID and registered the app. You can still get around it with ADB or Google's "advanced flow", for now. I'm not verifying my identity to share a file launcher, so Vivarium will need that workaround once. After that, every tool you put in it is just a file and you can avoid that hassle entirely.

This lets anyone interested in programming safely and privately run their .html tools within a free app with zero ads and zero trackers.

Vivarium itself never connects to anything. Your tools only send what they were written to send, and nothing else in here can see their data. It might be buggy, it might be simplistic, but what's important is that it works well with what it has, looks good, and doesn't try to sell you anything.

If you wish to support what I do, check out my other projects, or ask me how to contribute. I don't pay it much mind, but the option is there if you want it.

You can fork it, alter it, do whatever you like with it, as long as you follow the license attached. 

Keep expectations in check. The tool works for me, and I'll mostly fix what doesn't work if I run into it, but reports and suggestions are welcome, because I want people to get as much freedom with how they run their stuff as possible.

---

## What it does

- Imports `.html` files. From the **+** button, from a file manager with **Open with**, or by sharing a file to it. Several at once works.
- Keeps its own copy of each file. Nothing is read from phone storage after the import.
- Shows them as tiles. Tap to open, hold for options.
- Runs each tool full screen, with no browser bars.
- Gives each tool its own storage. Tools cannot see each other's data.

No ads. No tracking. No account. Vivarium itself never connects to anything. Tools can, if they are written to.

## Install

Download the APK from [Releases](../../releases) and open it. Android 7 or newer.

Not on the Play Store.

## How the sandbox works

Every tool is served from its own address:

```
https://<id>.vivarium.localhost/
```

The app answers that address itself, from its private storage. Nothing goes over the network. Different hostname means different origin, so the WebView gives every tool separate localStorage, IndexedDB, cookies and caches.

The shelf runs at `https://localhost`. Tools run in a frame on top of it.

A tool can:
- use the network like any web page (fetch, images, fonts)
- open files you pick for it
- export files (they go to `Documents/Vivarium/`)

A tool cannot:
- read or change another tool's storage, or the shelf's
- call the app's native side (Capacitor only answers `https://localhost`)
- be loaded inside another tool (its pages send `frame-ancestors 'self' https://localhost`)
- wipe another tool through the storage helper (the helper only acts when the shelf loaded it directly)
- read your files without you picking them

Links to other sites open in your browser, not inside the tool.

## Options per tool

Hold a tile.

| Option | What it does |
|---|---|
| Update from file | Swaps the file. Saved data stays. |
| Rename | Changes the label only. |
| Hide system bars | Status and navigation bars hidden while the tool is open. Swipe from an edge to show them. |
| Pin to home screen | Home screen icon that opens straight into the tool. |
| Save a copy | Writes the `.html` to `Documents/Vivarium/`. |
| Wipe its data | Empties the tool's storage. The tool stays. |
| Remove | Removes the tool. Asks whether to keep or wipe its data. |

A square in the corner of a tile means the tool has saved data.

## Tool identity

The id, and so the hostname, and so the storage, comes from the file's `<title>` on first import. A leading "VulpesTech" is dropped.

`<title>VulpesTech Almagest</title>` becomes `almagest`, served at `https://almagest.vivarium.localhost/`.

- Importing a file with a title that is already on the shelf asks: **Replace** (keeps the data) or **Keep both** (new id, empty storage).
- **Update from file** keeps the id, even if the title changed.
- **Remove, keep data** leaves the storage behind. Importing the same tool again picks it back up.

## What tools get without changes

The app adds a small script to every tool page when it serves it. The file on disk is never modified.

| In the tool | In Vivarium |
|---|---|
| `<a download>` with a `blob:` or `data:` link | Saved to `Documents/Vivarium/`. Works even if the tool revokes the URL right after. |
| `navigator.vibrate()` | Haptics. |
| `navigator.wakeLock.request('screen')` | Screen stays on until released or the tool closes. |
| Link to another site | Opens in the browser. |
| `<input type="file">` | Accepts any file. Android otherwise hides unknown extensions. |
| `font-family: "JetBrains Mono"` | Bundled, works offline. |

## Saving on close

When a tool closes, Vivarium fires `pagehide` in it and waits.

- If the tool has an element `#dot` with the class `pending`, Vivarium waits until the class is gone, up to 2.5 seconds.
- If the tool has an element `#banner` that is not hidden, Vivarium says saving failed and offers to stay.

Both are optional. Tools without them close after `pagehide` fires. Tools that save as they go need nothing.

## Limits

- Single files only. A tool that loads `./other-file.js` or `./image.png` from next to itself gets a 404. Inline it.
- Requests to plain `http://` addresses are blocked. Tools run on https.
- The Fullscreen API does nothing. Use **Hide system bars**.
- Tools imported into the Den or a browser keep their data there. Export from the old place, import inside the tool in Vivarium.
- Uninstalling the app deletes every tool and all their data. Android backup is switched off, so nothing goes to a cloud.
- Opened in a desktop browser, `index.html` works as a preview but without separate origins. All tools share one storage there.

## Where things are on the phone

In the app's private storage:

```
library.json            the shelf
tools/<id>/tool.html    each tool, as imported
inbox/                  files that came in through Open with / Share, deleted after import
```

Each tool's own storage is in the WebView's data for its origin.

## Building it yourself

GitHub Actions builds the APK. Nothing to install locally except for making a signing key once.

1. Fork or copy the repo.
2. Make a keystore on any machine with Java:
   ```
   keytool -genkeypair -v -keystore vivarium.keystore -alias vivarium -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 vivarium.keystore > vivarium.b64
   ```
   Keep the `.keystore` file and the password. Every later build has to use the same key, or Android refuses the update.
3. In the repo: Settings → Secrets and variables → Actions. Add:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | contents of `vivarium.b64` |
   | `KEYSTORE_PASSWORD` | the password |
   | `KEY_ALIAS` | `vivarium` |
   | `KEY_PASSWORD` | the password |

4. Actions → Build Android APK → Run workflow. The APK is in the run's artifacts. Publishing a release also runs the build and attaches the APK to it.

## Files

```
index.html                  the shelf, the sheets, the tool layer
vivarium/shim.js            added to every tool page
vivarium/keeper.html        reads or wipes a tool's storage for the shelf
vivarium/fonts.css          JetBrains Mono for tools
vivarium/jbm.woff2
native/ToolServer.java      serves https://<id>.vivarium.localhost
native/ToolHostClient.java  puts ToolServer in front of Capacitor
native/Inbox.java           Open with / Share
native/VivariumPlugin.java  shortcuts, keep awake, hide bars, external links
native/MainActivity.java
assets/                     icon and splash sources
.github/workflows/android.yml
```

The Android project is generated fresh by Capacitor on every build. The workflow copies `native/` into it and patches the manifest.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE). Third-party parts are listed in [NOTICE.md](NOTICE.md).
