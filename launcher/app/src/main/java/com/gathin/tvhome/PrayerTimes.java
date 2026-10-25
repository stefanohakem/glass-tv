package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Next prayer time and a countdown in one TextView. One request a day to the Aladhan API, cached. */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String API = "https://api.aladhan.com/v1/timingsByCity"
            + "?city=Kuala%20Lumpur&country=Malaysia&method=17";
    private static final String[] NAMES = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final long RETRY_MS = 10 * 60_000L;

    private final TextView view;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int[] minutes;
    private String day = "";
    private boolean fetching;
    private boolean running;
    private long lastTry;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            ui.postDelayed(this, 30_000);
        }
    };

    PrayerTimes(Context ctx, TextView view) {
        this.view = view;
        this.prefs = ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }

    void start() {
        running = true;
        ui.removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private void update() {
        String today = today();
        if (!today.equals(day)) loadCache(today);
        if (minutes == null) {
            view.setText("");
            if (!fetching && System.currentTimeMillis() - lastTry > RETRY_MS) fetch(today);
            return;
        }
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int idx = -1;
        for (int i = 0; i < minutes.length; i++) {
            if (minutes[i] > now) { idx = i; break; }
        }
        int target;
        if (idx < 0) { idx = 0; target = minutes[0] + 1440; } else target = minutes[idx];
        view.setText(NAMES[idx] + " " + fmt(minutes[idx]) + "  -  " + countdown(target - now));
    }

    private void loadCache(String today) {
        minutes = null;
        day = "";
        if (!today.equals(prefs.getString("day", ""))) return;
        String[] p = prefs.getString("times", "").split(",");
        if (p.length != KEYS.length) return;
        try {
            int[] m = new int[p.length];
            for (int i = 0; i < p.length; i++) m[i] = Integer.parseInt(p[i]);
            minutes = m;
            day = today;
        } catch (NumberFormatException e) {
            minutes = null;
        }
    }

    private void fetch(String today) {
        fetching = true;
        lastTry = System.currentTimeMillis();
        new Thread(() -> {
            int[] result = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject t = new JSONObject(sb.toString()).getJSONObject("data").getJSONObject("timings");
                    int[] m = new int[KEYS.length];
                    for (int i = 0; i < KEYS.length; i++) m[i] = parse(t.getString(KEYS[i]));
                    result = m;
                }
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            final int[] r = result;
            ui.post(() -> {
                fetching = false;
                if (r != null) {
                    StringBuilder s = new StringBuilder();
                    for (int i = 0; i < r.length; i++) s.append(i > 0 ? "," : "").append(r[i]);
                    prefs.edit().putString("day", today).putString("times", s.toString()).apply();
                    minutes = r;
                    day = today;
                }
                if (running) update();
            });
        }, "prayer-times").start();
    }

    /** "05:52" or "05:52 (+08)" to minutes since midnight. */
    private static int parse(String s) {
        s = s.trim();
        return Integer.parseInt(s.substring(0, 2)) * 60 + Integer.parseInt(s.substring(3, 5));
    }

    private static String fmt(int min) {
        int h = (min / 60) % 24;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, min % 60, h >= 12 ? "PM" : "AM");
    }

    private static String countdown(int diff) {
        return diff >= 60 ? (diff / 60) + "j " + (diff % 60) + "m" : diff + "m";
    }
}
