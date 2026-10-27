/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private final GLRenderer renderer;
    private final XServer xServer;
    private final HostDisplayRefresh displayRefresh;

    public XServerView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        displayRefresh = new HostDisplayRefresh(this, xServer);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        com.winlator.core.FinalDisplayCadence.register(xServer, this::queueEvent);
        displayRefresh.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        renderer.setMenuBackdropBlur(0f);
        queueEvent(renderer::releaseMenuBackdropBlur);
        com.winlator.core.FinalDisplayCadence.unregister(xServer);
        com.winlator.core.GameVisualTrace.stopOwner(xServer);
        com.winlator.core.FramePipelineTrace.stopOwner(xServer);
        displayRefresh.stop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onResume() {
        super.onResume();
        displayRefresh.refresh();
    }

    @Override
    public void requestRender() {
        final com.winlator.core.RuntimePerformanceCounters performance = xServer != null ? xServer.performance : null;
        if (performance != null && com.winlator.core.RuntimeDiagnostics.enabled())
            performance.renderRequested(System.nanoTime());
        super.requestRender();
    }
}
