package com.example.btmouse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : Activity() {

    companion object {
        // HID report descriptor: 3 buttons + X + Y + wheel (4 byte report, no report id)
        private val DESCRIPTOR: ByteArray = intArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1, 0x01,       // Collection (Application)
            0x09, 0x01,       //   Usage (Pointer)
            0xA1, 0x00,       //   Collection (Physical)
            0x05, 0x09,       //     Usage Page (Buttons)
            0x19, 0x01,       //     Usage Minimum (1)
            0x29, 0x03,       //     Usage Maximum (3)
            0x15, 0x00,       //     Logical Minimum (0)
            0x25, 0x01,       //     Logical Maximum (1)
            0x95, 0x03,       //     Report Count (3)
            0x75, 0x01,       //     Report Size (1)
            0x81, 0x02,       //     Input (Data, Var, Abs)
            0x95, 0x01,       //     Report Count (1)
            0x75, 0x05,       //     Report Size (5)
            0x81, 0x03,       //     Input (Const) padding
            0x05, 0x01,       //     Usage Page (Generic Desktop)
            0x09, 0x30,       //     Usage (X)
            0x09, 0x31,       //     Usage (Y)
            0x09, 0x38,       //     Usage (Wheel)
            0x15, 0x81,       //     Logical Minimum (-127)
            0x25, 0x7F,       //     Logical Maximum (127)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x03,       //     Report Count (3)
            0x81, 0x06,       //     Input (Data, Var, Rel)
            0xC0,             //   End Collection
            0xC0              // End Collection
        ).map { it.toByte() }.toByteArray()

        // Modern Dark Theme Colors
        private const val BG_DARK = "#0F172A"
        private const val CARD_BG = "#1E293B"
        private const val PAD_BG = "#162032"
        private const val BORDER_COLOR = "#334155"
        private const val ACCENT_BLUE = "#3B82F6"
        private const val ACCENT_CYAN = "#06B6D4"
        private const val ACCENT_GREEN = "#10B981"
        private const val ACCENT_RED = "#EF4444"
        private const val ACCENT_AMBER = "#F59E0B"
        private const val TEXT_PRIMARY = "#F8FAFC"
        private const val TEXT_MUTED = "#94A3B8"
    }

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var appRegistered = false
    private var buttons = 0
    private var errorShown = false
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusView: TextView

    // touch state
    private var lastX = 0f
    private var lastY = 0f
    private var accX = 0f
    private var accY = 0f
    private var accWheel = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0
    private var sensitivity = 1.0f

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (registered) {
                setStatus("● Ready — Pair from laptop, then tap Connect", ACCENT_CYAN)
            } else {
                setStatus("● HID app not registered", ACCENT_AMBER)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    errorShown = false
                    setStatus("● Connected: ${device?.name ?: device?.address}", ACCENT_GREEN)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    setStatus("● Connecting...", ACCENT_AMBER)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    host = null
                    setStatus("● Disconnected", ACCENT_RED)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            try {
                hid?.replyReport(device, type, id, byteArrayOf(0, 0, 0, 0))
            } catch (_: Exception) {
            }
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            // Host protocol request acknowledged
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // স্ট্যাটাস বার ও নেভিগেশন বার ডার্ক থিমের সাথে ম্যাচ করা
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.parseColor(BG_DARK)
        window.navigationBarColor = Color.parseColor(BG_DARK)

        buildUi()

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ),
                1
            )
        } else {
            initBluetooth()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hid == null || !appRegistered) {
            if (Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                initBluetooth()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            initBluetooth()
        } else {
            setStatus("● Nearby devices permission required!", ACCENT_RED)
        }
    }

    private fun initBluetooth() {
        try {
            val mgr = getSystemService(BluetoothManager::class.java)
            adapter = mgr?.adapter
            val a = adapter
            if (a == null || !a.isEnabled) {
                setStatus("● Turn Bluetooth ON, then return to app", ACCENT_AMBER)
                return
            }
            if (hid != null) {
                if (!appRegistered) registerHid()
                return
            }
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hid = proxy as BluetoothHidDevice
                        registerHid()
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hid = null
                        appRegistered = false
                        setStatus("● HID service disconnected", ACCENT_RED)
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            setStatus("● Bluetooth error: ${e.message}", ACCENT_RED)
        }
    }

    private fun registerHid() {
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "BT Mouse",
                "Phone as Bluetooth mouse",
                "BTMouse",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                DESCRIPTOR
            )
            hid?.registerApp(sdp, null, null, executor, hidCallback)
        } catch (e: Exception) {
            setStatus("● HID register error: ${e.message}", ACCENT_RED)
        }
    }

    private fun makeDiscoverable() {
        try {
            if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE), 2)
                return
            }
            val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            startActivity(i)
        } catch (_: Exception) {
            toast("Open phone Bluetooth settings to pair")
        }
    }

    private fun pickDevice() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val a = adapter ?: run {
            initBluetooth()
            toast("Bluetooth not
