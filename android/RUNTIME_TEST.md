# Runtime test on Android 14/15

This is the procedure that closes the loop: prove the built app actually
runs on a device and events reach the parent server + dashboard.

Requires: a built APK (see `BUILD.md`) and the Rust server running on the
parent side (same Tailscale network).

## 1. Parent side (on Kali / host)

```bash
cd server-rust
cargo build --release
CHILDGUARD_TOKEN=testsecret CHILDGUARD_DATA_DIR=/tmp/cg_data \
  ./target/release/childguard-server
```

Keep this running. The collector **never prints a user-supplied token**; the
token is whatever you set in `CHILDGUARD_TOKEN`. Note the reachable URL from
the phone: `http://<tailscale-ip>:38080`.

## 2. Phone side (Android 14/15)

1. Install `app-debug.apk`.
2. Open ChildGuard → Setup:
   - Server URL: `http://<tailscale-ip>:38080`
   - Token: the same value you set in `CHILDGUARD_TOKEN`
   - Enable Accessibility (Settings → Accessibility → ChildGuard)
   - Enable Notification access (Settings → Special access → Notification access)
   - Allow Notifications permission
3. Dashboard → **Start Monitoring** (starts the foreground service).

## 3. Expected evidence (what "it works" looks like)

- Server log: new `POST /api/register` and repeated `POST /api/events`.
- Parent terminal:
  ```bash
  cd childguard/termux
  ./dashboard.sh --sync
  ./dashboard.sh --overview    # events count > 0
  ./dashboard.sh --logs ""      # sees chat/browser lines
  ./dashboard.sh --alerts       # stranger alerts appear when patterns match
  ```
- Type a message in WhatsApp / search in Chrome → within ~15 s the event
  appears in `--logs`.

## 4. Recording results

Capture:

```
adb devices                       # only if attached via USB
curl -s http://127.0.0.1:38080/health
dashboard.sh --overview
dashboard.sh --logs "whatsapp"
```
