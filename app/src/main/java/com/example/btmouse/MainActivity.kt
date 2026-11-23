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
            toast("Bluetooth not ready")
            return
        }
        val list = a.bondedDevices?.toList() ?: emptyList()
        if (list.isEmpty()) {
            toast("No paired devices. Pair with your laptop first.")
            return
        }
        val names = list.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Select your laptop")
            .setItems(names) { _, i -> connectTo(list[i]) }
            .show()
    }

    private fun connectTo(d: BluetoothDevice) {
        val h = hid
        if (h == null) {
            toast("HID not ready. Make sure Bluetooth is ON.")
            initBluetooth()
            return
        }
        if (!appRegistered) {
            toast("Registering HID... try again in 2 seconds")
            registerHid()
            return
        }
        try {
            host = d
            h.connect(d)
            setStatus("● Connecting to ${d.name ?: d.address}...", ACCENT_AMBER)
        } catch (e: Exception) {
            setStatus("● Connect failed: ${e.message}", ACCENT_RED)
        }
    }

    private fun send(btn: Int, dx: Int, dy: Int, wheel: Int) {
        val h = hid ?: return
        val d = host ?: run {
            if (!errorShown) {
                errorShown = true
                setStatus("● Not connected! Tap Connect first.", ACCENT_AMBER)
            }
            return
        }
        try {
            val report = byteArrayOf(
                btn.toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
            val ok = h.sendReport(d, 0, report)
            if (!ok && !errorShown) {
                errorShown = true
                setStatus("● Send failed! Unpair both devices & pair again.", ACCENT_RED)
            }
        } catch (_: Exception) {
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

    private fun roundedBox(bgHex: String, strokeHex: String, radiusDp: Int, strokeWidthDp: Int = 1): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(radiusDp).toFloat()
        drawable.setColor(Color.parseColor(bgHex))
        drawable.setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
        return drawable
    }

    private fun styledButton(label: String, bgHex: String, textHex: String = TEXT_PRIMARY): Button {
        val base = roundedBox(bgHex, BORDER_COLOR, 14, 1)
        val ripple = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#40FFFFFF")), base, null)
        val btn = Button(this)
        btn.text = label
        btn.setTextColor(Color.parseColor(textHex))
        btn.textSize = 14f
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.isAllCaps = false
        btn.background = ripple
        btn.stateListAnimator = null
        return btn
    }

    private fun holdButton(label: String, bit: Int, bgHex: String): Button {
        val normalBg = roundedBox(bgHex, BORDER_COLOR, 16, 1)
        val pressedBg = roundedBox(ACCENT_BLUE, ACCENT_CYAN, 16, 2)
        val b = Button(this)
        b.text = label
        b.setTextColor(Color.parseColor(TEXT_PRIMARY))
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.isAllCaps = false
        b.background = normalBg
        b.stateListAnimator = null

        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    b.background = pressedBg
                    buttons = buttons or bit
                    send(buttons, 0, 0, 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    b.background = normalBg
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
        root.setBackgroundColor(Color.parseColor(BG_DARK))
        root.setPadding(dp(14), dp(14), dp(14), dp(14))

        // ১. একদম ওপরে কার্সার স্পিড স্লাইডার কার্ড (Range)
        val speedCard = LinearLayout(this)
        speedCard.orientation = LinearLayout.HORIZONTAL
        speedCard.gravity = Gravity.CENTER_VERTICAL
        speedCard.background = roundedBox(CARD_BG, BORDER_COLOR, 14, 1)
        speedCard.setPadding(dp(14), dp(10), dp(14), dp(10))

        val speedLabel = TextView(this)
        speedLabel.text = "⚡ Speed: 1.0x"
        speedLabel.setTextColor(Color.parseColor(ACCENT_CYAN))
        speedLabel.textSize = 14f
        speedLabel.typeface = Typeface.DEFAULT_BOLD

        val speedBar = SeekBar(this)
        speedBar.max = 27
        speedBar.progress = 7
        speedBar.progressTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
        speedBar.thumbTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_BLUE))
        speedBar.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor(BORDER_COLOR))
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivity = 0.3f + (progress * 0.1f)
                speedLabel.text = String.format(Locale.US, "⚡ Speed: %.1fx", sensitivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        speedCard.addView(speedLabel, LinearLayout.LayoutParams(dp(115), LinearLayout.LayoutParams.WRAP_CONTENT))
        speedCard.addView(speedBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val speedLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        speedLp.bottomMargin = dp(10)
        root.addView(speedCard, speedLp)

        // ২. স্ট্যাটাস ব্যাজ
        statusView = TextView(this)
        statusView.text = "● Starting..."
        statusView.setTextColor(Color.parseColor(ACCENT_CYAN))
        statusView.textSize = 14f
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.gravity = Gravity.CENTER
        statusView.background = roundedBox(CARD_BG, BORDER_COLOR, 12, 1)
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10))
        val statusLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statusLp.bottomMargin = dp(10)
        root.addView(statusView, statusLp)

        // ৩. অ্যাকশন বাটন (Discoverable & Connect)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val discBtn = styledButton("📡 Discoverable", CARD_BG, ACCENT_CYAN)
        discBtn.setOnClickListener { makeDiscoverable() }
        val connBtn = styledButton("🔗 Connect", ACCENT_BLUE, TEXT_PRIMARY)
        connBtn.setOnClickListener { pickDevice() }

        val discLp = LinearLayout.LayoutParams(0, dp(48), 1f)
        discLp.rightMargin = dp(6)
        val connLp = LinearLayout.LayoutParams(0, dp(48), 1f)
        connLp.leftMargin = dp(6)
        top.addView(discBtn, discLp)
        top.addView(connBtn, connLp)
        val topLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        topLp.bottomMargin = dp(12)
        root.addView(top, topLp)

        // ৪. আকর্ষণীয় টাচপ্যাড এরিয়া
        val padContainer = FrameLayout(this)
        padContainer.background = roundedBox(PAD_BG, ACCENT_BLUE, 20, 1)
        padContainer.setOnTouchListener { _, ev -> onPad(ev) }

        val padHint = TextView(this)
        padHint.text = "TOUCHPAD\n\n1 Finger: Move & Tap (Left Click)\n2 Fingers: Scroll & Tap (Right Click)"
        padHint.setTextColor(Color.parseColor(TEXT_MUTED))
        padHint.alpha = 0.45f
        padHint.textSize = 13f
        padHint.gravity = Gravity.CENTER
        padContainer.addView(padHint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        val padLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        padLp.bottomMargin = dp(12)
        root.addView(padContainer, padLp)

        // ৫. নিচের মাউস ক্লিক বাটনগুলো (LEFT, MIDDLE, RIGHT)
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        val left = holdButton("LEFT", 1, CARD_BG)
        val middle = holdButton("MID", 4, CARD_BG)
        val right = holdButton("RIGHT", 2, CARD_BG)

        val leftLp = LinearLayout.LayoutParams(0, dp(68), 2f)
        leftLp.rightMargin = dp(6)
        val midLp = LinearLayout.LayoutParams(0, dp(68), 1f)
        midLp.leftMargin = dp(3)
        midLp.rightMargin = dp(3)
        val rightLp = LinearLayout.LayoutParams(0, dp(68), 2f)
        rightLp.leftMargin = dp(6)

        bottom.addView(left, leftLp)
        bottom.addView(middle, midLp)
        bottom.addView(right, rightLp)
        root.addView(bottom)

        setContentView(root)
    }

    private fun setStatus(s: String, colorHex: String = ACCENT_CYAN) {
        runOnUiThread {
            statusView.text = s
            statusView.setTextColor(Color.parseColor(colorHex))
        }
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
        try {
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {
        }
        executor.shutdown()
    }
}
