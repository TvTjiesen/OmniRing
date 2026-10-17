# HyperZoomRing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an LSPosed module that intercepts Xiaomi 17 Ultra's zoom ring input events and maps rotation gestures to user-configurable system actions.

**Architecture:** Hook `InputManagerService` in system_server via YukiHookAPI to intercept `focus_ring_bridge` device events (`EV_REL REL_WHEEL`). A speed detector classifies rotation into fast/slow gestures. Each gesture maps to a configurable Action (volume, brightness, app launch). A Miuix-styled Compose UI provides configuration and a diagnostic mode for real-time event inspection.

**Tech Stack:** Kotlin 2.1, YukiHookAPI 1.3+, KavaRef, Jetpack Compose, Miuix UI 0.9.0, Gradle KTS, minSdk 35

**VCS:** All version control uses `jj` (not git). Commit = `jj commit -m "..."`.

---

## File Structure

```
HyperZoomRing/
├── gradle/
│   ├── libs.versions.toml              # Version catalog
│   └── wrapper/
│       └── gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts                # App module build config
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/xyz/nextalone/hyperzoomring/
│       │       ├── HookEntry.kt                    # YukiHookAPI Xposed entry
│       │       ├── hook/
│       │       │   └── InputInterceptorHook.kt     # Hook InputManagerService
│       │       ├── ring/
│       │       │   ├── ZoomRingConstants.kt         # Device IDs, event codes
│       │       │   ├── ZoomRingEvent.kt             # Event data class
│       │       │   ├── ZoomRingDetector.kt          # Speed detection logic
│       │       │   └── GestureType.kt               # Gesture enum
│       │       ├── action/
│       │       │   ├── Action.kt                    # Action interface
│       │       │   ├── ActionRegistry.kt            # Action lookup
│       │       │   └── actions/
│       │       │       ├── VolumeAction.kt
│       │       │       ├── BrightnessAction.kt
│       │       │       └── LaunchAppAction.kt
│       │       ├── config/
│       │       │   └── ConfigManager.kt             # XSharedPreferences bridge
│       │       └── ui/
│       │           ├── MainActivity.kt              # Compose entry
│       │           └── screen/
│       │               ├── HomeScreen.kt            # Module status + gesture cards
│       │               ├── DiagnosticScreen.kt      # Real-time event log
│       │               └── GestureConfigScreen.kt   # Action picker per gesture
│       └── test/
│           └── kotlin/xyz/nextalone/hyperzoomring/
│               └── ring/
│                   └── ZoomRingDetectorTest.kt      # Unit tests for speed detection
├── build.gradle.kts                    # Root build script
├── settings.gradle.kts                 # Project settings
└── gradle.properties                   # Gradle config
```

---

## Task 1: Project Scaffolding — Gradle & Build System

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/")
        maven("https://maven.pkg.github.com/nicehash/yukihookapi") // fallback
    }
}

rootProject.name = "HyperZoomRing"
include(":app")
```

- [ ] **Step 2: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.9.1"
kotlin = "2.1.20"
ksp = "2.1.20-1.0.32"
yukihookapi = "1.3.0"
kavaref = "1.0.3"
compose-bom = "2025.03.01"
miuix = "0.9.0"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }

[libraries]
yukihookapi-api = { module = "com.highcapable.yukihookapi:api", version.ref = "yukihookapi" }
yukihookapi-ksp-xposed = { module = "com.highcapable.yukihookapi:ksp-xposed", version.ref = "yukihookapi" }
kavaref-core = { module = "com.highcapable.kavaref:kavaref-core", version.ref = "kavaref" }
kavaref-extension = { module = "com.highcapable.kavaref:kavaref-extension", version.ref = "kavaref" }
xposed-api = { module = "de.robv.android.xposed:api", version = "82" }

compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-activity = { module = "androidx.activity:activity-compose", version = "1.10.1" }
compose-navigation = { module = "androidx.navigation:navigation-compose", version = "2.9.0" }

miuix-ui = { module = "top.yukonga.miuix.kmp:miuix", version.ref = "miuix" }

junit = { module = "junit:junit", version = "4.13.2" }
```

