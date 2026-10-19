/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.xserver.extensions

import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import com.winlator.xserver.*
import com.winlator.xserver.errors.BadValue
import com.winlator.xserver.errors.BadWindow
import com.winlator.xserver.errors.BadMatch
import com.winlator.xserver.errors.BadDrawable
import com.winlator.xserver.errors.XRequestError
import com.winlator.xserver.events.Event

/** RandR 1.1: a single resizable monitor, fixed orientation, 60 Hz. No fictitious 1.2+ CRTCs. */
class RandRExtension(server: XServer, opcode: Byte) : Extension(server, opcode), DynamicDisplay.Listener {
    private data class Size(val width: Int, val height: Int)
    private val sizes = arrayListOf(Size(server.screenInfo.width.toInt(), server.screenInfo.height.toInt()))
    private val ratesSupported = mutableMapOf<XClient, Boolean>()
    private val subscriptions = mutableMapOf<XClient, MutableSet<Int>>()
    // Same X timestamp epoch as the engine's core input/property events.
    private var timestamp = System.currentTimeMillis().toInt().let { if (it == 0) 1 else it }
    private var configTimestamp = timestamp
    private var sizeId = 0

    init {
        listOf(640 to 480, 800 to 600, 960 to 540, 1024 to 768, 1152 to 864,
            1280 to 720, 1280 to 800, 1280 to 960, 1280 to 1024, 1366 to 768,
            1404 to 648, 1600 to 900, 1600 to 1200, 1680 to 1050, 1920 to 1080,
            1920 to 1200, 2560 to 1080, 2560 to 1440, 2560 to 1600, 3440 to 1440,
            3840 to 2160).map { Size(it.first, it.second) }.forEach { if (it !in sizes) sizes += it }
        server.dynamicDisplay.addListener(this)
        server.windowManager.addOnResourceLifecycleListener(object : XResourceManager.OnResourceLifecycleListener {
            override fun onFreeResource(resource: XResource) {
                if (resource is Window) subscriptions.values.forEach { it.remove(resource.id) }
            }
        })
    }

    override fun getName() = "RANDR"
    override fun getEventCount(): Byte = 1

    private fun track(client: XClient) {
        if (ratesSupported.containsKey(client)) return
        ratesSupported[client] = false // 1.0 until QueryVersion says otherwise
        client.addOnDestroyListener { disconnected ->
            xServer.lock(XServer.Lockable.WINDOW_MANAGER).use {
                ratesSupported.remove(disconnected)
                subscriptions.remove(disconnected)
            }
        }
    }

    override fun handleRequest(client: XClient, input: XInputStream, output: XOutputStream) {
        xServer.lock(XServer.Lockable.WINDOW_MANAGER).use {
            track(client)
            when (client.requestData.toInt()) {
                0 -> {
                    requireLength(client, 8)
                    val major = input.readUnsignedInt()
                    val minor = input.readUnsignedInt()
                    val rates = major > 1 || major == 1L && minor >= 1
                    ratesSupported[client] = rates
                    output.lock().use {
                        replyHeader(output, client, 0, 0)
                        output.writeInt(1); output.writeInt(if (rates) 1 else 0); output.writePad(16)
                    }
                }
                2 -> setScreenConfig(client, input, output)
                4 -> {
                    requireLength(client, 8)
                    val window = requireWindow(input.readInt())
                    val mask = input.readUnsignedShort(); input.skip(2)
                    if (mask and 1.inv() != 0) throw BadValue(mask)
                    if (mask == 0) subscriptions[client]?.remove(window.id)
                    else {
                        subscriptions.getOrPut(client) { mutableSetOf() }.add(window.id)
                        // Supply an initial snapshot as well as subsequent changes, so clients that
                        // subscribe after another client switched modes cannot retain stale dimensions.
                        client.sendEvent(screenEvent(window.id))
                    }
                }
                5 -> {
                    requireLength(client, 4)
                    requireWindow(input.readInt())
                    getScreenInfo(client, output)
                }
                else -> throw XRequestError(1, 0) // BadRequest, including unsupported RandR 1.2+ opcodes
            }
        }
    }

