using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using StardewModdingAPI;

namespace Aura.Diagnostics;

// Application-owned observer. No game assets, Harmony patches, commands, or timing setters.
public sealed class Probe : Mod
{
    const BindingFlags Any = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
    string session = "", root = "", capture = "", lastCommand = "";
    long nextPoll, deadline, started, lease, lastState, startUtc;
    readonly Meter update = new(), draw = new();
    readonly StringBuilder states = new();
    object? runner, graphics;
    CommandWatch? watch;
    bool ready;

    public override void Entry(IModHelper helper)
    {
        try
        {
            // Fail closed if SMAPI's observed command ABI changes.
            if (Constants.ApiVersion.ToString() != "4.5.2") return;
            var config = Lines(Path.Combine(helper.DirectoryPath, "session.txt"), 512);
            if (config.Length != 3 || !Guid.TryParseExact(config[0], "D", out _) || config[2] != "1") return;
            long since = long.Parse(config[1], CultureInfo.InvariantCulture);
            using var process = Process.GetCurrentProcess();
            var expected = Path.GetFullPath(Path.Combine(helper.DirectoryPath, "..", "..", "StardewModdingAPI.exe"));
            if (!string.Equals(Environment.ProcessPath, expected, StringComparison.OrdinalIgnoreCase) ||
                new DateTimeOffset(process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() < since ||
                since > Utc() + 2000) return;
            session = config[0];
            root = Path.Combine("C:\\aura-smapi-diagnostics", session);
            if (!Directory.Exists(root)) return;
            helper.Events.GameLoop.UpdateTicking += (_, _) => Tick();
            helper.Events.GameLoop.UpdateTicked += (_, _) => { if (Live()) update.End(Stopwatch.GetTimestamp()); };
            helper.Events.Display.Rendering += (_, _) => { if (Live()) draw.Begin(Stopwatch.GetTimestamp(), started); };
            helper.Events.Display.Rendered += (_, _) => { if (Live()) draw.End(Stopwatch.GetTimestamp()); };
            AppDomain.CurrentDomain.ProcessExit += (_, _) => { watch?.Restore(); };
            Write("ready.txt", session + "\nREADY\n");
            ready = true;
        }
        catch { /* A diagnostic failure must never prevent the game from starting. */ }
    }

    static long Utc() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    static string[] Lines(string path, int limit)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length > limit || (info.Attributes & FileAttributes.ReparsePoint) != 0) return Array.Empty<string>();
        return File.ReadAllLines(path);
    }
    void Write(string file, string text)
    {
        try { File.WriteAllText(Path.Combine(root, file + ".tmp"), text, Encoding.ASCII);
            File.Move(Path.Combine(root, file + ".tmp"), Path.Combine(root, file), true); } catch { }
    }
    bool Live() => capture.Length != 0 && Stopwatch.GetTimestamp() <= deadline;
    void Tick()
    {
        if (!ready) return;
        try
        {
            long now = Stopwatch.GetTimestamp();
            if (now >= nextPoll)
            {
                Poll(now);
                nextPoll = now + Stopwatch.Frequency / (capture.Length == 0 ? 1 : 4);
            }
            if (Live())
            {
                update.Begin(now, started);
                if (now >= lastState && states.Length < 12000)
                { lastState = now + Stopwatch.Frequency / 4; states.Append(State(now - started)).Append('\n'); }
            }
            if (capture.Length != 0 && (now > deadline || Utc() > lease)) Finish("LEASE_OR_DEADLINE");
            if (watch?.Done == true) watch = null;
            else if (now >= nextWatchCheck) { nextWatchCheck = now + Stopwatch.Frequency / 4; watch?.Expire(Utc()); }
        }
        catch { watch?.Restore(); watch = null; if (capture.Length != 0) Finish("OBSERVER_ERROR"); }
    }
    void Poll(long now)
    {
        var c = Lines(Path.Combine(root, "control.txt"), 256);
        if (c.Length == 4 && c[0] == session && Guid.TryParseExact(c[1], "D", out _) &&
            long.TryParse(c[2], out long until) && until > Utc() && until <= Utc() + 2000)
        {
            lease = until;
            if (c[3] == "START" && capture.Length == 0 && c[1] != lastCapture)
            {
                capture = c[1]; started = now; startUtc = Utc(); deadline = now + Stopwatch.Frequency * 10;
                update.Reset(); draw.Reset(); states.Clear(); lastState = now;
                Write("loop.txt", session + "\n" + capture + "\nCOLLECTING\n");
            }
        }
        else if (capture.Length != 0) Finish("CONTROL_REVOKED");
        var r = Lines(Path.Combine(root, "command.txt"), 2048);
        if (r.Length != 4 || r[0] != session || r[1] == lastCommand ||
            !Guid.TryParseExact(r[1], "D", out _) || !long.TryParse(r[2], out long expires) ||
            expires <= Utc() || expires > Utc() + 15000) return;
        lastCommand = r[1]; watch?.Restore(); watch = null;
        string command = Encoding.Unicode.GetString(Convert.FromBase64String(r[3]));
        if (command.Length == 0 || command.Length > 256 || command.Any(c => char.IsControl(c) || c == '\u2028' || c == '\u2029')) return;
        string nonce = r[1];
        int requested = -1;
        var parts = command.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length == 2 && parts[0].Equals("fps", StringComparison.OrdinalIgnoreCase) && int.TryParse(parts[1], out int fps) && fps > 0 && fps <= 10000) requested = fps;
        // .NET 6 rounds TimeSpan.FromSeconds to milliseconds; newer runtimes retain finer precision.
        // Read the SAME conversion used by StardewFPS, never assume 120 means exactly 83333 ticks.
        long expected = requested > 0 ? TimeSpan.FromSeconds(1d / requested).Ticks : -1;
        Action<string> report = text => {
            int newline = text.IndexOf('\n');
            string phase = newline < 0 ? text : text[..newline];
            string rest = newline < 0 ? "" : text[newline..];
            Write("command-result.txt", session + "\n" + nonce + "\n" + phase + $" requested_fps={requested} expected_100ns={expected} utc_ms={Utc()}" + rest + "\n");
        };
        try
        {
            var manager = Helper.ConsoleCommands.GetType().GetField("CommandManager", Any)?.GetValue(Helper.ConsoleCommands);
            var parse = manager?.GetType().GetMethod("TryParse", Any);
            object?[] args = { command, null, null, null, 0 };
            if (parse == null || !(bool)parse.Invoke(manager, args)!) { report("UNREGISTERED"); return; }
            // Observe the existing dispatcher; do not dispatch, rewrite, retry or change screen routing.
            var target = args[3]!;
            var field = target.GetType().GetField("<Callback>k__BackingField", Any);
            if (field?.FieldType != typeof(Action<string, string[]>)) { report("UNSUPPORTED"); return; }
            watch = new CommandWatch(target, field, (string)args[1]!, (string[])args[2]!, expires,
                () => Lines(Path.Combine(root, "command.txt"), 2048).SequenceEqual(r),
                () => State(0), report);
            watch.Arm();
        }
        catch { watch?.Restore(); watch = null; report("UNSUPPORTED"); }
    }
    string lastCapture = "";
    long nextWatchCheck;
    void Finish(string reason)
    {
        if (capture.Length == 0) return;
        long end = Math.Min(Stopwatch.GetTimestamp(), deadline);
        var text = new StringBuilder(session + "\n" + capture + "\nCOMPLETE\n");
        text.Append($"LOOP reason={reason} frequency={Stopwatch.Frequency} start_ticks={started} end_ticks={end} start_utc_ms={startUtc} utc_ms={Utc()}\n");
        text.Append("SCOPE SMAPI_EVENTS_NOT_MONOGAME_TICK_OR_SCANOUT\n");
        text.Append(update.Report("UPDATE")).Append(draw.Report("DRAW")).Append(states);
        Write("loop.txt", text.ToString()); lastCapture = capture; capture = "";
    }
    string State(long ticks)
    {
        try
        {
            var game = AppDomain.CurrentDomain.GetAssemblies().FirstOrDefault(a => a.GetName().Name == "Stardew Valley");
            runner ??= game?.GetType("StardewValley.GameRunner")?.GetField("instance", Any)?.GetValue(null);
            graphics ??= game?.GetType("StardewValley.Game1")?.GetField("graphics", Any)?.GetValue(null);
            object? Read(object? obj, string name) => obj?.GetType().GetProperty(name, Any)?.GetValue(obj);
            long Flag(object? value) => value is bool b ? b ? 1 : 0 : -1;
            long target = Read(runner, "TargetElapsedTime") is TimeSpan span ? span.Ticks : -1;
            return $"STATE ticks={ticks} target_100ns={target} fixed={Flag(Read(runner, "IsFixedTimeStep"))} active={Flag(Read(runner, "IsActive"))} vsync={Flag(Read(graphics, "SynchronizeWithVerticalRetrace"))} world={Flag(Context.IsWorldReady)}";
        }
        catch { return $"STATE ticks={ticks} target_100ns=-1 fixed=-1 active=-1 vsync=-1 world=-1"; }
    }
}

