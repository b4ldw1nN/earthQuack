# Core Network & Reliability Improvements Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement network enhancements, Tailscale-linked SFTP auto-start, battery optimization exemption prompts, rclone remote probe caching, and an interactive diagnostics self-test.

**Architecture:** Layered additions across existing managers (`TailnetRoute`, `SftpSettingsStore`, `TailscaleNodeMonitor`, `StorageRepository`, and `DiagnosticsFragment`) adhering to Android lifecycle, coroutines, and AppStatus channels.

**Tech Stack:** Kotlin, Android SDK 34, Coroutines & Flow, tsnet/gomobile bindings, ViewBinding.

---

### Task 1: SOCKS Proxy Utilities & One-Tap Export (Feature 1)

**Files:**
- Modify: `app/src/main/kotlin/io/earthquack/app/tailscale/TailnetRoute.kt`
- Modify: `app/src/main/kotlin/io/earthquack/app/ui/DiagnosticsFragment.kt`
- Test: `app/src/test/kotlin/io/earthquack/app/tailscale/TailnetRouteTest.kt`

**Interfaces:**
- Produces: `TailnetRoute.proxyUrl(): String?`, `TailnetRoute.exportCommand(): String?`

- [ ] **Step 1: Write unit tests for proxy URL generation in `TailnetRouteTest.kt`**
- [ ] **Step 2: Add `proxyUrl()` and `exportCommand()` helper methods to `TailnetRoute.kt`**
- [ ] **Step 3: Make `rowSocks` clickable in `DiagnosticsFragment.kt` with a dialog or toast offering to copy `socks5://127.0.0.1:1055` or `export all_proxy=...`**
- [ ] **Step 4: Run unit tests and verify**

---

### Task 2: Auto-Start SFTP with Tailscale & on Boot (Feature 2)

**Files:**
- Modify: `app/src/main/kotlin/io/earthquack/app/sftp/SftpSettings.kt`
- Modify: `app/src/main/res/layout/fragment_sftp.xml`
- Modify: `app/src/main/kotlin/io/earthquack/app/ui/SftpFragment.kt`
- Modify: `app/src/main/kotlin/io/earthquack/app/tailscale/TailscaleNodeMonitor.kt`
- Create: `app/src/main/kotlin/io/earthquack/app/receiver/BootCompletedReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/io/earthquack/app/sftp/SftpSettingsTest.kt`

**Interfaces:**
- Produces: `SftpSettings.autoStartWithTailscale: Boolean`
- Produces: `BootCompletedReceiver` responding to `android.intent.action.BOOT_COMPLETED`

- [ ] **Step 1: Add `autoStartWithTailscale` to `SftpSettings` with JSON serialization & unit tests**
- [ ] **Step 2: Add switch in `fragment_sftp.xml` and bind to `SftpSettingsStore` in `SftpFragment.kt`**
- [ ] **Step 3: Trigger `SftpServerControllerImpl.start()` in `TailscaleNodeMonitor.kt` when node reaches `Running` if setting enabled**
- [ ] **Step 4: Implement `BootCompletedReceiver` to initialize `TailscaleNodeMonitor.start(context)` on boot**
- [ ] **Step 5: Register receiver and `RECEIVE_BOOT_COMPLETED` in `AndroidManifest.xml`**

---

### Task 3: Background Survival & Battery Whitelist Notification (Feature 3)

**Files:**
- Create: `app/src/main/kotlin/io/earthquack/app/state/BatteryOptimizationManager.kt`
- Modify: `app/src/main/kotlin/io/earthquack/app/MainActivity.kt`
- Test: `app/src/test/kotlin/io/earthquack/app/state/BatteryOptimizationManagerTest.kt`

**Interfaces:**
- Produces: `BatteryOptimizationManager.checkAndPublishWarning(context: Context)`
- Uses: `AppStatus.publish("battery", ...)`

- [ ] **Step 1: Create `BatteryOptimizationManager` checking `PowerManager.isIgnoringBatteryOptimizations`**
- [ ] **Step 2: Add `checkAndPublishWarning()` that publishes to `AppStatus` with an action to launch settings**
- [ ] **Step 3: Invoke check on `MainActivity.onResume()`**
- [ ] **Step 4: Verify `AppStatus` banner rendering and dismissing**

---

### Task 4: Storage & Rclone Remote Reachability TTL Cache (Feature 4)

**Files:**
- Modify: `app/src/main/kotlin/io/earthquack/app/state/StorageRepository.kt`
- Test: `app/src/test/kotlin/io/earthquack/app/state/StorageRepositoryTest.kt`

**Interfaces:**
- Produces: `probeRemotes(names: List<String>, forceRefresh: Boolean = false, onResult: ...)`

- [ ] **Step 1: Write unit tests verifying cached probe results are returned within TTL**
- [ ] **Step 2: Add `ConcurrentHashMap` cache with 90-second TTL to `StorageRepository.kt`**
- [ ] **Step 3: Return cached health results immediately; skip network round-trips unless expired or `forceRefresh = true`**
- [ ] **Step 4: Verify unit tests and test tab-switching performance**

---

### Task 5: Diagnostics Self-Healing & Network Self-Test (Feature 5)

**Files:**
- Create: `app/src/main/kotlin/io/earthquack/app/diagnostics/NetworkSelfTest.kt`
- Modify: `app/src/main/res/layout/fragment_diagnostics.xml`
- Modify: `app/src/main/kotlin/io/earthquack/app/ui/DiagnosticsFragment.kt`
- Test: `app/src/test/kotlin/io/earthquack/app/diagnostics/NetworkSelfTestTest.kt`

**Interfaces:**
- Produces: `NetworkSelfTest.runAll(context: Context): List<TestResult>`
- Produces: UI card displaying test status for SOCKS loopback, Tailnet ping, and SFTP service

- [ ] **Step 1: Create `NetworkSelfTest` with individual checks for SOCKS socket, Tailnet state, and SFTP status**
- [ ] **Step 2: Add "Run Self-Test" button and results container to `fragment_diagnostics.xml`**
- [ ] **Step 3: Wire button in `DiagnosticsFragment.kt` to run tests asynchronously and display results**
- [ ] **Step 4: Verify test execution and run `./gradlew assembleDebug testDebugUnitTest`**
