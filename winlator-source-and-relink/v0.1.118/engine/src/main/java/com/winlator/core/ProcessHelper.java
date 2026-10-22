/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import com.winlator.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class ProcessHelper {
    public enum PState {RUNNING, SLEEPING, WAITING, ZOMBIE, STOPPED, DEAD, OTHER}
    private static final ArrayList<Callback<String>> debugCallbacks = new ArrayList<>();

    public static class PStat {
        public int pid = 0;
        public String name = "";
        public String shortName = "";
        public PState state = PState.OTHER;
        public int parentPID = 0;
        public long startTicks = 0;
        public boolean guestProcess = false;

        @NonNull
        @Override
        public String toString() {
            return pid+" "+name+" "+state+" "+parentPID+" "+guestProcess;
        }
    }

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGCONT);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, OsConstants.SIGKILL);
    }

    public static void killProcess(PStat process) {
        GuestProcessSession.killTracked(process.pid, process.startTicks);
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, EnvVars envVars) {
        return exec(command, envVars, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir) {
        return exec(command, envVars, workingDir, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        return exec(command, envVars, workingDir, terminationCallback, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback, Callback<String> outputCallback) {
        return exec(command, envVars, workingDir, terminationCallback, outputCallback, null);
    }

    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback,
                           Callback<String> outputCallback, Callback<java.lang.Process> startedCallback) {
        int pid = -1;
        java.lang.Process process = null;
        try {
            ProcessBuilder processBuilder = (new ProcessBuilder(NativeRuntime.command(splitCommand(command)))).directory(workingDir);
            final boolean captureDebug;
            synchronized (debugCallbacks) { captureDebug = !debugCallbacks.isEmpty(); }
            if (outputCallback != null) processBuilder.redirectErrorStream(true);
            else if (!captureDebug) processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);

            Map<String, String> environment = processBuilder.environment();
            if (envVars != null) for (String name : envVars) environment.put(name, envVars.get(name));

            process = processBuilder.start();
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);
            if (startedCallback != null) startedCallback.call(process);

            List<Thread> outputThreads = new ArrayList<>();
            if (outputCallback != null || captureDebug) {
                outputThreads.add(createDebugThread(process.getInputStream(), outputCallback));
                if (outputCallback == null) outputThreads.add(createDebugThread(process.getErrorStream(), null));
            }

            if (terminationCallback != null) createWaitForThread(process, terminationCallback, outputThreads);
        }
        catch (Exception e) {
            // PID discovery can fail after spawn; never leave an untracked Wine/Box64 process.
            if (process != null) process.destroyForcibly();
            RuntimeLaunchDiagnostics.processFailure(e);
            if (RuntimeDiagnostics.enabled()) android.util.Log.e("AuraRuntime", "Guest process launch failed", e);
            if (terminationCallback != null) terminationCallback.call(-1);
        }
        return pid;
    }

    private static Thread createDebugThread(final InputStream inputStream, Callback<String> outputCallback) {
        Thread thread = new Thread(() -> {
            try {
                GuestOutputSummary.drain(inputStream, (line) -> {
                    if (outputCallback != null) outputCallback.call(line);
                    synchronized (debugCallbacks) {
                        if (!debugCallbacks.isEmpty()) {
                            for (Callback<String> callback : debugCallbacks) callback.call(line);
                        }
                        else if (MainActivity.DEBUG_MODE) System.out.println(line);
                    }
                }, RuntimeDiagnostics::enabled);
            }
            catch (IOException e) {}
        }, "aura-guest-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback, List<Thread> outputThreads) {
        Thread thread = new Thread(() -> {
            try {
                int status = process.waitFor();
                // Drain early failures before publishing the report; inherited child pipes must not block exit.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                for (Thread reader : outputThreads) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                }
                terminationCallback.call(status);
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "aura-guest-wait");
        thread.setDaemon(true);
        thread.start();
    }

    public static void removeAllDebugCallbacks() {
        synchronized (debugCallbacks) {
            debugCallbacks.clear();
        }
    }

    public static void addDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            if (!debugCallbacks.contains(callback)) debugCallbacks.add(callback);
        }
    }

    public static void removeDebugCallback(Callback<String> callback) {
        synchronized (debugCallbacks) {
            debugCallbacks.remove(callback);
        }
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static long getMemoryUsage(int pid) {
        try (Scanner scanner = new Scanner(new FileInputStream("/proc/"+pid+"/statm"))) {
            byte index = 0;
            long vmSize = 0;
            long resident = 0;

            while (scanner.hasNext() && index < 2) {
                byte column = index++;
                if (column == 0) {
                    vmSize = scanner.nextLong();
                }
                else resident = scanner.nextLong();
            }

            return (resident * Os.sysconf(OsConstants._SC_PAGESIZE));
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static String getProcessName(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder result = new StringBuilder();
            int chr;
            while ((chr = reader.read()) != -1 && chr != 0) {
                result.append((char)chr);
            }
            return result.length() > 0 && result.charAt(0) == '/' ? FileUtils.getName(result.toString()) : result.toString();
        }
        catch (IOException e) {
            return "";
        }
    }

    public static List<String> getProcessCmdLine(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/"+pid+"/cmdline"))) {
            StringBuilder sb = new StringBuilder();
            ArrayList<String> result = new ArrayList<>();

            int charsRead;
            char[] chars = new char[64];
            while ((charsRead = reader.read(chars)) != -1) {
                for (int i = 0; i < charsRead; i++) {
                    if (chars[i] == '\0') {
                        if (sb.length() == 0) break;
                        result.add(sb.toString());
                        sb = new StringBuilder();
                    }
                    else sb.append(chars[i]);
                }
            }
            return result;
        }
        catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static List<PStat> getChildProcesses() {
        ArrayList<PStat> result = new ArrayList<>();
        // Session ownership is established by UID + start time + ancestry / inherited marker.
        // A numerically larger PID or a Wine-looking name is not proof of parentage.
        for (GuestProcessInfo process : GuestProcessSession.processes()) {
            PStat pstat = new PStat();
            pstat.pid = process.getPid();
            pstat.parentPID = process.getParentPid();
            pstat.startTicks = process.getStartTicks();
            pstat.name = getProcessName(pstat.pid);
            pstat.guestProcess = true;
            switch (process.getState()) {
                case 'R': pstat.state = PState.RUNNING; break;
                case 'S': pstat.state = PState.SLEEPING; break;
                case 'D': pstat.state = PState.WAITING; break;
                case 'T': pstat.state = PState.STOPPED; break;
                default: pstat.state = PState.OTHER;
            }
            result.add(pstat);
        }
        return result;
    }
}