- [ ] **Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 5: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.nextalone.hyperzoomring"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.nextalone.hyperzoomring"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // YukiHookAPI
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)

    // Miuix UI
    implementation(libs.miuix.ui)

    // Test
    testImplementation(libs.junit)
}
```

- [ ] **Step 6: Create `app/proguard-rules.pro`**

```proguard
-keep class xyz.nextalone.hyperzoomring.HookEntry
-keep class * extends com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
```

- [ ] **Step 7: Initialize Gradle wrapper**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && gradle wrapper --gradle-version 8.13`
Expected: `gradle/wrapper/` created with `gradle-wrapper.jar` and `gradle-wrapper.properties`

- [ ] **Step 8: Commit**

```bash
jj commit -m "chore: scaffold Gradle build system with YukiHookAPI and Miuix UI dependencies"
```

---

## Task 2: AndroidManifest & YukiHookAPI Entry Point

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/HookEntry.kt`

- [ ] **Step 1: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:icon="@mipmap/ic_launcher"
        android:label="HyperZoomRing"
        android:theme="@android:style/Theme.Material.DayNight.NoActionBar"
        android:supportsRtl="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Xposed Module metadata -->
        <meta-data
            android:name="xposedmodule"
            android:value="true" />
        <meta-data
            android:name="xposeddescription"
            android:value="Remap Xiaomi 17 Ultra zoom ring gestures to custom actions" />
        <meta-data
            android:name="xposedminversion"
            android:value="93" />
        <meta-data
            android:name="xposedsharedprefs"
            android:value="true" />
    </application>
</manifest>
```

- [ ] **Step 2: Create `HookEntry.kt`**

```kotlin
package xyz.nextalone.hyperzoomring

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import xyz.nextalone.hyperzoomring.hook.InputInterceptorHook

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onHook() = encase {
        loadSystem {
            InputInterceptorHook.hook(this)
        }
    }
}
```

- [ ] **Step 3: Create placeholder `InputInterceptorHook.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.hook

import android.util.Log
import com.highcapable.yukihookapi.hook.factory.toClass
import com.highcapable.yukihookapi.hook.param.PackageParam

object InputInterceptorHook {

    private const val TAG = "HyperZoomRing"

    fun hook(param: PackageParam) = with(param) {
        Log.i(TAG, "InputInterceptorHook loaded in system_server")
    }
}
```

- [ ] **Step 4: Create placeholder `MainActivity.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                // Placeholder — screens added in later tasks
            }
        }
    }
}
```

- [ ] **Step 5: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK generated at `app/build/outputs/apk/debug/`

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: add AndroidManifest, YukiHookAPI entry point, and placeholder MainActivity"
```

---

## Task 3: Event Data Model & Constants

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ring/ZoomRingConstants.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ring/ZoomRingEvent.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ring/GestureType.kt`

- [ ] **Step 1: Create `ZoomRingConstants.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ring

object ZoomRingConstants {
    /** Input device name reported by the zoom ring hardware. */
    const val DEVICE_NAME = "maxic, focus_ring_bridge"

    /** Vendor ID of the zoom ring. */
    const val VENDOR_ID = 0x9120

    /** Product ID of the zoom ring. */
    const val PRODUCT_ID = 0x9126

    /** Default value per tick when outside camera app. */
    const val TICK_VALUE_DEFAULT = 6

    /** Value per tick when camera app is active. */
    const val TICK_VALUE_CAMERA = -1

    /** Time window in milliseconds for speed calculation. */
    const val SPEED_WINDOW_MS = 200L

    /** Default threshold: events within SPEED_WINDOW_MS above this count = fast rotation. */
    const val DEFAULT_SPEED_THRESHOLD = 5
}
```

- [ ] **Step 2: Create `ZoomRingEvent.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ring

data class ZoomRingEvent(
    val timestampMs: Long,
    val value: Int,
) {
    val isCameraMode: Boolean get() = value == ZoomRingConstants.TICK_VALUE_CAMERA
}
```