// Temporary readback around the original SMAPI delegate. No private game code is patched.
internal sealed class CommandWatch
{
    readonly object target; readonly FieldInfo field; readonly string name; readonly string[] args;
    readonly long expiry; readonly Func<bool> current; readonly Func<string> state; readonly Action<string> report;
    readonly Action<string, string[]> original, wrapper; bool consumed;
    public bool Done => consumed;
    public CommandWatch(object target, FieldInfo field, string name, string[] args, long expiry,
        Func<bool> current, Func<string> state, Action<string> report)
    {
        this.target = target; this.field = field; this.name = name; this.args = args;
        this.expiry = expiry; this.current = current; this.state = state; this.report = report;
        original = (Action<string, string[]>)field.GetValue(target)!; wrapper = Invoke;
    }
    public void Arm() { field.SetValue(target, wrapper); report("ARMED"); }
    public void Restore() { if (ReferenceEquals(field.GetValue(target), wrapper)) field.SetValue(target, original); }
    public void Expire(long now) { if (now > expiry || !current()) { Restore(); if (!consumed) { consumed = true; report("UNOBSERVED"); } } }
    void Invoke(string command, string[] values)
    {
        bool allowed = false;
        try { allowed = !consumed && command == name && values.SequenceEqual(args) && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() <= expiry && current(); } catch { }
        if (!allowed)
        { original(command, values); return; }
        consumed = true;
        // Diagnostic failures must not skip the user's original callback or replace its exception.
        string before = ""; try { before = state(); report("ENTERED\n" + before); } catch { }
        try
        {
            original(command, values);
            try { report("RETURNED\n" + before + "\n" + state()); } catch { }
        }
        catch { try { report("THREW\n" + before + "\n" + state()); } catch { } throw; }
        finally { try { Restore(); } catch { } }
    }
}

