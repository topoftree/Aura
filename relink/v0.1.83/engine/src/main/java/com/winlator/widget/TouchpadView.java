/* Aura 0.1.82 local modified file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.core.AppUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.ViewTransformation;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.DynamicDisplay;
import com.winlator.xserver.XServer;
import com.winlator.xserver.XLock;

public class TouchpadView extends View implements View.OnCapturedPointerListener {
    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.5f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private Finger mouseMoveFinger = null;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private boolean moveCursorToTouchpoint = false;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private final XServer xServer;
    private Runnable fourFingersTapCallback;
    private Runnable pointerActivityCallback;
    private final float[] xform = XForm.getInstance();
    private ScreenInfo inputScreenInfo;
    private boolean inputStretched;
    private final Runnable refreshInputViewport = this::ensureInputViewport;
    private final DynamicDisplay.Listener displayListener = (previous, current, source) -> {
        removeCallbacks(refreshInputViewport);
        post(refreshInputViewport);
    };

    public void setPointerActivityCallback(Runnable callback) { pointerActivityCallback = callback; }
    private void notifyPointerActivity() {
        if (pointerActivityCallback != null) pointerActivityCallback.run();
    }

    public TouchpadView(Context context, XServer xServer, boolean capturePointerOnExternalMouse) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBackground());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(capturePointerOnExternalMouse);
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo);

        if (capturePointerOnExternalMouse) {
            setOnCapturedPointerListener(this);
            setOnClickListener(view -> {
                if (requestFocus()) requestPointerCapture();
            });
        }
    }

    private static StateListDrawable createTransparentBackground() {
        StateListDrawable stateListDrawable = new StateListDrawable();
        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);

        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[0], defaultDrawable);
        return stateListDrawable;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        xServer.dynamicDisplay.addListener(displayListener);
        ensureInputViewport();
    }

    @Override
    protected void onDetachedFromWindow() {
        xServer.dynamicDisplay.removeListener(displayListener);
        removeCallbacks(refreshInputViewport);
        cancelViewportGestures();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cancelViewportGestures();
        updateXform(w, h, xServer.screenInfo);
    }

    private void updateXform(int outerWidth, int outerHeight, ScreenInfo current) {
        if (outerWidth <= 0 || outerHeight <= 0) return;
        ViewTransformation viewTransformation = new ViewTransformation();
        inputStretched = xServer.getRenderer() != null && xServer.getRenderer().isFullscreen();
        viewTransformation.pointerTransform(xform, outerWidth, outerHeight, current.width, current.height, inputStretched);
        inputScreenInfo = current;
    }

    private void ensureInputViewport() {
        ScreenInfo current = xServer.screenInfo;
        boolean stretched = xServer.getRenderer() != null && xServer.getRenderer().isFullscreen();
        if (current == inputScreenInfo && stretched == inputStretched) return;
        cancelViewportGestures();
        updateXform(getWidth() > 0 ? getWidth() : AppUtils.getScreenWidth(),
                getHeight() > 0 ? getHeight() : AppUtils.getScreenHeight(), current);
    }

    private void cancelViewportGestures() {
        // Old touch deltas/taps are in a different coordinate system. Release only buttons owned by
        // these gestures, preserving physical mouse and controller state.
        if (fingerPointerButtonLeft != null) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        if (fingerPointerButtonRight != null) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        fingerPointerButtonLeft = null;
        fingerPointerButtonRight = null;
        for (int i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
        mouseMoveFinger = null;
        numFingers = 0;
        scrollAccumY = 0;
        scrolling = false;
    }

    private class Finger {
        private int x;
        private int y;
        private final int startX;
        private final int startY;
        private int lastX;
        private int lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = this.startX = this.lastX = (int)transformedPoint[0];
            this.y = this.startY = this.lastY = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x;
            lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) {
                dx *= CURSOR_ACCELERATION;
            }
            return Mathf.roundPoint(dx);
        }

        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) {
                dy *= CURSOR_ACCELERATION;
            }
            return Mathf.roundPoint(dy);
        }

        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        private float travelDistance() {
            return (float)Math.hypot(x - startX, y - startY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            return handleTouchEvent(event);
        }
    }

    private boolean handleTouchEvent(MotionEvent event) {
        ensureInputViewport();
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (isEnabled()) {
                        xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                        notifyPointerActivity();
                    }
                }
                else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(fingers[i]);
                            }
                            else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }

        return true;
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (finger1.isTap()) {
                    if (moveCursorToTouchpoint) xServer.injectPointerMove(finger1.x, finger1.y);
                    pressPointerButtonLeft(finger1);
                }
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1);
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i++) {
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    }
                    fourFingersTapCallback.run();
                }
                break;
        }

        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        if (!isEnabled()) return;
        boolean skipPointerMove = false;

        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            final float resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
            float currDistance = (float)Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;

            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - (finger1.lastY + finger2.lastY) * 0.5f;

                if (scrollAccumY < -100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                }
                else if (scrollAccumY > 100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            }
            else if (currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                     finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }

        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            if (finger1.x != finger1.lastX || finger1.y != finger1.lastY) notifyPointerActivity();
            if (moveCursorToTouchpoint && numFingers == 1) {
                xServer.injectPointerMove(finger1.x, finger1.y);
            }
            else {
                int dx = finger1.deltaX();
                int dy = finger1.deltaY();

                WinHandler winHandler = xServer.getWinHandler();
                if (xServer.isRelativeMouseMovement()) {
                    winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                }
                else xServer.injectPointerMoveDelta(dx, dy);
            }
        }
    }

    public void mouseMove(float x, float y, int action) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            handleMouseMove(x, y, action);
        }
    }

    private void handleMouseMove(float x, float y, int action) {
        ensureInputViewport();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mouseMoveFinger = new Finger(x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mouseMoveFinger != null) {
                    mouseMoveFinger.update(x, y);
                    handleFingerMove(mouseMoveFinger);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mouseMoveFinger = null;
                break;
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (isEnabled() && pointerButtonLeftEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            fingerPointerButtonLeft = finger;
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (isEnabled() && pointerButtonRightEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            fingerPointerButtonRight = finger;
        }
    }

    private void releasePointerButtonLeft(final Finger finger) {
        if (isEnabled() && pointerButtonLeftEnabled && finger == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                if (fingerPointerButtonLeft != finger) return;
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(final Finger finger) {
        if (isEnabled() && pointerButtonRightEnabled && finger == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> {
                if (fingerPointerButtonRight != finger) return;
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = null;
            }, 30);
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public boolean isMoveCursorToTouchpoint() {
        return moveCursorToTouchpoint;
    }

    public void setMoveCursorToTouchpoint(boolean moveCursorToTouchpoint) {
        this.moveCursorToTouchpoint = moveCursorToTouchpoint;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            return handleExternalMouseEvent(event);
        }
    }

    private boolean handleExternalMouseEvent(MotionEvent event) {
        ensureInputViewport();
        boolean handled = false;
        if (isEnabled() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                    else if (scrollY >= 1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                    handled = true;
                    break;
            }
        }
        if (handled) notifyPointerActivity();
        return handled;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        ensureInputViewport();
        final float[] result = {0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    @Override
    public boolean onCapturedPointer(View view, MotionEvent event) {
        ensureInputViewport();
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;

            float dy = event.getY() * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;

            xServer.injectPointerMoveDelta(Mathf.roundPoint(dx), Mathf.roundPoint(dy));
            if (dx != 0 || dy != 0) notifyPointerActivity();
            return true;
        }
        else {
            event.setSource(event.getSource() | InputDevice.SOURCE_MOUSE);
            return onExternalMouseEvent(event);
        }
    }
}