- [ ] **Step 3: Create `GestureType.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ring

enum class GestureType(val displayName: String) {
    ROTATE_SLOW("慢转"),
    ROTATE_FAST("快转"),
}
```

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat: add zoom ring event data model, constants, and gesture types"
```

---

## Task 4: ZoomRingDetector with Unit Tests

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ring/ZoomRingDetector.kt`
- Create: `app/src/test/kotlin/xyz/nextalone/hyperzoomring/ring/ZoomRingDetectorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package xyz.nextalone.hyperzoomring.ring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ZoomRingDetectorTest {

    private lateinit var detector: ZoomRingDetector

    @Before
    fun setUp() {
        detector = ZoomRingDetector(
            speedWindowMs = 200L,
            speedThreshold = 5
        )
    }

    @Test
    fun singleEvent_returnsSlow() {
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6))
        assertEquals(GestureType.ROTATE_SLOW, gesture)
    }

    @Test
    fun rapidEvents_returnsFast() {
        // Send 6 events within 200ms window → exceeds threshold of 5
        for (i in 0 until 5) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 30L, value = 6))
        }
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1000 + 150, value = 6))
        assertEquals(GestureType.ROTATE_FAST, gesture)
    }

    @Test
    fun eventsSpreadOut_returnsSlow() {
        // Send events spread over 1 second — only 1 per window
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6))
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1500, value = 6))
        assertEquals(GestureType.ROTATE_SLOW, gesture)
    }

    @Test
    fun oldEvents_arePruned() {
        // Send 10 events, then wait 500ms
        for (i in 0 until 10) {
            detector.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 10L, value = 6))
        }
        // After 500ms gap, old events should be pruned
        val gesture = detector.onEvent(ZoomRingEvent(timestampMs = 1600, value = 6))
        assertEquals(GestureType.ROTATE_SLOW, gesture)
    }

    @Test
    fun intensity_scalesWithEventCount() {
        // Single event → low intensity
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = 6))
        val lowIntensity = detector.currentIntensity
        assert(lowIntensity in 0f..0.5f) { "Expected low intensity, got $lowIntensity" }

        // Many rapid events → high intensity
        val detector2 = ZoomRingDetector(speedWindowMs = 200L, speedThreshold = 5)
        for (i in 0 until 10) {
            detector2.onEvent(ZoomRingEvent(timestampMs = 1000 + i * 15L, value = 6))
        }
        val highIntensity = detector2.currentIntensity
        assert(highIntensity > 0.5f) { "Expected high intensity, got $highIntensity" }
    }

    @Test
    fun detectsCameraModeSwitch() {
        detector.onEvent(ZoomRingEvent(timestampMs = 1000, value = -1))
        assert(detector.isCameraMode) { "Should detect camera mode from value -1" }

        detector.onEvent(ZoomRingEvent(timestampMs = 2000, value = 6))
        assert(!detector.isCameraMode) { "Should detect default mode from value 6" }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:testDebugUnitTest --tests "xyz.nextalone.hyperzoomring.ring.ZoomRingDetectorTest"`
Expected: FAIL — `ZoomRingDetector` class does not exist