internal sealed class Meter
{
    // Histogram of wall intervals and paired event durations. No per-frame file writes or allocation.
    readonly long[] interval = new long[15], duration = new long[15], bins = new long[101];
    static readonly double[] limits = { 1, 2, 4, 8, 12, 16.8, 20, 25, 33.5, 50, 100, 250, 1000, 5000 };
    long last, begin, count, missing; double sum, max;
    public void Reset() { Array.Clear(interval); Array.Clear(duration); Array.Clear(bins); last = begin = count = missing = 0; sum = max = 0; }
    static void Add(long[] bins, double ms) { int i = 0; while (i < limits.Length && ms > limits[i]) i++; bins[i]++; }
    public void Begin(long now, long start)
    {
        if (begin != 0) missing++;
        if (last != 0) { double ms = (now - last) * 1000d / Stopwatch.Frequency; Add(interval, ms); sum += ms; max = Math.Max(max, ms); }
        last = begin = now; count++;
        int bin = (int)((now - start) * 10 / Stopwatch.Frequency); if (bin >= 0 && bin < bins.Length) bins[bin]++;
    }
    public void End(long now) { if (begin != 0) { Add(duration, (now - begin) * 1000d / Stopwatch.Frequency); begin = 0; } else missing++; }
    public string Report(string label) => FormattableString.Invariant($"{label} count={count} interval_sum_us={(long)(sum * 1000)} interval_max_us={(long)(max * 1000)} unpaired={missing + (begin != 0 ? 1 : 0)}\n") +
        label + "_INTERVAL " + string.Join(',', interval) + "\n" + label + "_DURATION " + string.Join(',', duration) + "\n" + label + "_100MS " + string.Join(',', bins) + "\n";
}
