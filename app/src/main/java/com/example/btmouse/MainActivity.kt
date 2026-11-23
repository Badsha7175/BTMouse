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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val sensitivity = 1.8f

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            setStatus(
                if (registered) "Ready. Pair from laptop, then tap Connect."
                else "HID app not registered"
            )
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    errorShown = false
                    setStatus("Connected: ${device?.name ?: device?.address}")
                }
                BluetoothProfile.STATE_CONNECTING -> setStatus("Connecting...")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    host = null
                    setStatus("Disconnected")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, byteArrayOf(0, 0, 0, 0))
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            // Host protocol request acknowledged
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        checkPermissionsAndInit()
    }

    override fun onResume() {
        super.onResume()
        if (hid == null || !appRegistered) {
            checkPermissionsAndInit()
        }
    }

    private fun checkPermissionsAndInit() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ).filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 1)
            } else {
                initBluetooth()
            }
        } else {
            initBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initBluetooth()
        } else {
            setStatus("Bluetooth permissions are required")
        }
    }

    private fun initBluetooth() {
        val mgr = getSystemService(BluetoothManager::class.java)
        adapter = mgr?.adapter
        val a = adapter
        if (a == null || !a.isEnabled) {
            setStatus("Turn Bluetooth on, then return to app")
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
                    setStatus("HID service disconnected")
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHid() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Mouse",
            "Phone as Bluetooth mouse",
            "BTMouse",
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            DESCRIPTOR
        )
        hid?.registerApp(sdp, null, null, executor, hidCallback)
    }

    private fun makeDiscoverable() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Advertise permission not granted")
            return
        }
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        startActivity(i)
    }

    private fun pickDevice() {
        val a = adapter ?: return
        val list = a.bondedDevices.toList()
        if (list.isEmpty()) {
            toast("No paired devices. Pair with your laptop first.")
            return
        }
        val names = list.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select your laptop")
            .setItems(names) { _, i -> connectTo(list[i]) }
            .show()
    }

    private fun connectTo(d: BluetoothDevice) {
        val h = hid
        if (h == null) {
            toast("Error: Phone does not support Bluetooth HID or BT is off")
            initBluetooth()
            return
        }
        if (!appRegistered) {
            toast("HID app registering... try again in 2 seconds")
            registerHid()
            return
        }
        host = d
        h.connect(d)
        setStatus("Connecting to ${d.name ?: d.address}...")
    }

    private fun send(btn: Int, dx: Int, dy: Int, wheel: Int) {
        val h = hid ?: return
        val d = host ?: run {
            if (!errorShown) {
                errorShown = true
                setStatus("Not connected! Tap Connect first.")
            }
            return
        }
        val report = byteArrayOf(
            btn.toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
        val ok = h.sendReport(d, 0, report)
        if (!ok && !errorShown) {
            errorShown = true
            setStatus("Send failed! Unpair from both devices & pair again.")
        }
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private fun onPad(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                downTime = ev.eventTime
                moved = 0f
                maxPointers = 1
                accX = 0f; accY = 0f; accWheel = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                lastX = avgX(ev)
                lastY = avgY(ev)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount > 1) {
                    val keep = if (ev.actionIndex == 0) 1 else 0
                    lastX = ev.getX(keep)
                    lastY = ev.getY(keep)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val cx = avgX(ev)
                val cy = avgY(ev)
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx
                lastY = cy
                moved += abs(dx) + abs(dy)

                if (ev.pointerCount >= 2) {
                    // two finger scroll
                    accWheel += dy / 12f
                    val w = accWheel.toInt()
                    if (w != 0) {
                        accWheel -= w
                        send(buttons, 0, 0, w)
                    }
                } else {
                    accX += dx * sensitivity
                    accY += dy * sensitivity
                    val ix = accX.toInt()
                    val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        accX -= ix
                        accY -= iy
                        send(buttons, ix, iy, 0)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - downTime < 250 && moved < 24f
                if (quick) {
                    // one finger tap = left click, two finger tap = right click
                    val bit = if (maxPointers >= 2) 2 else 1
                    send(buttons or bit, 0, 0, 0)
                    send(buttons, 0, 0, 0)
                }
            }
        }
        return true
    }

    private fun holdButton(label: String, bit: Int): Button {
        val b = Button(this)
        b.text = label
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    buttons = buttons or bit
                    send(buttons, 0, 0, 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    buttons = buttons and bit.inv()
                    send(buttons, 0, 0, 0)
                }
            }
            true
        }
        return b
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(12), dp(12), dp(12))

        statusView = TextView(this)
        statusView.text = "Starting..."
        statusView.textSize = 16f
        statusView.gravity = Gravity.CENTER
        root.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val discBtn = Button(this)
        discBtn.text = "Make discoverable"
        discBtn.setOnClickListener { makeDiscoverable() }
        val connBtn = Button(this)
        connBtn.text = "Connect"
        connBtn.setOnClickListener { pickDevice() }
        top.addView(discBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(connBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        val pad = View(this)
        pad.setBackgroundColor(Color.parseColor("#DDDDDD"))
        pad.setOnTouchListener { _, ev -> onPad(ev) }
        val padLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        padLp.topMargin = dp(8)
        padLp.bottomMargin = dp(8)
        root.addView(pad, padLp)

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val left = holdButton("LEFT", 1)
        val right = holdButton("RIGHT", 2)
        bottom.addView(left, LinearLayout.LayoutParams(0, dp(64), 1f))
        bottom.addView(right, LinearLayout.LayoutParams(0, dp(64), 1f))
        root.addView(bottom)

        setContentView(root)
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusView.text = s }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            hid?.unregisterApp()
        } catch (_: Exception) {
        }
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        executor.shutdown()
    }
}
