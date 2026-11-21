<p align="center"><img src="assets/icon-only.png" width="120" alt=""></p>

<h1 align="center">Vivarium</h1>

<p align="center"><em>Keeps single-file HTML tools on the phone and runs each one behind its own glass.</em></p>

---

## What it does

- Import `.html` tools with the **+** button, or from any file manager with **Open with → Vivarium**, or by sharing the file to it. Several at once is fine.
- The app keeps its own copy. Nothing is read from phone storage after the import.
- **Every tool is its own origin**: `https://<id>.vivarium.localhost`. The app serves that address itself and nothing goes over the network. Each tool gets its own localStorage, IndexedDB and caches, and no tool can see another one's data or the shelf's.
- Tap a tile to open the tool, hold it for the sheet: update from file (data stays), rename, hide system bars, pin to home screen, save a copy of the .html, wipe its data, remove.
- A square pip on a tile means the tool has saved data.

## Things tools get for free

All of this is added from the outside, so the tool file never needs to change:

| In the tool | In Vivarium |
|---|---|
| `<a download>` with a blob: or data: link | written to `Documents/Vivarium/`. Works even if the tool revokes the URL right away |
| `navigator.vibrate()` | real haptics |
| `navigator.wakeLock.request('screen')` | the screen stays on until released or the tool closes |
| a link to another site | opens in the browser, not inside the tool |
| `<input type="file">` | accepts any file (Android hides unknown extensions otherwise) |
| `font-family: "JetBrains Mono"` | works offline, bundled |
| `pagehide` listener + `#dot.pending` | on close the tool is asked to save and Vivarium waits for the dot to settle (up to 2.5 s) |
| `#banner` visible | on close Vivarium warns that saving failed and offers to stay |

The last two are the same conventions the Den's Workshop uses, so Almagest, Lore Map and Lexicon behave exactly as they do there.

## Identity of a tool

The id (and so the hostname, and so the storage) comes from the tool's `<title>` the first time it's imported, minus a leading "VulpesTech". `VulpesTech Almagest` becomes `almagest`.

- Importing a file with the same title again offers **Replace** (keeps data) or **Keep both** (new id, starts empty).
- **Update from file** always keeps the id, even if the title changed.
- **Rename** only changes the label on the shelf.
- **Remove, keep data** leaves the storage in place, so importing the same tool later brings everything back.

## Setup (phone, GitHub web UI)

1. Make a **private** repo and upload everything here, keeping the folders (`.github/workflows`, `assets`, `native`, `vivarium`).
2. Settings → Secrets and variables → Actions: add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The Den's values work as they are; one key can sign any number of apps.
3. Actions → Build Android APK → Run workflow. The APK is in the run's artifacts. Or publish a release (`v1.0.0` style tag) and it's attached to the release.

## Layout

```
index.html               the launcher (shelf, sheets, tool layer)
vivarium/shim.js         spliced into every tool page
vivarium/keeper.html     reads or wipes a tool's storage without opening it
vivarium/fonts.css       JetBrains Mono for tools
vivarium/jbm.woff2
native/ToolServer.java   serves https://<id>.vivarium.localhost
native/ToolHostClient.java
native/Inbox.java        Open with / Share
native/VivariumPlugin.java   shortcuts, keep awake, hide bars, external links
native/MainActivity.java
assets/                  icon and splash sources
```

On the phone the app keeps `library.json`, `tools/<id>/tool.html` and a short-lived `inbox/` in its private storage. Cloud backup of the app's data is switched off in the manifest.

## Notes

- Opened in a desktop browser, `index.html` still works as a preview, but without separate origins: every tool shares one storage there.
- JetBrains Mono: Copyright 2020 The JetBrains Mono Project Authors, SIL Open Font License 1.1.
