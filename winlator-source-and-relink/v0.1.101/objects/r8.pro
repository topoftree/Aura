
-allowaccessmodification

-keepattributes AnnotationDefault,
                EnclosingMethod,
                InnerClasses,
                RuntimeVisibleAnnotations,
                RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations,
                Signature

-keep public class com.google.vending.licensing.ILicensingService
-keep public class com.android.vending.licensing.ILicensingService
-keep public class com.google.android.vending.licensing.ILicensingService
-dontnote com.android.vending.licensing.ILicensingService
-dontnote com.google.vending.licensing.ILicensingService
-dontnote com.google.android.vending.licensing.ILicensingService

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontnote android.support.**
-dontnote androidx.**
-dontwarn android.support.**
-dontwarn androidx.**

-keep class android.support.annotation.Keep

-keep @android.support.annotation.Keep class * {*;}

-keepclasseswithmembers class * {
    @android.support.annotation.Keep <methods>;
}

-keepclasseswithmembers class * {
    @android.support.annotation.Keep <fields>;
}

-keepclasseswithmembers class * {
    @android.support.annotation.Keep <init>(...);
}

-dontnote org.apache.http.**
-dontnote android.net.http.**

-dontnote java.lang.invoke.**

-keep,allowoptimization class com.winlator.** { *; }

-keep,allowoptimization class in.dragonbra.javasteam.generated.** { public <init>(); }
-keep,allowoptimization class in.dragonbra.javasteam.steam.handlers.** { *; }
-keep,allowoptimization class in.dragonbra.javasteam.steam.steamclient.callbacks.** { *; }
-keep,allowoptimization class in.dragonbra.javasteam.protobufs.** { *; }
-keep,allowoptimization class in.dragonbra.javasteam.rpc.** { *; }
-keep,allowoptimization class org.bouncycastle.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations

-dontwarn org.spongycastle.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

-keep class com.github.luben.zstd.** { *; }


-keep public class * extends androidx.coordinatorlayout.widget.CoordinatorLayout$Behavior {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>();
}

-keepattributes RuntimeVisible*Annotation*


-if class androidx.appcompat.app.AppCompatViewInflater
-keep class com.google.android.material.theme.MaterialComponentsViewInflater {
    <init>();
}


-keep public class androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-dontwarn coil3.PlatformContext


-keepclassmembernames,allowobfuscation,allowshrinking class androidx.appcompat.widget.AppCompatTextViewAutoSizeHelper$Impl* {
  <methods>;
}


-if public class ** extends androidx.fragment.app.Fragment
-keepclasseswithmembers,allowobfuscation public class <1> {
    public <init>();
}




-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static java.lang.Void throw*Exception(...);
}




-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}


-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}


-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}


-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}
-keepclassmembers class androidx.compose.ui.graphics.layer.view.ViewLayerContainer {
    protected void dispatchGetDisplayList();
}
-keepclassmembers class androidx.compose.ui.graphics.layer.view.PlaceholderHardwareCanvas {
    public int drawRenderNode(...);
    public boolean isHardwareAccelerated();
}



-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}



-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
}




-keepnames class * extends androidx.work.ListenableWorker
-keepclassmembers public class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

-keep class androidx.work.WorkerParameters

-keepnames class * extends androidx.work.InputMerger
-keepclassmembers class * extends androidx.work.InputMerger { void <init>(); }



-keep public class * extends androidx.coordinatorlayout.widget.CoordinatorLayout$Behavior {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>();
}

-keepattributes *Annotation*


-keepclassmembers class androidx.transition.ChangeBounds$* extends android.animation.AnimatorListenerAdapter {
  androidx.transition.ChangeBounds$ViewBounds mViewBounds;
}



-keep public class * extends androidx.recyclerview.widget.RecyclerView$LayoutManager {
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    public <init>();
}

-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    public void suppressLayout(boolean);
    public boolean isLayoutSuppressed();
}

-keepclassmembers class androidx.vectordrawable.graphics.drawable.VectorDrawableCompat$* {
   void set*(***);
   *** get*();
}


-if class androidx.window.layout.SidecarCompat {
  public setExtensionCallback(androidx.window.layout.ExtensionInterfaceCompat$ExtensionCallbackInterface);
}
-keep class androidx.window.layout.SidecarCompat$TranslatingCallback,
 androidx.window.layout.SidecarCompat$DistinctSidecarElementCallback {
  public onDeviceStateChanged(androidx.window.sidecar.SidecarDeviceState);
  public onWindowLayoutChanged(android.os.IBinder, androidx.window.sidecar.SidecarWindowLayoutInfo);
}
-keepclasseswithmembers class androidx.graphics.path.** {
    native <methods>;
}

-keepclassmembernames,allowobfuscation,allowshrinking class androidx.core.view.ViewCompat$Api* {
  <methods>;
}
-keepclassmembernames,allowobfuscation,allowshrinking class androidx.core.view.WindowInsetsCompat$*Impl* {
  <methods>;
}
-keepclassmembernames,allowobfuscation,allowshrinking class androidx.core.app.NotificationCompat$*$Api*Impl {
  <methods>;
}
-keepclassmembernames,allowobfuscation,allowshrinking class androidx.core.os.UserHandleCompat$Api*Impl {
  <methods>;
}
-keepclassmembernames,allowobfuscation,allowshrinking class androidx.core.widget.EdgeEffectCompat$Api*Impl {
  <methods>;
}



