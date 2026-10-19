/* Aura 0.1.82 local modified file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.GameStartupUi;
import com.winlator.core.GameStartupStage;
import com.winlator.core.GameStartupPresentation;
import com.winlator.core.EmbeddedGameExitMonitor;
import com.winlator.core.ProcessHelper;
import com.winlator.core.RuntimeLaunchDiagnostics;
import com.winlator.core.EmbeddedGameSession;
import com.winlator.core.RuntimeComponentFiles;
import com.winlator.core.RuntimeComponentException;
import com.winlator.core.RuntimeAssets;
import com.winlator.core.Arm64EcRuntime;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineLaunchPaths;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.GuestInputRouting;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    // Serial across activities: a cancelled extraction must finish before the next launch writes files.
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private final AtomicBoolean sessionClosed = new AtomicBoolean(false);
    private final Object environmentLock = new Object();
    private final com.winlator.core.GuestPauseController guestPause =
        new com.winlator.core.GuestPauseController(STARTUP_EXECUTOR, this::applyGuestPause);
    private volatile boolean environmentStarted;
    private boolean activityResumed;
    private Boolean displayResumed;
    private boolean ownsSession;
    private Future<?> startupTask;
    private GameStartupPresentation startupPresentation;
    private volatile boolean displayedWindow;
    private com.winlator.core.GameRuntimeProfile gameRuntimeProfile;
    private com.winlator.core.RuntimeMemoryMonitor memoryMonitor;
    private AutoCloseable diagnosticModeObserver;
    private EmbeddedGameExitMonitor gameExitMonitor;
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private volatile XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private boolean hostMenuVisible;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        if (!shouldCreateGameSession()) {
            EmbeddedGameSession.returnToHost(this);
            return;
        }
        ownsSession = true;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { toggleNavigationDrawer(); }
        });
        RuntimeLaunchDiagnostics.stage(this, "CREATE_ACTIVITY");
        try {
            memoryMonitor = com.winlator.core.RuntimeMemoryMonitor.start(this, () -> runOnUiThread(() -> {
                if (!sessionClosed.get() && RuntimeLaunchDiagnostics.isActive(this)) exit();
            }));
            createSession();
            diagnosticModeObserver = com.winlator.core.RuntimeDiagnostics.observe(enabled -> {
                if (!enabled) runOnUiThread(this::stopDiagnosticCollection);
                return kotlin.Unit.INSTANCE;
            });
        } catch (Exception | LinkageError error) {
            failLaunch(error);
        }
    }

    private void createSession() {
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        startupPresentation = new GameStartupPresentation(this::runOnUiThread, createGameStartupUi());
        startupPresentation.show(GameStartupStage.PREPARING);
        ForegroundService.startSession(this);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = !getIntent().getBooleanExtra(com.winlator.core.UbisoftWaitTrace.EXTRA, false)
                && com.winlator.core.GuestDebugLog.captureEnabled(this);
        if (enableLogs) ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            if (container == null) throw new IllegalStateException("Wine container is missing");
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                startupPresentation.show(GameStartupStage.UPDATING_FILES);
                // Keep the animation responsive and retain the environment so Back/exit
                // cancels the update too. Do not resume a destroyed Activity from its callback.
                startupTask = STARTUP_EXECUTOR.submit(() -> {
                    try {
                        synchronized (environmentLock) {
                            if (sessionClosed.get()) return;
                            environment = WineUtils.createWineprefixUpdateEnvironment(this, status -> runOnUiThread(() -> {
                                if (sessionClosed.get() || isFinishing() || isDestroyed()) return;
                                if (status == 0) {
                                    container.putExtra("wineprefixNeedsUpdate", null);
                                    container.putExtra("wincomponents", null);
                                    container.saveData();
                                    // Finish cleanup before restarting, so the old Activity cannot
                                    // stop the new session's shared guest launcher in onDestroy.
                                    closeSession();
                                    if (getIntent().hasExtra("aura_game_id")) {
                                        String launchId = RuntimeLaunchDiagnostics.begin(this,
                                            getIntent().getIntExtra("aura_game_id", 0), container.id);
                                        getIntent().putExtra("aura_launch_id", launchId);
                                    }
                                    AppUtils.restartActivity(this);
                                } else failLaunch(new IllegalStateException("Wine prefix update failed: " + status));
                            }));
                            environment.startEnvironmentComponents();
                            environmentStarted = true;
                            guestPause.started();
                            runOnUiThread(this::updateEnvironmentVisibility);
                        }
                    } catch (Exception | LinkageError error) {
                        if (!sessionClosed.get()) runOnUiThread(() -> failLaunch(error));
                    }
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            Arm64EcRuntime.normalize(container);
            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = com.winlator.core.AudioBackend.normalize(container.getAudioDriver());
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = resolveScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = com.winlator.core.AudioBackend.normalize(shortcut.getExtra("audioDriver", container.getAudioDriver()));
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = resolveScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.graphicsDriver[0] = this.graphicsDriver[0].equals(GraphicsDrivers.TURNIP) ? GraphicsDrivers.TURNIP : GraphicsDrivers.WRAPPER;
            this.graphicsDriver[1] = GraphicsDrivers.ZINK;
            this.dxwrapper = DXWrappers.DXVK;
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
            this.dxwrapperConfig[0].put("version", Arm64EcRuntime.DXVK);
            this.dxwrapperConfig[1].put("version", Arm64EcRuntime.VKD3D);
            com.winlator.core.RuntimeTuning.dxvk(this.dxwrapperConfig[0], getIntent().getIntExtra(
                    com.winlator.core.RuntimeTuning.EXTRA_THREADS, 0));
            if (shortcut == null) {
                int gameId = getIntent().getIntExtra("aura_game_id", 0);
                String executable = getIntent().getStringExtra("exec_path");
                // Restored old launch intents must keep their original GTFO-only scope.
                boolean legacyStability = getIntent().getBooleanExtra("aura_gtfo_low_memory", false) &&
                    com.winlator.core.GameRuntimeProfile.requiresMemoryProtection(gameId, executable);
                gameRuntimeProfile = com.winlator.core.GameRuntimeProfile.forLaunch(
                    gameId, executable, getIntent().getBooleanExtra("aura_stability_mode", legacyStability));
                boolean dxvkDefaults = false;
                if (gameRuntimeProfile != null) {
                    screenInfo = resolveScreenInfo(gameRuntimeProfile.screenSize(container.getScreenSize(),
                        getIntent().getStringExtra("aura_exec_args")));
                    dxvkDefaults = this.dxwrapper.equals(DXWrappers.DXVK) &&
                        gameRuntimeProfile.applyDxvkDefaults(this.dxwrapperConfig[0],
                            this.dxwrapperConfig[0].get("version", DefaultVersion.DXVK(this.graphicsDriver[0])));
                }
                // Apply game-specific choices to this launch's copies, after inherited defaults.
                String gameScreen = com.winlator.core.PerGameLaunch.screenSize(getIntent());
                if (gameScreen != null) screenInfo = resolveScreenInfo(gameScreen);
                com.winlator.core.PerGameLaunch.dxvk(getIntent(), this.dxwrapperConfig[0]);
                if (gameRuntimeProfile != null) {
                    RuntimeLaunchDiagnostics.detail(this, gameRuntimeProfile.diagnostic(screenInfo.toString(),
                        this.dxwrapperConfig[0], dxvkDefaults));
                }
            }
        }

        startupPresentation.show(GameStartupStage.PREPARING);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        if (RuntimeLaunchDiagnostics.isCollecting(this))
            xServer.performance = new com.winlator.core.RuntimePerformanceCounters();
        xServer.dynamicDisplay.setFollowWineDesktop(true);
        xServer.dynamicDisplay.addListener((previous, current, source) -> {
            runOnUiThread(() -> {
                if (!sessionClosed.get()) RuntimeLaunchDiagnostics.detail(this,
                    "DYNAMIC_DISPLAY source="+source+"; previous="+previous+"; current="+current);
            });
        });
        RuntimeLaunchDiagnostics.detail(this, "DYNAMIC_DISPLAY=RANDR_1_1_WINE_DESKTOP_V1");
        xServer.setWinHandler(winHandler);
        if (getIntent().hasExtra("aura_game_id") && getIntent().hasExtra("exec_path")) {
            if (getIntent().getIntExtra("aura_game_id", 0) > 0 && RuntimeLaunchDiagnostics.isCollecting(this))
                xServer.gameDiagnostics = new com.winlator.core.GameWindowDiagnostics(getIntent().getStringExtra("exec_path"));
            gameExitMonitor = new EmbeddedGameExitMonitor(winHandler, getIntent().getStringExtra("exec_path"), () -> {
                if (sessionClosed.get()) return;
                RuntimeLaunchDiagnostics.detail(this, "GAME_WINDOWS_CLOSED; APPLICATION_PROCESSES_FINISHED");
                exit();
            }, getIntent().hasExtra("aura_steam_bootstrap"), xServer.gameDiagnostics,
                getIntent().hasExtra("aura_steam_bootstrap"));
            xServer.windowManager.addOnResourceLifecycleListener(new com.winlator.xserver.XResourceManager.OnResourceLifecycleListener() {
                @Override public void onFreeResource(com.winlator.xserver.XResource resource) {
                    gameExitMonitor.windowRemoved(resource.id);
                }
            });
        }
        final boolean[] flags = {false, shortcut != null || getIntent().hasExtra("exec_path")};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (sessionClosed.get()) return;
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (sessionClosed.get()) return;
                if (gameExitMonitor != null) gameExitMonitor.windowChanged(window);
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    displayedWindow = true;
                    RuntimeLaunchDiagnostics.stage(XServerDisplayActivity.this, "X_WINDOW_VISIBLE");
                    RuntimeLaunchDiagnostics.detail(XServerDisplayActivity.this,
                        "显示状态：首个 X 窗口已出现；不代表游戏画面已加载或主菜单可用。");
                    if (memoryMonitor != null) memoryMonitor.sampleSoon();
                    onGameWindowMapped();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                if (sessionClosed.get()) return;
                if (gameExitMonitor != null) gameExitMonitor.windowRemoved(window.id);
                changeFrameRatingVisibility(window, false);
            }

            @Override public void onModifyWindowProperty(Window window, Property property) {
                if (!sessionClosed.get() && gameExitMonitor != null && (property.name == Atom.WM_CLASS ||
                    property.name == Atom._NET_WM_PID || property.name == Atom._NET_WM_HWND ||
                    property.name == Atom.WM_TRANSIENT_FOR || property.name == Atom.WM_HINTS))
                    gameExitMonitor.windowChanged(window);
            }

            @Override public void onUpdateWindowGeometry(Window window, boolean resized) {
                if (resized && !sessionClosed.get() && gameExitMonitor != null) gameExitMonitor.windowChanged(window);
            }
        });

        setupUI();

        startupTask = STARTUP_EXECUTOR.submit(() -> {
            try {
                if (sessionClosed.get()) return;
                if (!isGenerateWineprefix()) {
                    RuntimeLaunchDiagnostics.stage(this, "PREPARE_WINE_FILES");
                    startupPresentation.show(GameStartupStage.PREPARING_WINE);
                    setupWineSystemFiles();
                    if (sessionClosed.get()) return;
                    RuntimeLaunchDiagnostics.stage(this, "PREPARE_GRAPHICS");
                    startupPresentation.show(GameStartupStage.PREPARING_GRAPHICS);
                    extractGraphicsDriverFiles();
                    if (sessionClosed.get()) return;
                    RuntimeLaunchDiagnostics.stage(this, "PREPARE_AUDIO");
                    startupPresentation.show(GameStartupStage.PREPARING_AUDIO);
                    changeWineAudioDriver();
                }
                synchronized (environmentLock) {
                    if (sessionClosed.get()) return;
                    RuntimeLaunchDiagnostics.stage(this, "START_ENVIRONMENT");
                    startupPresentation.show(GameStartupStage.STARTING_ENVIRONMENT);
                    setupXEnvironment();
                    environmentStarted = true;
                    guestPause.started();
                    runOnUiThread(this::updateEnvironmentVisibility);
                }
            } catch (Exception | LinkageError error) {
                if (!sessionClosed.get()) runOnUiThread(() -> failLaunch(error));
            }
        });
    }

    /** Embedded hosts can replace the startup presentation without changing guest execution. */
    protected GameStartupUi createGameStartupUi() {
        final PreloaderDialog dialog = new PreloaderDialog(this);
        return new GameStartupUi() {
            @Override public void show(GameStartupStage stage) {
                dialog.show(stage == GameStartupStage.UPDATING_FILES ? R.string.updating_system_files : R.string.starting_up);
            }
            @Override public void close() { dialog.close(); }
        };
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateGameVibration();

        if (hasFocus) {
            updateGuestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    protected final void setHostMenuVisible(boolean visible) {
        hostMenuVisible = visible;
        updateGameVibration();
        if (visible) {
            if (inputControlsView != null) inputControlsView.releasePointerCapture();
            if (touchpadView != null) touchpadView.releasePointerCapture();
        } else updateGuestPointerCapture();
    }

    /** Explicit menu/return-transition pause. Loading artwork only blocks input, not execution. */
    protected final void setHostGamePaused(boolean paused) {
        guestPause.setHostPaused(paused);
        updateEnvironmentVisibility();
    }

    protected final boolean isGuestExecutionPaused() { return guestPause.isPaused(); }
    protected void onGuestExecutionPauseChanged(boolean paused) { }

    private void updateGuestPointerCapture() {
        if (!hostMenuVisible && !sessionClosed.get() && hasWindowFocus() &&
                capturePointerOnExternalMouse && inputControlsView != null && touchpadView != null) {
            // Captured events are delivered to the focused view, never to the host menu.
            View captureView = inputControlsView.getVisibility() == View.VISIBLE ? inputControlsView : touchpadView;
            if (captureView.requestFocus()) captureView.requestPointerCapture();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        activityResumed = true;
        updateGameVibration();
        updateEnvironmentVisibility();
        ForegroundService.onResumeSession(this);
    }

    @Override
    public void onPause() {
        ForegroundService.onPauseSession(this);
        super.onPause();
        activityResumed = false;
        updateGameVibration();
        updateEnvironmentVisibility();
    }

    private void updateGameVibration() {
        if (winHandler != null) winHandler.gamepadHandler.setVibrationActive(
            activityResumed && hasWindowFocus() && !hostMenuVisible && !sessionClosed.get());
    }

    private void updateEnvironmentVisibility() {
        if (sessionClosed.get()) return;
        guestPause.setForeground(activityResumed);
        boolean paused = guestPause.isPaused();
        if (gameExitMonitor != null) gameExitMonitor.setResumed(!paused);
        onGuestExecutionPauseChanged(paused);
        // Keep the last GL frame behind the menu. Only leaving the Activity pauses its surface;
        // suspending a dialog's background surface can erase it on some drivers. No /proc work
        // or startup lock on the UI thread, including when a menu opens during preparation.
        if (!environmentStarted || xServerView == null ||
                (displayResumed != null && displayResumed == activityResumed)) return;
        displayResumed = activityResumed;
        if (activityResumed) xServerView.onResume();
        else xServerView.onPause();
    }

    private void applyGuestPause(boolean paused) {
        synchronized (environmentLock) {
            if (environmentStarted && environment != null && !sessionClosed.get()) {
                if (paused) environment.onPause();
                else environment.onResume();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
    }

    private void stopDiagnosticCollection() {
        if (xServer != null) { xServer.performance = null; xServer.gameDiagnostics = null; }
        if (gameExitMonitor != null) gameExitMonitor.stopDiagnostics();
        NavigationView navigation = findViewById(R.id.NavigationView);
        if (navigation != null) navigation.getMenu().findItem(R.id.menu_item_logs).setVisible(false);
        frameRatingWindowId = -1;
        if (frameRating != null) {
            frameRating.setMode(FrameRating.Mode.DISABLED);
            frameRating.removeCallbacks(frameRating);
            frameRating.setVisibility(View.GONE);
        }
        if (debugDialog != null) {
            ProcessHelper.removeDebugCallback(debugDialog);
            debugDialog.closeLog();
            debugDialog.dismiss();
            debugDialog = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (diagnosticModeObserver != null) {
            try { diagnosticModeObserver.close(); } catch (Exception ignored) { }
            diagnosticModeObserver = null;
        }
        if (ownsSession) closeSession();
        if (debugDialog != null) {
            ProcessHelper.removeDebugCallback(debugDialog);
            debugDialog.closeLog();
            debugDialog = null;
        }
        super.onDestroy();
    }

    /** Host-only resume intents must reuse an existing Activity, never create a second guest. */
    protected boolean shouldCreateGameSession() { return true; }
    protected final boolean isGameSessionClosing() { return sessionClosed.get(); }
    protected void onGameSessionClosing() { }
    protected void onGameSessionClosed() { }
    protected final void releaseGameControllerState() { winHandler.gamepadHandler.releaseControllerState(); }

    /** Embedded hosts may wait for rendered content instead of treating an X window as a frame. */
    protected void onGameWindowMapped() {
        setGuestCursorVisible(showDefaultGuestCursor());
        finishGameStartup();
    }
    protected final void finishGameStartup() {
        if (startupPresentation != null) startupPresentation.close();
    }
    protected boolean showDefaultGuestCursor() { return true; }
    protected void onGuestPointerActivity() { }
    protected final void setGuestCursorVisible(boolean visible) {
        if (xServerView != null && xServerView.getRenderer().isCursorVisible() != visible)
            xServerView.getRenderer().setCursorVisible(visible);
    }

    private void toggleNavigationDrawer() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        do {
            final int _auraItemId = item.getItemId();
            if (_auraItemId == R.id.menu_item_keyboard) {
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_input_controls) {
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_toggle_fullscreen) {
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_task_manager) {
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_active_windows) {
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_magnifier) {
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_screen_effect) {
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_pip_mode) {
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(getScreenInfo().aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_logs) {
                if (debugDialog != null) debugDialog.show();
                drawerLayout.closeDrawers();
                break;

            } else if (_auraItemId == R.id.menu_item_touchpad_help) {
                showTouchpadHelpDialog();
                break;

            } else if (_auraItemId == R.id.menu_item_exit) {
                exit();
                break;

            }
        } while (false);
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private void exit() {
        Intent intent = getIntent();
        if (intent.hasExtra("aura_game_id")) {
            if (!beginSessionClose()) return;
            // Reserve cleanup ahead of another startup. Cover the last frame before stopping GL,
            // then keep all process scans / service joins off the Android animation thread.
            CountDownLatch covered = new CountDownLatch(1);
            STARTUP_EXECUTOR.execute(() -> {
                try { covered.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                try { stopSessionComponents(); }
                catch (Exception | LinkageError error) {
                    RuntimeLaunchDiagnostics.failed(this, error);
                    RuntimeLaunchDiagnostics.finished(this);
                }
                finally {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) EmbeddedGameSession.returnToHost(this);
                    });
                }
            });
            showReturnToHostTransition(covered::countDown);
            return;
        }
        closeSession();
        if (intent.hasExtra("exec_path") && container != null) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
    }

    /** Explicit host-menu action; uses the same supervised guest cleanup as the engine. */
    protected final void exitGameToApp() {
        if (!sessionClosed.get()) {
            RuntimeLaunchDiagnostics.detail(this, "USER_REQUESTED_GAME_EXIT");
            exit();
        }
    }

    /** Called on main; invoke covered once the host's return overlay is opaque. */
    protected void showReturnToHostTransition(Runnable covered) { covered.run(); }

    private void failLaunch(Throwable error) {
        if (sessionClosed.get()) return;
        RuntimeLaunchDiagnostics.failed(this, error);
        exit();
    }

    private void closeSession() {
        if (beginSessionClose()) stopSessionComponents();
    }

    private boolean beginSessionClose() {
        if (!sessionClosed.compareAndSet(false, true)) return false;
        guestPause.close();
        onGameSessionClosing();
        if (gameExitMonitor != null) gameExitMonitor.close();
        if (startupPresentation != null) startupPresentation.close();
        if (memoryMonitor != null) memoryMonitor.close();
        if (startupTask != null) startupTask.cancel(true);
        return true;
    }

    private void stopSessionComponents() {
        RuntimeLaunchDiagnostics.stage(this, "STOP_ENVIRONMENT");
        synchronized (environmentLock) {
            if (environment != null) {
                try { environment.stopEnvironmentComponents(); }
                catch (Exception | LinkageError error) { RuntimeLaunchDiagnostics.failed(this, error); }
                environment = null;
                environmentStarted = false;
            }
            // Startup can create WinHandler threads too; stop them after startup releases the lock.
            RuntimeLaunchDiagnostics.stage(this, "STOP_WINHANDLER");
            try { winHandler.stop(); }
            catch (Exception | LinkageError error) { RuntimeLaunchDiagnostics.failed(this, error); }
        }
        RuntimeLaunchDiagnostics.stage(this, "STOP_GAME_SERVICE");
        try { ForegroundService.stopSession(this); }
        catch (Exception | LinkageError error) { RuntimeLaunchDiagnostics.failed(this, error); }
        RuntimeLaunchDiagnostics.finished(this);
        onGameSessionClosed();
    }

    private void setupWineSystemFiles() {
        RuntimeLaunchDiagnostics.watchGuestLogs(this, getIntent().getStringExtra("exec_path"));
        RuntimeLaunchDiagnostics.watchUbisoftLogs(this, getIntent().getStringExtra(com.winlator.core.UbisoftClientLogs.EXTRA_EXECUTABLE));
        Arm64EcRuntime.preparePrefix(this, container);
        changeWineAudioDriver();
        DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
        RuntimeLaunchDiagnostics.detail(this, "DXVK_COMPILER_THREADS=" +
                (dxwrapperConfig[0].get("numCompilerThreads").matches("[0-9]{1,2}") ?
                        dxwrapperConfig[0].get("numCompilerThreads") : "AUTO_OR_CUSTOM"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);
        WineUtils.applySystemTweaks(this, wineInfo);
        // Keep redistributable user choices; these are Windows PE modules, not native glibc libraries.
        if (!wincomponents.equals(container.getExtra("wincomponents")) ||
                !RuntimeAssets.hasWindowsComponents(this, new File(container.getRootDir(), ".wine/drive_c/windows"), wincomponents)) {
            container.putExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS);
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            container.saveData();
        }
    }

    /** Host launch preferences are applied after container, shortcut and driver settings. */
    protected EnvVars prepareGuestEnvironment(EnvVars variables) { return variables; }

    /** App-owned launch diagnostics can add arguments without changing the Wine working directory. */
    protected String prepareGameArguments(String executable, String arguments) { return arguments; }

    /** Allow the host to stage a client before the game without losing the game's profile/log path. */
    protected String prepareWineStartArguments(String arguments) { return arguments; }

    /** A host supervisor observed successful game process creation, possibly before a window. */
    protected final void markGameProcessStarted() {
        if (gameExitMonitor != null) gameExitMonitor.applicationStarted();
    }

    private void setupXEnvironment() {
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (com.winlator.core.RuntimeDiagnostics.sessionEnabled(this) && container.getHUDMode() == FrameRating.Mode.FULL.ordinal())
                envVars.put("X11_WND_GPU_INFO", "1");

            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ? "nogui" : "shell";
            RuntimeLaunchDiagnostics.stage(this, "RESOLVE_EXECUTABLE");
            String wineStartCommand = getWineStartCommand();
            RuntimeLaunchDiagnostics.stage(this, "START_ENVIRONMENT");
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+wineStartCommand;
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            // Apply required GPU flags after the container defaults (which also contain TU_DEBUG).
            if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP))
                TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");


        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        com.winlator.core.RuntimeAudio.configure(this, audioDriver, audioDriverConfig, environment, envVars);

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(prepareGuestEnvironment(envVars));
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(() -> {
            if (sessionClosed.get()) return;
            RuntimeLaunchDiagnostics.exited(this, status, displayedWindow);
            exit();
        }));
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        RuntimeLaunchDiagnostics.detail(this, RuntimeComponentFiles.describe(
            container != null ? container.getWineVersion() : "generated",
            Arm64EcRuntime.FEX,
            graphicsDriver[0], graphicsDriver[1], dxwrapper, "ARM64EC"));
        if (container != null) RuntimeComponentFiles.requireFiles(container.getRootDir(), "WINE_PREFIX",
            ".wine/system.reg", ".wine/user.reg", ".wine/drive_c/windows/winhandler.exe", ".wine/drive_c/windows/explorer.exe");
        RuntimeLaunchDiagnostics.stage(this, "START_GUEST");
        if (startupPresentation != null) startupPresentation.show(GameStartupStage.STARTING_GAME);
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setPointerActivityCallback(this::onGuestPointerActivity);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        if (!getIntent().hasExtra("aura_game_id")) {
            touchpadView.setFourFingersTapCallback(() -> {
                if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
            });
        }
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        if (capturePointerOnExternalMouse) {
            inputControlsView.setOnCapturedPointerListener((view, event) ->
                touchpadView.isEnabled() && touchpadView.onCapturedPointer(view, event));
        }
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        if (container != null && com.winlator.core.RuntimeDiagnostics.sessionEnabled(this) && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(showDefaultGuestCursor());
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(showDefaultGuestCursor());
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        Arm64EcRuntime.graphics(this, graphicsDriver[0], graphicsDriverConfig[0], envVars);
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        boolean controller = GuestInputRouting.isControllerMotion(event);
        if (hostMenuVisible && !controller) return super.dispatchGenericMotionEvent(event);
        return GuestInputRouting.dispatch(controller,
                () -> touchpadView != null && (winHandler.onGenericMotionEvent(event) || touchpadView.onExternalMouseEvent(event)),
                () -> super.dispatchGenericMotionEvent(event));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean controller = GuestInputRouting.isControllerKey(event);
        if (hostMenuVisible && !controller) return super.dispatchKeyEvent(event);
        KeyEvent guestEvent = GuestInputRouting.guestKey(event, controller);
        return GuestInputRouting.dispatch(controller,
                () -> inputControlsView != null && xServer != null &&
                        (inputControlsView.onKeyEvent(guestEvent) || winHandler.onKeyEvent(guestEvent) || xServer.keyboard.onKeyEvent(guestEvent)),
                () -> super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper")) ||
            !RuntimeComponentFiles.hasFiles(new File(container.getRootDir(), ".wine/drive_c/windows"), RuntimeComponentFiles.dxFiles());
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                RuntimeAssets.extract(this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                RuntimeAssets.extract(this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                RuntimeAssets.extract(this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        RuntimeComponentFiles.requireFiles(windowsDir, "DIRECTX_WRAPPER", RuntimeComponentFiles.dxFiles());
        container.putExtra("dxwrapper", cacheId);
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    RuntimeAssets.extract(this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        RuntimeAssets.extract(this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
            if (!RuntimeAssets.hasWindowsComponents(this, windowsDir, wincomponents))
                throw new RuntimeComponentException("WINDOWS_COMPONENT", "FILES_MISSING_OR_UNREADABLE");
        }
        catch (JSONException e) { throw new RuntimeComponentException("WINDOWS_COMPONENT", "INVALID_CATALOGUE"); }
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/aarch64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                String arguments = intent.getStringExtra("aura_exec_args");
                if (gameRuntimeProfile != null) arguments = gameRuntimeProfile.arguments(arguments, screenInfo.toString());
                arguments = prepareGameArguments(intent.getStringExtra("exec_path"), arguments);
                cmdArgs = WineLaunchPaths.startArguments(
                    intent.getStringExtra("exec_path"), container.getRootDir(), container.drivesIterator(),
                    arguments);
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            String directoryArgs = execDir.isEmpty() ? "" : "/dir "+WineLaunchPaths.escapeWorkingDirectory(execDir)+" ";
            cmdArgs = directoryArgs+"\""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+prepareWineStartArguments(cmdArgs);
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return xServer != null ? xServer.screenInfo : screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    /** Host Activities may adapt a launch profile to their actual display before X11 starts. */
    protected ScreenInfo resolveScreenInfo(String value) {
        if (!value.equals("native")) return new ScreenInfo(value);

        int width = AppUtils.getScreenWidth();
        int height = AppUtils.getScreenHeight();
        return new ScreenInfo(width - (width % 2), height - (height % 2));
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        try (WineRegistryEditor registry = new WineRegistryEditor(userRegFile)) {
            registry.setStringValue("Software\\Wine\\Drivers", "Audio", com.winlator.core.AudioBackend.fromId(audioDriver).getWineDriver());
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        RuntimeAssets.extract(this, "rootfs_patches.tzst", rootDir);
        RuntimeAssets.extract(this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null || frameRating.getMode() == FrameRating.Mode.DISABLED) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}
