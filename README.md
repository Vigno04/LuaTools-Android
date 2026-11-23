# LuaTools Android

Android companion app for **LuaTools** — push `.lua` / `.manifest` games, apply online & community game fixes, manage BetterSteamTools / OpenSteamTools packs, and sync Steam Cloud saves via **CloudRedirect** (Google Drive) to Android Wine/Steam emulators (**BannerHub**, **GameNative**, **Winlator**, etc.).

## Proof-of-Concept Integration
- **[Modified BannerHub (Vigno04/bannerhub)](https://github.com/Vigno04/bannerhub)** — Proof-of-concept Android Steam/Wine emulator integrating the `luatools-javasteam-addon` library (`BhLuaBridgeProvider`, `BhLuaSteamEmu`, `BhLuaCloudHook`).

## Repositories
- `app/` — Android Jetpack Compose app (`com.luatools.companion`)
- `luatools-javasteam-addon/` — Portable, zero-dependency Java library (`com.luatools.javasteam`) for target emulators. See [luatools-javasteam-addon/README.md](luatools-javasteam-addon/README.md) for the integration guide.
- **[LuaTools-Fixes (Vigno04/LuaTools-Fixes)](https://github.com/Vigno04/LuaTools-Fixes)** — Community & Wine/FEX compatibility fixes index (`fixes.json`) loaded alongside LuaTools API fixes in the **Fixes** tab.

## Building
```bash
./gradlew assembleDebug
```
Requires Android SDK 36 (and `libcloud_redirect_android.so` from [CloudRedirect](https://github.com/Vigno04/CloudRedirect) releases in `app/src/main/jniLibs/`).
