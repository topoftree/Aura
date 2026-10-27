using System;
using System.Collections.Generic;
using System.Reflection;
using Aura.Diagnostics;

class Replay
{
    static int checks;
    static void Check(bool value, string name) { if (!value) throw new Exception(name); checks++; }
    static void Main()
    {
        var type = Assembly.Load("StardewModdingAPI").GetType("StardewModdingAPI.Framework.Command")!;
        var field = type.GetField("<Callback>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var prop = type.GetProperty("Callback")!;
        int calls = 0, target = 60; bool valid = true;
        Action<string, string[]> original = (_, a) => { calls++; target = int.Parse(a[0]); };
        var command = Activator.CreateInstance(type, new object?[] { null, "fps", "", original })!;
        var reports = new List<string>();
        var watch = new CommandWatch(command, field, "fps", new[] { "120" }, long.MaxValue,
            () => valid, () => "target=" + target, reports.Add);
        watch.Arm(); Check(reports[0] == "ARMED", "actual SMAPI readonly callback can be observed");
        ((Action<string, string[]>)prop.GetValue(command)!)("fps", new[] { "90" });
        Check(calls == 1 && reports.Count == 1 && target == 90, "different command executes untouched, no false acknowledgement");
        ((Action<string, string[]>)prop.GetValue(command)!)("fps", new[] { "120" });
        Check(calls == 2 && target == 120, "original callback exactly once");
        Check(reports[1] == "ENTERED\ntarget=90" && reports[2] == "RETURNED\ntarget=90\ntarget=120", "actual before/after readback");
        Check(ReferenceEquals(prop.GetValue(command), original), "original delegate restored");
        watch = new CommandWatch(command, field, "fps", new[] { "120" }, long.MaxValue,
            () => valid, () => "", reports.Add);
        watch.Arm(); valid = false; watch.Expire(0);
        Check(ReferenceEquals(prop.GetValue(command), original) && reports[^1] == "UNOBSERVED", "revoked request restores delegate");
        valid = true;
        var sentinel = new InvalidOperationException("sentinel");
        Action<string, string[]> throws = (_, _) => throw sentinel;
        command = Activator.CreateInstance(type, new object?[] { null, "fps", "", throws })!;
        watch = new CommandWatch(command, field, "fps", new[] { "120" }, long.MaxValue,
            () => true, () => "same", reports.Add);
        watch.Arm();
        try { ((Action<string, string[]>)prop.GetValue(command)!)("fps", new[] { "120" }); throw new Exception("lost exception"); }
        catch (InvalidOperationException e) { Check(ReferenceEquals(e, sentinel), "original exception identity preserved"); }
        Check(reports[^1].StartsWith("THREW\n") && ReferenceEquals(prop.GetValue(command), throws), "exception acknowledgement and cleanup");
        // Even diagnostic storage/state failures must not swallow or duplicate the user's command.
        command = Activator.CreateInstance(type, new object?[] { null, "fps", "", original })!;
        watch = new CommandWatch(command, field, "fps", new[] { "120" }, long.MaxValue,
            () => throw new Exception(), () => "", reports.Add);
        watch.Arm(); ((Action<string, string[]>)prop.GetValue(command)!)("fps", new[] { "120" });
        Check(calls == 3, "observer failure preserves callback"); watch.Restore();
        var meter = new Meter(); meter.Begin(100, 100); meter.End(150); meter.Begin(200, 100); meter.End(210);
        Check(meter.Report("UPDATE").Contains("count=2") && meter.Report("UPDATE").Contains("unpaired=0"), "paired loop events");
        meter.Reset(); Check(meter.Report("UPDATE").Contains("count=0"), "new capture resets statistics");
        Console.WriteLine("PASS " + checks + " readback/loop contracts against actual SMAPI 4.5.2 Command");
    }
}