-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>();
}

-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

-keepattributes AnnotationDefault,
                RuntimeVisibleAnnotations,
                RuntimeVisibleParameterAnnotations,
                RuntimeVisibleTypeAnnotations

-keepclassmembers enum androidx.lifecycle.Lifecycle$Event {
    <fields>;
}

-keep class * implements androidx.lifecycle.GeneratedAdapter {
    <init>(...);
}

-keepclassmembers class ** {
    @androidx.lifecycle.OnLifecycleEvent *;
}

-keepclasseswithmembers,allowobfuscation public class androidx.lifecycle.ReportFragment {
    public <init>();
}

-keepclassmembers class androidx.lifecycle.ReportFragment$LifecycleCallbacks { *; }






-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.ViewModel {
    <init>(androidx.lifecycle.SavedStateHandle);
}

-keepclassmembers,allowobfuscation class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application,androidx.lifecycle.SavedStateHandle);
}



-keepclassmembers,allowobfuscation class * implements androidx.savedstate.SavedStateRegistry$AutoRecreated {
    <init>();
}

-if public class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalSavedStateRegistryOwner();
}
-keep public class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalSavedStateRegistryOwner();
}

-assumenosideeffects public class androidx.compose.runtime.ComposerKt {
    void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
    void sourceInformationMarkerStart(androidx.compose.runtime.Composer,int,java.lang.String);
    void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
}

-assumenosideeffects public class androidx.compose.runtime.Composer {
    void <clinit>();
}
-assumenosideeffects public class androidx.compose.runtime.ComposerImpl {
    void <clinit>();
}

-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.runtime.** {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);

    static java.lang.String exceptionMessage*(...);
    java.lang.String exceptionMessage*(...);

    static void compose*RuntimeError(...);
    static java.lang.Void compose*RuntimeError(...);
}




-if public class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalLifecycleOwner();
}
-keep public class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static *** getLocalLifecycleOwner();
}

-keepclassmembers class * extends androidx.lifecycle.EmptyActivityLifecycleCallbacks { *; }


-dontwarn android.view.RenderNode
-dontwarn android.view.DisplayListCanvas
-dontwarn android.view.HardwareCanvas

-keepclassmembers class androidx.compose.ui.platform.ViewLayerContainer {
    protected void dispatchGetDisplayList();
}

-keepclassmembers class androidx.compose.ui.platform.AndroidComposeView {
    android.view.View findViewByAccessibilityIdTraversal(int);
}

-keep,allowshrinking class * extends androidx.compose.ui.node.ModifierNodeElement

-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* {
    static void throw*Exception(...);
    static void throw*ExceptionForNullCheck(...);
    static java.lang.Void throw*Exception(...);
    static java.lang.Void throw*ExceptionForNullCheck(...);
    static java.lang.String exceptionMessage*(...);
    java.lang.String exceptionMessage*(...);
}

-keepnames class androidx.compose.ui.input.pointer.PointerInputEventHandler {
    *;
}





-keep class * extends androidx.room.RoomDatabase { void <init>(); }
-dontwarn androidx.room.paging.**
-dontwarn androidx.lifecycle.LiveData

-keepclassmembers class io.ktor.** {
    volatile <fields>;
}

-keepclassmembernames class io.ktor.** {
    volatile <fields>;
}

-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer



-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn sun.misc.SignalHandler
-dontwarn java.lang.instrument.Instrumentation
-dontwarn sun.misc.Signal

-dontwarn java.lang.ClassValue

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
    boolean FAST_SERVICE_LOADER_ENABLED return false;
}

-assumenosideeffects class kotlinx.coroutines.internal.FastServiceLoaderKt {
    boolean ANDROID_DETECTED return true;
}

-assumenosideeffects class kotlinx.coroutines.internal.MainDispatchersKt {
    boolean SUPPORT_MISSING return false;
}

-assumenosideeffects class kotlinx.coroutines.DebugKt {
    boolean getASSERTIONS_ENABLED() return false;
    boolean getDEBUG() return false;
    boolean getRECOVER_STACK_TRACES() return false;
}


 -if @kotlinx.serialization.Serializable class **
 -keep, allowshrinking, allowoptimization, allowobfuscation, allowaccessmodification class <1>



-keepclassmembers @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$* Companion;
}

-keepnames @kotlinx.serialization.internal.NamedCompanion class *
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembernames class * {
    static <1> *;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontnote kotlinx.serialization.**

-dontwarn kotlinx.serialization.internal.ClassValueReferences

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-dontwarn javax.annotation.**

-dontwarn org.codehaus.mojo.animal_sniffer.*

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**


-keepnames class * extends androidx.startup.Initializer

-keep class * extends androidx.startup.Initializer {
    <init>();
}

-assumenosideeffects class androidx.startup.StartupLogger { public static <methods>; }

-keep class * implements androidx.versionedparcelable.VersionedParcelable
-keep public class android.support.**Parcelizer { *; }
-keep public class androidx.**Parcelizer { *; }
-keep public class androidx.versionedparcelable.ParcelImpl





-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}

-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}

-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}

-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}

-keepclassmembers,allowobfuscation class * {
  @androidx.annotation.DoNotInline <methods>;
}


# Keep runtime serialization lookup valid after the independent application pass.
-keepclassmembers class aura.obfuscated.** {
    public static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