- [ ] **Step 3: Implement `ZoomRingDetector.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ring

class ZoomRingDetector(
    private val speedWindowMs: Long = ZoomRingConstants.SPEED_WINDOW_MS,
    private val speedThreshold: Int = ZoomRingConstants.DEFAULT_SPEED_THRESHOLD,
) {
    private val recentEvents = ArrayDeque<Long>() // timestamps only
    private var lastValue: Int = ZoomRingConstants.TICK_VALUE_DEFAULT

    /** Intensity of the last detected gesture, 0.0..1.0. */
    var currentIntensity: Float = 0f
        private set

    /** Whether the zoom ring is currently in camera mode. */
    var isCameraMode: Boolean = false
        private set

    /**
     * Process a new zoom ring event. Returns the detected gesture type.
     * Prunes old events outside the speed window, then classifies speed.
     */
    fun onEvent(event: ZoomRingEvent): GestureType {
        lastValue = event.value
        isCameraMode = event.isCameraMode

        val now = event.timestampMs
        recentEvents.addLast(now)

        // Prune events outside the speed window
        while (recentEvents.isNotEmpty() && recentEvents.first() < now - speedWindowMs) {
            recentEvents.removeFirst()
        }

        val count = recentEvents.size
        // Intensity: map event count in window to 0.0..1.0, capped at 2x threshold
        currentIntensity = (count.toFloat() / (speedThreshold * 2)).coerceIn(0f, 1f)

        return if (count > speedThreshold) GestureType.ROTATE_FAST else GestureType.ROTATE_SLOW
    }

    /** Reset detector state. */
    fun reset() {
        recentEvents.clear()
        currentIntensity = 0f
        isCameraMode = false
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:testDebugUnitTest --tests "xyz.nextalone.hyperzoomring.ring.ZoomRingDetectorTest"`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: implement ZoomRingDetector with speed-based gesture classification"
```

---

## Task 5: Input Event Interception Hook (Diagnostic Mode)

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/InputInterceptorHook.kt`

This is the core hook. It intercepts `MotionEvent` objects in system_server and filters for the zoom ring device. Android converts `EV_REL REL_WHEEL` to `MotionEvent` with `ACTION_SCROLL` and `AXIS_VSCROLL`.

- [ ] **Step 1: Implement the full `InputInterceptorHook.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.hook

import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.java.IntType
import xyz.nextalone.hyperzoomring.ring.ZoomRingConstants
import xyz.nextalone.hyperzoomring.ring.ZoomRingDetector
import xyz.nextalone.hyperzoomring.ring.ZoomRingEvent

object InputInterceptorHook {

    private const val TAG = "HyperZoomRing"

    private val detector = ZoomRingDetector()

    /** Listeners for diagnostic mode — collects raw events for UI display. */
    private val eventListeners = mutableListOf<(ZoomRingEvent) -> Unit>()

    fun addEventlistener(listener: (ZoomRingEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (ZoomRingEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun hook(param: PackageParam) = with(param) {
        Log.i(TAG, "Hooking InputManagerService for zoom ring interception")

        // Hook InputDispatcher's injectInputEvent to intercept all input events
        // InputManagerService.injectInputEvent(InputEvent, int) is accessible
        "com.android.server.input.InputManagerService".toClass().method {
            name = "onInputEvent"
            paramCount(1..3)
        }.hookAll {
            before {
                val event = args.firstOrNull() as? InputEvent ?: return@before
                if (event !is MotionEvent) return@before

                val device = event.device ?: return@before
                if (!isZoomRingDevice(device)) return@before

                val value = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
                val zoomEvent = ZoomRingEvent(
                    timestampMs = event.eventTime,
                    value = value,
                )

                Log.d(TAG, "ZoomRing event: value=$value, time=${event.eventTime}")

                // Notify diagnostic listeners
                eventListeners.forEach { it(zoomEvent) }

                // Detect gesture
                val gesture = detector.onEvent(zoomEvent)
                val intensity = detector.currentIntensity

                Log.d(TAG, "Gesture: $gesture, intensity=$intensity, cameraMode=${detector.isCameraMode}")

                // TODO: Task 8 will dispatch to ActionRegistry here
            }
        }.onHookFailure {
            Log.e(TAG, "Failed to hook InputManagerService.onInputEvent, trying fallback")
            hookFallback(param)
        }
    }

    /**
     * Fallback: hook PhoneWindowManager which also processes input events.
     */
    private fun hookFallback(param: PackageParam) = with(param) {
        "com.android.server.policy.PhoneWindowManager".toClass().method {
            name = "interceptMotionBeforeQueueingNonInteractive"
        }.hookAll {
            before {
                val event = args.firstOrNull() as? MotionEvent ?: return@before
                val device = event.device ?: return@before
                if (!isZoomRingDevice(device)) return@before

                val value = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
                val zoomEvent = ZoomRingEvent(
                    timestampMs = event.eventTime,
                    value = value,
                )

                Log.d(TAG, "ZoomRing event (fallback): value=$value")
                eventListeners.forEach { it(zoomEvent) }
                detector.onEvent(zoomEvent)
            }
        }.onHookFailure {
            Log.e(TAG, "Fallback hook also failed", it)
        }
    }

    private fun isZoomRingDevice(device: InputDevice): Boolean {
        return device.name == ZoomRingConstants.DEVICE_NAME ||
            (device.vendorId == ZoomRingConstants.VENDOR_ID &&
                device.productId == ZoomRingConstants.PRODUCT_ID)
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
jj commit -m "feat: implement InputInterceptorHook with zoom ring device filtering and fallback"
```

