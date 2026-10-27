/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.core.GPUHelper;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.core.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.PresentIdleQueue;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XResource;
import com.winlator.xserver.XResourceManager;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentConfigureNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PresentExtension extends Extension implements WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 0;
    public enum CompleteKind {PIXMAP, MSC_NOTIFY}
    public enum CompleteMode {COPY, FLIP, SKIP}
    private static final byte FLAG_WINDOW_DESTROYED = (1<<0);
    private final SparseArray<PresentEvent> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private long eglContextPtr;
    private ScheduledThreadPoolExecutor idleNotifyScheduler;
    private final PresentIdleQueue idleNotifications = new PresentIdleQueue();
    private final Texture.SourceCompletion sourceCompletion = (source, trace) ->
        com.winlator.renderer.PresentCompletionWait.await(idleNotifications,
            source instanceof GPUImage ? ((GPUImage)source).getHardwareBufferId() : 0, trace);

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class PresentEvent {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    public PresentExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getEventCount() {
        return 3;
    }

    private void sendConfigureNotify(Window window, int pixmapFlags) {
        if (events.size() == 0) return;
        int mask = (1<<PresentConfigureNotify.PRESENT_CONFIGURE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentConfigureNotify(this, event.id, window, pixmapFlags));
                }
            }
        }
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);
        if (events.size() == 0) return;
        int mask = (1<<PresentIdleNotify.PRESENT_IDLE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentIdleNotify(this, event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendIdleNotifyScheduled(final Window window, final Pixmap pixmap, final int serial, final int idleFence,
            final long bufferId, final com.winlator.core.FramePipelineCapture.Frame trace) {
        long now = idleNotifications.nowNs();
        long lastIdleTime = xServer.presentTiming.nextIdleDeadline((long)window.getTag("lastIdleTime", now), now);
        long delayNanos = lastIdleTime - now;
        window.setTag("lastIdleTime", lastIdleTime);

        if (idleNotifyScheduler == null) {
            idleNotifyScheduler = new ScheduledThreadPoolExecutor(1);
            idleNotifyScheduler.setKeepAliveTime(5, TimeUnit.SECONDS);
            idleNotifyScheduler.allowCoreThreadTimeOut(true);
            idleNotifyScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            idleNotifyScheduler.setRemoveOnCancelPolicy(true);
        }
        final long deadline = lastIdleTime;
        if (trace != null) trace.idleDue(deadline);
        final ScheduledThreadPoolExecutor scheduler = idleNotifyScheduler;
        final com.winlator.core.RuntimePerformanceCounters performance = xServer.performance;
        if (performance != null) performance.idleScheduled(delayNanos);
        PresentIdleQueue.Ticket ticket = idleNotifications.enqueue(window, bufferId, deadline, source -> {
            // All drain sites hold WINDOW_MANAGER. Keep object/session identity
            // checks even when a stale timer has already acquired its CPU thread.
            if (idleNotifyScheduler == scheduler && xServer.windowManager.getWindow(window.id) == window) {
                if (performance != null && com.winlator.core.RuntimeDiagnostics.enabled())
                    performance.idleDispatched(idleNotifications.nowNs() - deadline);
                if (trace != null) { trace.idleDispatchSource(source); trace.mark(13); }
                sendIdleNotify(window, pixmap, serial, idleFence);
                if (trace != null) trace.mark(14);
            }
        });
        if (trace != null) trace.idleReservation(ticket);
        idleNotifications.attachFuture(ticket, scheduler.schedule(() -> {
            if (trace != null) trace.idleTimerWake();
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                if (trace != null) trace.idleTimerLocked();
                if (idleNotifyScheduler == scheduler) idleNotifications.dispatchDue(false);
            }
        }, Math.max(0, deadline - idleNotifications.nowNs()), TimeUnit.NANOSECONDS));
    }

    public String idleDiagnostic() { return idleNotifications.diagnostic(); }

    private void sendCompleteNotify(Window window, int serial, CompleteKind kind, CompleteMode mode, long ust, long msc) {
        if (events.size() == 0) return;
        int mask = (1<<PresentCompleteNotify.PRESENT_COMPLETE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentCompleteNotify(this, event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
    }

    private void createCopyEGLContext(XClient client) {
        eglContextPtr = GPUHelper.createOffscreenEGLContext(true);
        client.addOnDestroyListener((unused) -> {
            GPUHelper.destroyOffscreenEGLContext(eglContextPtr);
            eglContextPtr = 0;
        });
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream, long outerLockNs, long requestNs) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(8);
        long targetMSC = inputStream.readLong();
        inputStream.skip(client.getRemainingRequestLength());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);

        Texture source = pixmap != null ? pixmap.drawable.getTexture() : null;
        // Do not advertise an aliased source as reusable just before reading it
        // again. Unknown imported allocation IDs use the conservative path.
        Long protectedBuffer = source instanceof GPUImage && ((GPUImage)source).isImportedHardwareBuffer()
            ? ((GPUImage)source).getHardwareBufferId() : null;
        idleNotifications.dispatchDue(PresentIdleQueue.BEFORE_COPY, protectedBuffer);

        Drawable content = window.getContent();
        if (pixmap != null && content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        final com.winlator.core.RuntimePerformanceCounters performance = xServer.performance;
        final long lockStart = performance != null ? System.nanoTime() : 0;
        final com.winlator.core.FramePipelineCapture pipeline = com.winlator.core.FramePipelineTrace.capture(xServer);
        final com.winlator.core.FramePipelineCapture.Frame trace = pipeline != null && pixmap != null ? pipeline.begin(content, requestNs) : null;
        if (trace != null && client.diagnosticDispatch != null)
            client.diagnosticDispatch.linked(pipeline.getTransport(), trace.id());
        boolean ready = false;
        try {
        synchronized (content.renderLock) {
            if (trace != null) trace.mark(3);
            final long lockNs = performance != null ? outerLockNs + System.nanoTime() - lockStart : 0;
            if (pixmap != null) {
                com.winlator.renderer.GamePixelGl.beforePresent(xServer, content);
                Texture srcTexture = pixmap.drawable.getTexture();
                final long copyStart = performance != null ? System.nanoTime() : 0;
                if (srcTexture instanceof GPUImage) {
                    if (eglContextPtr == 0) createCopyEGLContext(client);
                    if (((GPUImage)srcTexture).isImportedHardwareBuffer() &&
                        GPUImage.createOrObtainGpuTarget(xServer, content) == null)
                        throw new com.winlator.xserver.errors.BadAlloc();
                    content.setData(null);
                    if (!content.getTexture().copyFromSource(srcTexture, trace, sourceCompletion))
                        throw new com.winlator.xserver.errors.BadAlloc();
                    com.winlator.renderer.GameVisualGl.copy(xServer, content, srcTexture, content.getTexture());
                    com.winlator.renderer.GamePixelGl.copy(xServer, content, srcTexture, content.getTexture());
                    if (trace != null) trace.ready();
                    ready = true;
                    content.forceUpdate();
                }
                else {
                    if (trace != null) trace.mark(6);
                    content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
                    if (trace != null) { trace.mark(7); trace.ready(); }
                    ready = true;
                }

                sendCopyComplete(window, serial);
                if (trace != null) trace.mark(11);

                if (performance != null && com.winlator.core.RuntimeDiagnostics.enabled()) {
                    final long copyEnd = System.nanoTime();
                    performance.present(windowId, copyEnd, lockNs, copyEnd - copyStart, srcTexture instanceof GPUImage);
                }

                com.winlator.core.JitPresentTimeline.present(xServer, windowId);

                if (targetMSC > 0) {
                    sendIdleNotifyScheduled(window, pixmap, serial, idleFence,
                        srcTexture instanceof GPUImage ? ((GPUImage)srcTexture).getHardwareBufferId() : 0, trace);
                }
                else {
                    if (trace != null) { trace.idleDue(System.nanoTime()); trace.idleDispatchSource(4); trace.mark(13); }
                    sendIdleNotify(window, pixmap, serial, idleFence);
                    if (trace != null) trace.mark(14);
                }
            }
            else {
                content.forceUpdate();
                sendCopyComplete(window, serial);
            }
        }
        } finally { if (!ready && trace != null) trace.failed(); }
    }

    private void sendCopyComplete(Window window, int serial) {
        final long now = System.nanoTime();
        sendCompleteNotify(window, serial, CompleteKind.PIXMAP, CompleteMode.COPY,
            now / 1000L, xServer.presentTiming.mediaStreamCounter(now));
    }

    /** Called after the X connector stops; no old Idle work may survive into a restart. */
    public void stop() {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            idleNotifications.clear();
            if (idleNotifyScheduler != null) {
                idleNotifyScheduler.shutdownNow();
                idleNotifyScheduler = null;
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        window.removeTag("lastIdleTime");
        GPUImage.createOrObtain(xServer, window.getContent(), true, true);

        if (eventId > 0) {
            synchronized (events) {
                PresentEvent event = events.get(eventId);
                if (event != null) {
                    if (event.window != window || event.client != client) throw new BadMatch();

                    if (!mask.isEmpty()) {
                        event.mask = mask;
                    }
                    else events.remove(eventId);
                }
                else {
                    event = new PresentEvent();
                    event.id = eventId;
                    event.window = window;
                    event.client = client;
                    event.mask = mask;
                    events.put(eventId, event);
                }
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = (SyncExtension)xServer.getExtensionByName("SYNC");

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                final long requestNs = com.winlator.core.FramePipelineTrace.capture(xServer) != null ? System.nanoTime() : 0;
                final long lockStart = xServer.performance != null ? System.nanoTime() : 0;
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    // A due notification can otherwise lose this non-fair lock
                    // repeatedly to new Presents and wait through another GPU copy.
                    // Only completed, expired copies are drained; the current
                    // image still follows copy/finish -> Complete -> queued Idle.
                    presentPixmap(client, inputStream, outputStream, lockStart != 0 ? System.nanoTime() - lockStart : 0, requestNs);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) {
            idleNotifications.cancelOwner(resource);
            sendConfigureNotify((Window)resource, FLAG_WINDOW_DESTROYED);
            synchronized (events) {
                for (int i = events.size()-1; i >= 0; i--) {
                    PresentEvent event = events.valueAt(i);
                    if (event.window == resource) events.removeAt(i);
                }
            }
        }
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        window.removeTag("lastIdleTime");
        sendConfigureNotify(window, 0);
    }
}
