package io.github.foxdenlabs.vivarium;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONObject;

/*
 * Background wake-ups for tools that were allowed to have them.
 *
 * One periodic job (Android's minimum, 15 minutes) looks at wakes.json and
 * runs every tool that is due, one after another, each in its own hidden
 * WebView (WakeRunner). Android decides the exact moment; Doze and battery
 * settings can stretch it.
 *
 *   wakes.json   { "<id>": { "every": minutes, "last": ms, "next": ms } }   written here
 *   grants.json  { "<id>": { "net": "any"|[hosts], "notify": bool, "wake": bool } }   written by the shelf
 */
public class WakeJob extends JobService {

    static final int PERIODIC = 0x7701;
    static final int NOW = 0x7702;
    static final int MIN_EVERY = 15;
    static final int MAX_EVERY = 24 * 60;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final java.util.Map<Integer, Run> runs = new java.util.HashMap<>();
    private static final java.util.Set<String> busy = new java.util.HashSet<>();

    @Override
    public boolean onStartJob(JobParameters params) {
        String only = params.getExtras() != null ? params.getExtras().getString("only") : null;
        JSONObject grants = readJson(this, "grants.json");
        long now = System.currentTimeMillis();
        Run run = new Run(params);
        run.manual = only != null;
        synchronized (LOCK) {
            JSONObject w = readJson(this, "wakes.json");
            Iterator<String> it = w.keys();
            List<String> gone = new ArrayList<>();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject g = grants.optJSONObject(id);
                boolean exists = new File(getFilesDir(), "tools/" + id + "/tool.html").isFile();
                if (g == null || !g.optBoolean("wake", false) || !exists) { gone.add(id); continue; }
                JSONObject e = w.optJSONObject(id);
                if (only != null ? only.equals(id) : e != null && e.optLong("next", 0) <= now + 60_000) run.due.add(id);
            }
            for (String id : gone) w.remove(id);
            if (!gone.isEmpty()) writeJson(this, "wakes.json", w);
            if (w.length() == 0) cancelPeriodic(this);
        }
        if (run.due.isEmpty()) return false;
        runs.put(params.getJobId(), run);
        MAIN.post(run::next);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Run r = runs.remove(params.getJobId());
        if (r != null) { r.stopped = true; r.due.clear(); }
        return true;
    }

    private final class Run {
        final JobParameters params;
        final ArrayDeque<String> due = new ArrayDeque<>();
        boolean stopped;
        boolean manual;   // "Wake it now": the tool should look even if it just did
        Run(JobParameters p) { params = p; }

        void next() {
            String id = due.poll();
            if (id == null || stopped) {
                // A moment for the last tool's storage writes to settle.
                MAIN.postDelayed(() -> {
                    if (runs.remove(params.getJobId()) != null) jobFinished(params, false);
                }, 1500);
                return;
            }
            if (busy.contains(id)) { MAIN.post(this::next); return; }
            markRun(WakeJob.this, id);
            if (VivariumPlugin.deliverWake(id, manual)) {
                MAIN.postDelayed(this::next, 500);
                return;
            }
            busy.add(id);
            WakeRunner.run(WakeJob.this, id, manual, (ok) -> {
                busy.remove(id);
                MAIN.postDelayed(this::next, 800);
            });
        }
    }

    /* ═════════════════ the store ═════════════════ */

    static JSONObject set(Context ctx, String id, int every) {
        every = Math.max(MIN_EVERY, Math.min(MAX_EVERY, every));
        JSONObject e;
        synchronized (LOCK) {
            JSONObject w = readJson(ctx, "wakes.json");
            e = w.optJSONObject(id);
            long now = System.currentTimeMillis();
            try {
                if (e == null) { e = new JSONObject(); e.put("last", 0); }
                long last = e.optLong("last", 0);
                e.put("every", every);
                e.put("next", last > 0 ? Math.max(now, last + every * 60_000L) : now + every * 60_000L);
                w.put(id, e);
            } catch (Exception ignored) {}
            writeJson(ctx, "wakes.json", w);
        }
        ensurePeriodic(ctx);
        return e;
    }

    static void clear(Context ctx, String id) {
        synchronized (LOCK) {
            JSONObject w = readJson(ctx, "wakes.json");
            w.remove(id);
            writeJson(ctx, "wakes.json", w);
            if (w.length() == 0) cancelPeriodic(ctx);
        }
    }

    static JSONObject all(Context ctx) {
        synchronized (LOCK) { return readJson(ctx, "wakes.json"); }
    }

    private static void markRun(Context ctx, String id) {
        synchronized (LOCK) {
            JSONObject w = readJson(ctx, "wakes.json");
            JSONObject e = w.optJSONObject(id);
            if (e == null) return;
            long now = System.currentTimeMillis();
            try {
                e.put("last", now);
                e.put("next", now + e.optInt("every", 60) * 60_000L);
            } catch (Exception ignored) {}
            writeJson(ctx, "wakes.json", w);
        }
    }

    /* ═════════════════ scheduling ═════════════════ */

    static void ensurePeriodic(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (js.getPendingJob(PERIODIC) != null) return;
        JobInfo job = new JobInfo.Builder(PERIODIC, new ComponentName(ctx, WakeJob.class))
            .setPeriodic(MIN_EVERY * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        try { js.schedule(job); } catch (Exception ignored) {}
    }

    /** Wakes are kept on disk; make sure the job exists whenever any are. */
    static void restore(Context ctx) {
        JSONObject w = all(ctx);
        if (w.length() > 0) ensurePeriodic(ctx);
    }

    static void cancelPeriodic(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(PERIODIC);
    }

    /** "Wake it now", for trying a tool's background run by hand. */
    static void runNow(Context ctx, String id) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("only", id);
        JobInfo job = new JobInfo.Builder(NOW, new ComponentName(ctx, WakeJob.class))
            .setMinimumLatency(0)
            .setOverrideDeadline(2000)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(extras)
            .build();
        try { js.schedule(job); } catch (Exception ignored) {}
    }

    /* ═════════════════ files ═════════════════ */

    static JSONObject readJson(Context ctx, String name) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.isFile()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(ToolServer.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void writeJson(Context ctx, String name, JSONObject o) {
        File dir = ctx.getFilesDir();
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(new File(dir, name))) tmp.delete();
    }
}