---

## Task 6: Diagnostic Screen UI

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/DiagnosticScreen.kt`
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/MainActivity.kt`

- [ ] **Step 1: Create `DiagnosticScreen.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import xyz.nextalone.hyperzoomring.ring.ZoomRingEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_EVENTS = 100

@Composable
fun DiagnosticScreen(modifier: Modifier = Modifier) {
    val events = remember { mutableStateListOf<ZoomRingEvent>() }
    val eventsPerSecond = remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new events arrive
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SmallTitle(text = "诊断信息")

        Spacer(modifier = Modifier.height(8.dp))

        // Stats card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "事件总数")
                    Text(text = "${events.size}")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "频率 (events/sec)")
                    Text(text = "%.1f".format(eventsPerSecond.value))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "最新值")
                    Text(
                        text = if (events.isNotEmpty()) {
                            val last = events.last()
                            "0x%08X (%d)".format(last.value, last.value)
                        } else "—"
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "模式")
                    Text(
                        text = if (events.isNotEmpty() && events.last().isCameraMode) "相机" else "默认"
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SmallTitle(text = "事件日志")

        Spacer(modifier = Modifier.height(8.dp))

        // Event log
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            items(events.toList()) { event ->
                EventLogItem(event)
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun EventLogItem(event: ZoomRingEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(event.timestampMs))
    val mode = if (event.isCameraMode) "CAM" else "DEF"

    Text(
        text = "$timeStr | value=${event.value} | $mode",
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
```

- [ ] **Step 2: Update `MainActivity.kt` with navigation**

```kotlin
package xyz.nextalone.hyperzoomring.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import xyz.nextalone.hyperzoomring.ui.screen.DiagnosticScreen
import xyz.nextalone.hyperzoomring.ui.screen.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                MainContent()
            }
        }
    }
}

@Composable
private fun MainContent() {
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        NavigationItem("主页", null),
        NavigationItem("诊断", null),
    )

    Scaffold(
        topBar = { TopAppBar(title = "HyperZoomRing") },
        bottomBar = {
            NavigationBar(
                items = tabs,
                selected = selectedTab,
                onClick = { selectedTab = it }
            )
        }
    ) { padding ->
        when (selectedTab) {
            0 -> HomeScreen(modifier = Modifier.padding(padding))
            1 -> DiagnosticScreen(modifier = Modifier.padding(padding))
        }
    }
}
```

- [ ] **Step 3: Create placeholder `HomeScreen.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "HyperZoomRing — 模块状态将在后续任务中实现")
    }
}
```

- [ ] **Step 4: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add DiagnosticScreen with real-time event log and stats display"
```

---

## Task 7: ConfigManager

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/config/ConfigManager.kt`

