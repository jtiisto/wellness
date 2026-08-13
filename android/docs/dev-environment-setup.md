# Development Environment Setup

Code + build on Linux server, emulator on Windows laptop, connected via ADB over TCP (Tailscale network).

## Prerequisites

- Linux server and Windows laptop on the same Tailscale network
- Linux server: where Claude Code runs and code lives
- Windows laptop: runs the Android emulator

---

## Part 1: Linux Server — JDK + Android SDK

### 1.1 Install JDK 21

```bash
sudo apt update
sudo apt install openjdk-21-jdk-headless
```

Verify:
```bash
java -version
# openjdk version "21.x.x"
```

### 1.2 Install Android SDK Command-Line Tools

```bash
# Create SDK directory
mkdir -p ~/android-sdk/cmdline-tools

# Download latest command-line tools (check https://developer.android.com/studio#command-line-tools-only for current URL)
cd /tmp
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip

# Move into place (the "latest" subdirectory name is required)
mv cmdline-tools ~/android-sdk/cmdline-tools/latest
```

### 1.3 Set Environment Variables

Add to `~/.bashrc` (or `~/.zshrc`):

```bash
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
```

Reload:
```bash
source ~/.bashrc
```

### 1.4 Install SDK Packages

```bash
# Accept licenses first
sdkmanager --licenses

# Install required packages for API 35 (Pixel 10+)
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Verify:
```bash
sdkmanager --list_installed
# Should show platform-tools, platforms;android-35, build-tools;35.0.0
```

### 1.5 Verify Gradle Can Build

Once the project skeleton exists:
```bash
cd ~/dev/native/wellness
./gradlew assembleDebug
```

---

## Part 2: Windows Laptop — Android Emulator

### 2.1 Install Android Studio

Download and install from https://developer.android.com/studio

During setup, ensure these are installed:
- Android SDK Platform 35
- Intel HAXM or Windows Hypervisor Platform (for emulator acceleration)
- Android Emulator

### 2.2 Create an AVD (Android Virtual Device)

1. Open Android Studio → Tools → Device Manager
2. Click "Create Device"
3. Select **Pixel 9 Pro** (or closest to Pixel 10 available — Pixel 10 AVD may not exist yet, use latest Pixel)
4. Select system image: **API 35** (VanillaIceCream or later)
5. Name it something recognizable (e.g., "Pixel-Dev")
6. Finish

### 2.3 Start the Emulator with ADB TCP Enabled

Option A — Start from command line with a specific port:
```powershell
# Find your emulator path (typically):
# C:\Users\<you>\AppData\Local\Android\Sdk\emulator\emulator.exe

# Start emulator
emulator -avd Pixel-Dev
```

Option B — Start from Android Studio Device Manager (click the play button).

### 2.4 Expose the Emulator's ADB Port via Port Proxy

The emulator's ADB bridge binds to `127.0.0.1:5555` (loopback only) on the Windows host. What
makes it reachable from the Linux server is a **netsh port proxy** that forwards the external
interface to loopback (one-time setup, Administrator PowerShell):

```powershell
netsh interface portproxy add v4tov4 listenport=5555 listenaddress=0.0.0.0 connectport=5555 connectaddress=127.0.0.1
# Verify:
netsh interface portproxy show all
```

`adb tcpip 5555` is **not** part of this path — the proxy talks straight to the emulator's
qemu ADB bridge, no device-side TCP mode needed.

**Boot-order trap:** the proxy's wildcard bind makes port 5555 look busy when the emulator
starts, so the emulator may skip to the next port pair (console 5556 / ADB 5557) — and the proxy
then forwards to a port where nothing listens. See "ADB connects but device stays offline" in
Troubleshooting.

```powershell
# Verify emulator is running and visible to local ADB
adb devices
# Should show: emulator-5554   device   (emulator-5556 means the port pair shifted — see Troubleshooting)
```

### 2.5 Allow ADB Through Windows Firewall

The Linux server needs to reach port 5555 on the Windows machine. Since both are on Tailscale, use the Tailscale IP.

```powershell
# Open PowerShell as Administrator
New-NetFirewallRule -DisplayName "ADB over TCP" -Direction Inbound -Protocol TCP -LocalPort 5555 -Action Allow -Profile Any
```

### 2.6 Find Your Windows Tailscale IP

```powershell
tailscale ip -4
# e.g., 100.x.x.x
```

---

## Part 3: Connect Linux → Windows Emulator

### 3.1 Connect ADB from Linux Server

```bash
# Replace with your Windows Tailscale IP
adb connect 100.x.x.x:5555
# Output: connected to 100.x.x.x:5555

# Verify
adb devices
# Should show: 100.x.x.x:5555   device
```

### 3.2 Test with a Simple Install

```bash
# Build the project
cd ~/dev/native/wellness
./gradlew assembleDebug

# Install on the remote emulator
adb -s 100.x.x.x:5555 install app/build/outputs/apk/debug/app-debug.apk
```

---

## Part 4: Daily Workflow

### Build and deploy

```bash
# One command: build + install + launch
./gradlew installDebug