    private fun getScreenInfo(client: XClient, output: XOutputStream) {
        val rates = ratesSupported[client] == true
        val extraBytes = sizes.size * (if (rates) 12 else 8)
        output.lock().use {
            replyHeader(output, client, 1, extraBytes / 4)
            output.writeInt(xServer.windowManager.rootWindow.id)
            output.writeInt(timestamp); output.writeInt(configTimestamp)
            output.writeShort(sizes.size.toShort()); output.writeShort(sizeId.toShort())
            output.writeShort(1); output.writeShort(if (rates) 60 else 0)
            output.writeShort((if (rates) sizes.size * 2 else 0).toShort()); output.writeShort(0)
            sizes.forEach { size ->
                output.writeShort(size.width.toShort()); output.writeShort(size.height.toShort())
                output.writeShort((size.width / 10).toShort()); output.writeShort((size.height / 10).toShort())
            }
            if (rates) sizes.forEach { output.writeShort(1); output.writeShort(60) }
        }
    }

    private fun setScreenConfig(client: XClient, input: XInputStream, output: XOutputStream) {
        val rates = ratesSupported[client] == true
        requireLength(client, if (rates) 20 else 16)
        val drawable = input.readInt()
        xServer.lock(XServer.Lockable.DRAWABLE_MANAGER).use {
            if (xServer.drawableManager.getDrawable(drawable) == null) throw BadDrawable(drawable)
        }
        val requestedTime = input.readInt()
        val requestedConfig = input.readInt()
        val index = input.readUnsignedShort()
        val rotation = input.readUnsignedShort()
        val rate = if (rates) input.readUnsignedShort().also { input.skip(2) } else 0
        if (requestedConfig == configTimestamp) {
            if (index !in sizes.indices) throw BadValue(index)
            if (rotation and 15 !in listOf(1, 2, 4, 8)) throw BadValue(rotation)
            if (rotation != 1) throw BadMatch()
            if (rate != 0 && rate != 60) throw BadValue(rate)
        }
        // Signed differences implement X's 32-bit timestamp wraparound comparisons.
        val now = System.currentTimeMillis().toInt().let { if (it - timestamp < 0) timestamp else it }
        val status = when {
            requestedConfig != configTimestamp -> 1 // InvalidConfigTime
            requestedTime != 0 && (requestedTime - timestamp < 0 || requestedTime - now > 0) -> 2
            else -> {
                val size = sizes[index]
                if (xServer.dynamicDisplay.resize(size.width, size.height, "RANDR")) 0 else 3
            }
        }
        output.lock().use {
            replyHeader(output, client, status, 0)
            output.writeInt(timestamp); output.writeInt(configTimestamp)
            output.writeInt(xServer.windowManager.rootWindow.id)
            output.writeShort(0); output.writePad(10)
        }
    }

    override fun onDisplaySizeChanged(previous: ScreenInfo, current: ScreenInfo, source: String) {
        val size = Size(current.width.toInt(), current.height.toInt())
        val now = System.currentTimeMillis().toInt()
        timestamp = if (now - timestamp > 0) now else timestamp + 1
        if (size !in sizes) {
            // Mode IDs are stable until the configuration timestamp changes. Bound custom-mode storage.
            if (sizes.size >= 128) sizes.removeAt(sizes.lastIndex)
            sizes += size
            configTimestamp = timestamp
        }
        sizeId = sizes.indexOf(size)
        subscriptions.forEach { (client, windows) -> windows.forEach { client.sendEvent(screenEvent(it)) } }
    }

    private fun screenEvent(window: Int): Event {
        val size = sizes[sizeId]
        val eventTime = timestamp
        val eventConfig = configTimestamp
        val eventSizeId = sizeId
        val root = xServer.windowManager.rootWindow.id
        return object : Event(firstEventId.toInt() and 0xff) {
            override fun send(sequenceNumber: Short, output: XOutputStream) {
                output.lock().use {
                    output.writeByte(code); output.writeByte(1); output.writeShort(sequenceNumber)
                    output.writeInt(eventTime); output.writeInt(eventConfig)
                    output.writeInt(root); output.writeInt(window)
                    output.writeShort(eventSizeId.toShort()); output.writeShort(0)
                    output.writeShort(size.width.toShort()); output.writeShort(size.height.toShort())
                    output.writeShort((size.width / 10).toShort()); output.writeShort((size.height / 10).toShort())
                }
            }
        }
    }

    private fun requireWindow(id: Int): Window = xServer.windowManager.getWindow(id) ?: throw BadWindow(id)

    private fun requireLength(client: XClient, length: Int) {
        if (client.requestLength != length) throw XRequestError(16, 0) // BadLength
    }

    private fun replyHeader(output: XOutputStream, client: XClient, data: Int, length: Int) {
        output.writeByte(1); output.writeByte(data.toByte()); output.writeShort(client.sequenceNumber)
        output.writeInt(length)
    }
}