- [ ] **Step 1: Implement `ConfigManager.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.config

import android.content.Context
import android.content.SharedPreferences
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ring.ZoomRingConstants

/**
 * Manages module configuration. Used on the app side with SharedPreferences
 * and on the hook side with XSharedPreferences.
 */
class ConfigManager private constructor(private val prefs: SharedPreferences) {

    /** Get the action ID bound to a gesture type. */
    fun getActionId(gesture: GestureType): String? =
        prefs.getString("gesture_${gesture.name}_action_id", null)

    /** Set the action ID bound to a gesture type. */
    fun setActionId(gesture: GestureType, actionId: String?) {
        prefs.edit().putString("gesture_${gesture.name}_action_id", actionId).apply()
    }

    /** Get extra config for an action (e.g., package name for LaunchAppAction). */
    fun getActionConfig(gesture: GestureType): String? =
        prefs.getString("gesture_${gesture.name}_action_config", null)

    /** Set extra config for an action. */
    fun setActionConfig(gesture: GestureType, config: String?) {
        prefs.edit().putString("gesture_${gesture.name}_action_config", config).apply()
    }

    /** Speed threshold for fast/slow detection. */
    var speedThreshold: Int
        get() = prefs.getInt("speed_threshold", ZoomRingConstants.DEFAULT_SPEED_THRESHOLD)
        set(value) = prefs.edit().putInt("speed_threshold", value).apply()

    /** Whether the module hook is enabled. */
    var isEnabled: Boolean
        get() = prefs.getBoolean("module_enabled", true)
        set(value) = prefs.edit().putBoolean("module_enabled", value).apply()

    companion object {
        const val PREFS_NAME = "hyperzoomring_config"

        /** Create from app context (settings UI side). */
        fun fromContext(context: Context): ConfigManager {
            @Suppress("DEPRECATION")
            val prefs = context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_WORLD_READABLE
            )
            return ConfigManager(prefs)
        }

        /** Create from XSharedPreferences (hook side). */
        fun fromXPrefs(prefs: SharedPreferences): ConfigManager {
            return ConfigManager(prefs)
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
jj commit -m "feat: add ConfigManager for gesture-action mapping persistence"
```

---

## Task 8: Action System

**Files:**
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/Action.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/ActionRegistry.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/actions/VolumeAction.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/actions/BrightnessAction.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/action/actions/LaunchAppAction.kt`

- [ ] **Step 1: Create `Action.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.action

import android.content.Context

interface Action {
    /** Unique identifier for persistence. */
    val id: String

    /** Human-readable name for the UI. */
    val displayName: String

    /**
     * Execute the action.
     * @param context System context from the hook environment.
     * @param intensity Rotation speed mapped to 0.0..1.0.
     */
    fun execute(context: Context, intensity: Float)
}
```

- [ ] **Step 2: Create `ActionRegistry.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.action

import xyz.nextalone.hyperzoomring.action.actions.BrightnessAction
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.action.actions.VolumeAction

object ActionRegistry {

    private val actions = mutableMapOf<String, Action>()

    init {
        register(VolumeAction)
        register(BrightnessAction)
        register(LaunchAppAction)
    }

    private fun register(action: Action) {
        actions[action.id] = action
    }

    fun get(id: String): Action? = actions[id]

    fun all(): List<Action> = actions.values.toList()
}
```

- [ ] **Step 3: Create `VolumeAction.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.media.AudioManager
import xyz.nextalone.hyperzoomring.action.Action

object VolumeAction : Action {
    override val id = "volume"
    override val displayName = "调节音量"

    override fun execute(context: Context, intensity: Float) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val direction = if (intensity > 0.5f) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_RAISE
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
```

- [ ] **Step 4: Create `BrightnessAction.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.provider.Settings
import xyz.nextalone.hyperzoomring.action.Action

object BrightnessAction : Action {
    override val id = "brightness"
    override val displayName = "调节亮度"

    override fun execute(context: Context, intensity: Float) {
        val resolver = context.contentResolver
        // Disable auto brightness first
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val step = (intensity * 25).toInt().coerceAtLeast(5)
        val newBrightness = (current + step).coerceIn(1, 255)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, newBrightness)
    }
}
```

- [ ] **Step 5: Create `LaunchAppAction.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.action.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import xyz.nextalone.hyperzoomring.action.Action

object LaunchAppAction : Action {
    override val id = "launch_app"
    override val displayName = "启动应用"

    /** Package name to launch, set via config. */
    var targetPackage: String? = null

