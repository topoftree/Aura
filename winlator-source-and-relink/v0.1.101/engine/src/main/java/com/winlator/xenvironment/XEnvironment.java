/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xenvironment;

import android.content.Context;

import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final RootFS rootFS;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    public XEnvironment(Context context, RootFS rootFS) {
        this.context = context;
        this.rootFS = rootFS;
    }

    public Context getContext() {
        return context;
    }

    public RootFS getRootFS() {
        return rootFS;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public File getTmpDir() {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir());
        for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
    }

    public void stopEnvironmentComponents() {
        // Stop the guest before its display/audio/IPC services, including partial startup failures.
        Throwable failure = null;
        for (int i = components.size() - 1; i >= 0; i--) {
            try { components.get(i).stop(); }
            catch (RuntimeException | LinkageError error) { if (failure == null) failure = error; }
        }
        if (failure instanceof LinkageError) throw (LinkageError)failure;
        if (failure != null) throw (RuntimeException)failure;
    }

    public void onPause() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onPause();
    }

    public void onResume() {
        for (EnvironmentComponent environmentComponent : this) environmentComponent.onResume();
    }
}