# Or manually:
./gradlew assembleDebug && adb install app/build/outputs/apk/debug/app-debug.apk
```

`installDebug` works if only one device is connected via ADB. If multiple devices are connected, specify the target:

```bash
adb -s 100.x.x.x:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

### View logs from Linux

```bash
# Stream all app logs
adb logcat --pid=$(adb shell pidof dev.jtiisto.wellness)

# Or filter by tag
adb logcat -s "WellnessSync"
```

### Reconnect after emulator restart

Each time you restart the emulator on Windows:
1. On Windows: `adb tcpip 5555`
2. On Linux: `adb connect 100.x.x.x:5555`

### Quick reference

| Action | Command (on Linux) |
|--------|-------------------|
| Build debug APK | `./gradlew assembleDebug` |
| Build + install | `./gradlew installDebug` |
| Install APK | `adb install app/build/outputs/apk/debug/app-debug.apk` |
| Run app | `adb shell am start -n dev.jtiisto.wellness/.MainActivity` |
| View logs | `adb logcat --pid=$(adb shell pidof dev.jtiisto.wellness)` |
| Clear app data | `adb shell pm clear dev.jtiisto.wellness` |
| Uninstall | `adb uninstall dev.jtiisto.wellness` |
| Check connection | `adb devices` |
| Reconnect | `adb connect 100.x.x.x:5555` |

---

## Part 5: Optional — Android Studio on Windows for Debugging

For day-to-day development, the command-line workflow above is sufficient. But when you need:

- **Compose previews** — open the project in Android Studio on Windows (clone from git or use a Syncthing share of just the source)
- **Debugger** — attach Android Studio's debugger to the running app on the emulator
- **Layout inspector** — Android Studio → Tools → Layout Inspector
- **Profiler** — Android Studio → View → Tool Windows → Profiler

For these cases, you can open the project in Android Studio on Windows by cloning from the same git remote, or setting up a one-way file sync (Linux → Windows) for read-only access.

---

## Troubleshooting

### ADB connection refused
- Verify emulator is running on Windows (`adb devices` on Windows should show it)
- Check the firewall rule and the port proxy are active (`netsh interface portproxy show all`)
- Verify Tailscale IPs can ping each other: `ping 100.x.x.x`

### ADB connects but device stays offline (diagnosed 2026-08-10)
Symptoms: `adb connect` says "failed to connect" yet `adb devices` on Linux lists the address as
`offline`, retries never help, and on Windows `adb devices` shows a ghost `emulator-5554  offline`
alongside a live `emulator-5556  device`.

Cause: the port proxy held `0.0.0.0:5555` when the emulator started, so the emulator considered
the port busy and took the next pair (console 5556 / ADB **5557**). The proxy now forwards to
`127.0.0.1:5555` where nothing listens — it accepts the TCP connection and drops the relay, which
the Linux side reads as a permanently-offline device. (The ghost `emulator-5554` entry is the
Windows ADB server probing loopback 5555 and finding the proxy's self-loop.)

Fix (Administrator PowerShell) — retarget the proxy at the live emulator's actual ADB port
(console port shown by `adb devices` + 1):

```powershell
netsh interface portproxy set v4tov4 listenport=5555 listenaddress=0.0.0.0 connectport=5557 connectaddress=127.0.0.1
```

Then `adb connect` from Linux works immediately. After a reboot or emulator restart the pair may
shift back to 5554/5555 — retarget accordingly. Red herrings that do NOT fix this: `adb tcpip`,
`adb kill-server` on either side, reconnect loops.

**Root cause and the ordering rule (resolved 2026-08-12):** the proxy's wildcard bind existing
BEFORE the emulator starts is what causes every variant of this. Either the emulator sees 5555
busy and shifts to 5556/5557 (the mismatch above), or — when the proxy targets its own listen
port with no emulator behind it — the proxy self-loops and the local ADB server sees the loop as
a ghost `emulator-5554  offline` that `emu kill` cannot kill (no console behind it). The fix is
ordering, not retargeting: **delete the proxy, start the emulator, re-add the proxy after boot**
(`start-emulator.ps1` v4 automates this when run as admin). Ghost cleanup: delete the proxy,
`adb kill-server`, confirm `adb devices` is empty, then proceed in order. After the Windows side
is correct, the Linux side may still need its own `adb kill-server` before the handshake reads
`device` — a stale local server that has failed many handshakes keeps failing good ones.

### ADB unauthorized
- Check the emulator screen for an "Allow USB debugging?" prompt and accept it

### Gradle build fails with SDK not found
- Verify `ANDROID_HOME` is set: `echo $ANDROID_HOME`
- Create `local.properties` in project root: `sdk.dir=/home/<user>/android-sdk`

### Emulator not visible after Windows sleep/resume
- Reconnect: `adb connect 100.x.x.x:5555`
- If that fails, restart the emulator and re-run `adb tcpip 5555`

### Slow ADB over network
- ADB over TCP adds ~50-100ms latency for installs, which is fine
- Log streaming (`adb logcat`) works well over Tailscale
- If APK installs are slow, check Tailscale throughput between the machines