    override fun execute(context: Context, intensity: Float) {
        val pkg = targetPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Log.w("HyperZoomRing", "No launch intent for package: $pkg")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
```

- [ ] **Step 6: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: add Action interface, ActionRegistry, and built-in actions (volume, brightness, launch app)"
```

---

## Task 9: Wire Hook to Action Dispatch

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/hook/InputInterceptorHook.kt`
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/HookEntry.kt`

- [ ] **Step 1: Update `HookEntry.kt` to load config**

```kotlin
package xyz.nextalone.hyperzoomring

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.hook.InputInterceptorHook

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    override fun onHook() = encase {
        loadSystem {
            val configManager = ConfigManager.fromXPrefs(prefs.native)
            InputInterceptorHook.hook(this, configManager)
        }
    }
}
```

- [ ] **Step 2: Update `InputInterceptorHook.kt` to dispatch actions**

Replace the `// TODO: Task 8 will dispatch to ActionRegistry here` section. Update the `hook` function signature and add dispatch logic:

In `InputInterceptorHook.kt`, change the function signature from:
```kotlin
fun hook(param: PackageParam) = with(param) {
```
to:
```kotlin
fun hook(param: PackageParam, config: ConfigManager) = with(param) {
```

And replace:
```kotlin
                // TODO: Task 8 will dispatch to ActionRegistry here
```
with:
```kotlin
                // Skip if module is disabled or in camera mode
                if (!config.isEnabled || detector.isCameraMode) return@before

                // Dispatch to configured action
                val actionId = config.getActionId(gesture) ?: return@before
                val action = ActionRegistry.get(actionId) ?: return@before

                // Set LaunchAppAction target if needed
                if (action is LaunchAppAction) {
                    LaunchAppAction.targetPackage = config.getActionConfig(gesture)
                }

                try {
                    action.execute(appContext, intensity)
                } catch (e: Exception) {
                    Log.e(TAG, "Action execution failed: ${action.id}", e)
                }
```

Add the missing imports at the top:
```kotlin
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.action.actions.LaunchAppAction
import xyz.nextalone.hyperzoomring.config.ConfigManager
```

- [ ] **Step 3: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat: wire InputInterceptorHook to ActionRegistry with config-driven dispatch"
```

---

## Task 10: Settings UI — HomeScreen & GestureConfigScreen

**Files:**
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/HomeScreen.kt`
- Create: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/screen/GestureConfigScreen.kt`
- Modify: `app/src/main/kotlin/xyz/nextalone/hyperzoomring/ui/MainActivity.kt`

- [ ] **Step 1: Implement `HomeScreen.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onGestureClick: (GestureType) -> Unit = {},
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var isEnabled by remember { mutableStateOf(config.isEnabled) }
    var speedThreshold by remember { mutableFloatStateOf(config.speedThreshold.toFloat()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Module status
        SmallTitle(text = "模块状态")
        Spacer(modifier = Modifier.height(8.dp))

        SuperSwitch(
            title = "启用 HyperZoomRing",
            summary = if (isEnabled) "变焦环手势已启用" else "变焦环手势已禁用",
            checked = isEnabled,
            onCheckedChange = {
                isEnabled = it
                config.isEnabled = it
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Gesture cards
        SmallTitle(text = "手势配置")
        Spacer(modifier = Modifier.height(8.dp))

        GestureType.entries.forEach { gesture ->
            val actionId = config.getActionId(gesture)
            val actionName = actionId?.let { ActionRegistry.get(it)?.displayName } ?: "未设置"

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGestureClick(gesture) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = gesture.displayName)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "动作: $actionName")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Speed threshold slider
        SmallTitle(text = "速度阈值")
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "200ms 内事件数 > ${speedThreshold.toInt()} 判定为快转")
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            progress = speedThreshold,
            onProgressChange = {
                speedThreshold = it
                config.speedThreshold = it.toInt()
            },
            minValue = 2f,
            maxValue = 15f,
        )
    }
}
```

- [ ] **Step 2: Create `GestureConfigScreen.kt`**

```kotlin
package xyz.nextalone.hyperzoomring.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import xyz.nextalone.hyperzoomring.action.ActionRegistry
import xyz.nextalone.hyperzoomring.config.ConfigManager
import xyz.nextalone.hyperzoomring.ring.GestureType

@Composable
fun GestureConfigScreen(
    gestureType: GestureType,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = remember { ConfigManager.fromContext(context) }
    var selectedActionId by remember { mutableStateOf(config.getActionId(gestureType)) }

    val actions = remember { ActionRegistry.all() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        SmallTitle(text = "${gestureType.displayName} — 选择动作")

        Spacer(modifier = Modifier.height(16.dp))

        // "None" option
        SuperSwitch(
            title = "无动作",
            checked = selectedActionId == null,
            onCheckedChange = {
                if (it) {
                    selectedActionId = null
                    config.setActionId(gestureType, null)
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Action options
        actions.forEach { action ->
            SuperSwitch(
                title = action.displayName,
                checked = selectedActionId == action.id,
                onCheckedChange = { checked ->
                    if (checked) {
                        selectedActionId = action.id
                        config.setActionId(gestureType, action.id)
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // TODO: If LaunchAppAction is selected, show app picker.
        // This will be a follow-up enhancement once the core flow is validated.
        if (selectedActionId == "launch_app") {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "应用选择器将在后续版本实现")
        }
    }
}
```

- [ ] **Step 3: Update `MainActivity.kt` with gesture config navigation**

Replace the full `MainContent` composable:

```kotlin
@Composable
private fun MainContent() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingGesture by remember { mutableStateOf<GestureType?>(null) }

    val tabs = listOf(
        NavigationItem("主页", null),
        NavigationItem("诊断", null),
    )

    // If editing a gesture, show config screen
    val currentGesture = editingGesture
    if (currentGesture != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "手势配置",
                )
            }
        ) { padding ->
            GestureConfigScreen(
                gestureType = currentGesture,
                onBack = { editingGesture = null },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = "HyperZoomRing") },
        bottomBar = {
            NavigationBar(
                items = tabs,
                selected = selectedTab,
                onClick = { selectedTab = it }
            )
        }
    ) { padding ->
        when (selectedTab) {
            0 -> HomeScreen(
                modifier = Modifier.padding(padding),
                onGestureClick = { editingGesture = it }
            )
            1 -> DiagnosticScreen(modifier = Modifier.padding(padding))
        }
    }
}
```

Add the missing import in `MainActivity.kt`:
```kotlin
import xyz.nextalone.hyperzoomring.ring.GestureType
import xyz.nextalone.hyperzoomring.ui.screen.GestureConfigScreen
```

- [ ] **Step 4: Verify build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: implement HomeScreen with gesture cards and GestureConfigScreen with action picker"
```

---

## Task 11: Final Integration & Build Verification

**Files:**
- All files from previous tasks

- [ ] **Step 1: Run all unit tests**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:testDebugUnitTest`
Expected: All tests PASS

- [ ] **Step 2: Run full debug build**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify APK contains Xposed metadata**

Run: `cd /Volumes/Repository/Android/HyperZoomRing && unzip -p app/build/outputs/apk/debug/app-debug.apk META-INF/xposed/init 2>/dev/null || echo "Checking assets..." && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i xposed`
Expected: Xposed init file referencing `xyz.nextalone.hyperzoomring.HookEntry`

- [ ] **Step 4: Commit**

```bash
jj commit -m "chore: verify full build and Xposed module metadata"
```

---

## Verification Checklist

After all tasks are complete, verify on device:

1. Install APK via `adb install`
2. Enable module in LSPosed manager, select `system` scope
3. Reboot
4. Check logcat: `adb logcat -s HyperZoomRing` — should see "InputInterceptorHook loaded"
5. Open HyperZoomRing app → Diagnostic tab → rotate zoom ring → verify events appear
6. Configure gesture → action mapping in settings
7. Rotate zoom ring outside camera → verify action triggers
