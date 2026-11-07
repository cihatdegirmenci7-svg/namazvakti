package tr.com.haktan.vakit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class PrayerNotificationService extends Service {
    private static final String CHANNEL_ID = "vakit_lock_screen";
    private static final int NOTIFICATION_ID = 8104;
    private static final String PREFS = "vakit_notification_state";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> fetching = new HashSet<>();
    private Runnable minuteTicker;

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Kilit ekranı vakit kartı", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Yaklaşan namaz vaktini ve bugünün vakitlerini gösterir.");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    public static void start(Context context, String payload) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return;
        Intent intent = new Intent(context, PrayerNotificationService.class);
        if (payload != null) intent.putExtra("prayer_payload", payload);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel(this);
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String payload = intent.getStringExtra("prayer_payload");
            if (payload != null) savePayload(payload);
        }
        refreshIfNeeded();
        updateNotification();
        scheduleMinuteUpdate();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void savePayload(String raw) {
        try {
            JSONObject data = new JSONObject(raw);
            String today = dayKey(new Date());
            SharedPreferences.Editor editor = prefs().edit()
                .putString("location", data.optString("location", ""))
                .putString("city", data.optString("city", ""))
                .putString("district", data.optString("district", ""))
                .putString("times_" + today, data.optJSONObject("timings") == null ? "{}" : data.optJSONObject("timings").toString())
                .putString("last_times", data.optJSONObject("timings") == null ? "{}" : data.optJSONObject("timings").toString());
            JSONObject coords = data.optJSONObject("coords");
            if (coords != null) {
                editor.putString("latitude", coords.optString("latitude", ""));
                editor.putString("longitude", coords.optString("longitude", ""));
            }
            editor.apply();
        } catch (Exception ignored) { }
    }

    private String dayKey(Date date) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date); }
    private String apiDate(Date date) { return new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(date); }
    private String timesFor(String key) { return prefs().getString("times_" + key, ""); }

    private void refreshIfNeeded() {
        Date now = new Date();
        String today = dayKey(now);
        if (timesFor(today).isEmpty()) fetchDay(now);
        JSONObject current = parseTimes(timesFor(today));
        int isha = minuteOf(current.optString("Isha", ""));
        Calendar next = Calendar.getInstance(); next.add(Calendar.DAY_OF_YEAR, 1);
        if (isha >= 0 && now.getHours() * 60 + now.getMinutes() >= isha) {
            if (timesFor(dayKey(next.getTime())).isEmpty()) fetchDay(next.getTime());
        }
    }

    private void fetchDay(Date date) {
        final String key = dayKey(date);
        synchronized (fetching) { if (fetching.contains(key)) return; fetching.add(key); }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                SharedPreferences p = prefs();
                String lat = p.getString("latitude", "");
                String lon = p.getString("longitude", "");
                String urlText;
                if (!lat.isEmpty() && !lon.isEmpty()) {
                    urlText = "https://api.aladhan.com/v1/timings/" + apiDate(date) + "?latitude=" + enc(lat) + "&longitude=" + enc(lon) + "&method=13&school=1";
                } else {
                    String city = p.getString("district", "");
                    if (city.isEmpty()) city = p.getString("city", "İstanbul");
                    urlText = "https://api.aladhan.com/v1/timingsByCity/" + apiDate(date) + "?city=" + enc(city) + "&country=Turkey&method=13&school=1";
                    if ("İstanbul".equals(p.getString("city", "")) && !p.getString("district", "").isEmpty()) urlText += "&state=Istanbul";
                }
                connection = (HttpURLConnection) new URL(urlText).openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String line; while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject response = new JSONObject(body.toString());
                JSONObject data = response.optJSONObject("data");
                JSONObject timings = data == null ? null : data.optJSONObject("timings");
                if (response.optInt("code") == 200 && timings != null) {
                    prefs().edit().putString("times_" + key, timings.toString()).putString("last_times", timings.toString()).apply();
                    handler.post(this::updateNotification);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
                synchronized (fetching) { fetching.remove(key); }
            }
        }, "vakit-times-refresh").start();
    }

    private String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private JSONObject parseTimes(String raw) { try { return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw); } catch (Exception e) { return new JSONObject(); } }
    private int minuteOf(String raw) {
        if (raw == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
    }

    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] NAMES = {"İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};
    private static final String[] NEXT_FORMS = {"İmsağa", "Güneşe", "Öğleye", "İkindiye", "Akşama", "Yatsıya"};
    private static final int[] LABEL_IDS = {R.id.label_fajr, R.id.label_sunrise, R.id.label_dhuhr, R.id.label_asr, R.id.label_maghrib, R.id.label_isha};
    private static final int[] TIME_IDS = {R.id.time_fajr, R.id.time_sunrise, R.id.time_dhuhr, R.id.time_asr, R.id.time_maghrib, R.id.time_isha};
    private static final int WHITE = 0xFFF7F5F0;
    private static final int MUTED = 0xFFC8C7C3;
    private static final int RED = 0xFFFF5959;

    private void updateNotification() {
        refreshIfNeeded();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Date now = new Date();
        String today = dayKey(now);
        JSONObject times = parseTimes(timesFor(today));
        if (times.length() == 0) times = parseTimes(prefs().getString("last_times", "{}"));
        int nowMinute = now.getHours() * 60 + now.getMinutes();
        int active = -1, next = -1, nextMinute = -1;
        for (int i = 0; i < KEYS.length; i++) {
            int minute = minuteOf(times.optString(KEYS[i], ""));
            if (minute < 0) continue;
            if (minute <= nowMinute) active = i;
            if (next < 0 && i != 1 && minute > nowMinute) { next = i; nextMinute = minute; }
        }
        if (active < 0 && times.length() > 0) active = 5;
        Date nextAt = null;
        if (next >= 0) {
            Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, nextMinute / 60); c.set(Calendar.MINUTE, nextMinute % 60); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); nextAt = c.getTime();
        } else if (times.length() > 0) {
            Calendar tomorrow = Calendar.getInstance(); tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            String tomorrowKey = dayKey(tomorrow.getTime());
            JSONObject tomorrowTimes = parseTimes(timesFor(tomorrowKey));
            int fajr = minuteOf(tomorrowTimes.optString("Fajr", ""));
            if (fajr >= 0) { next = 0; nextMinute = fajr; tomorrow.set(Calendar.HOUR_OF_DAY, fajr / 60); tomorrow.set(Calendar.MINUTE, fajr % 60); tomorrow.set(Calendar.SECOND, 0); tomorrow.set(Calendar.MILLISECOND, 0); nextAt = tomorrow.getTime(); }
            else fetchDay(tomorrow.getTime());
        }
        long remaining = nextAt == null ? -1 : Math.max(0, nextAt.getTime() - now.getTime());
        int remainingMinutes = remaining < 0 ? -1 : (int) Math.ceil(remaining / 60000.0);
        String title = next >= 0 && remainingMinutes >= 0 ? NEXT_FORMS[next] + ": " + remainingMinutes + " dk" : "Namaz vakitleri";
        String location = prefs().getString("location", "");

        RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notification_prayer);
        compact.setTextViewText(R.id.notification_title, title);
        compact.setTextViewText(R.id.notification_location, location.isEmpty() ? "Bugünün namaz vakitleri" : location);
        for (int i = 0; i < KEYS.length; i++) {
            String t = times.optString(KEYS[i], "--:--");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2})").matcher(t);
            if (m.find()) t = m.group(1);
            compact.setTextViewText(LABEL_IDS[i], NAMES[i]);
            compact.setTextViewText(TIME_IDS[i], t);
            int color = (i == active) ? RED : WHITE;
            compact.setTextColor(LABEL_IDS[i], color);
            compact.setTextColor(TIME_IDS[i], color);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(Icon.createWithBitmap(statusIcon(remainingMinutes)))
            .setContentTitle(title).setContentText(location)
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS).setPriority(Notification.PRIORITY_LOW)
            .setColor(0xFF103B32);
        if (Build.VERSION.SDK_INT >= 24) {
            builder.setCustomContentView(compact).setCustomBigContentView(compact);
        } else {
            builder.setContent(compact);
        }
        Notification.Builder publicBuilder = new Notification.Builder(this)
            .setSmallIcon(Icon.createWithBitmap(statusIcon(remainingMinutes)))
            .setContentTitle(title).setContentText("Bugünün namaz vakitleri")
            .setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 24) publicBuilder.setCustomContentView(compact).setCustomBigContentView(compact);
        else publicBuilder.setContent(compact);
        builder.setPublicVersion(publicBuilder.build());
        return builder.build();
    }

    private Bitmap statusIcon(int minutes) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        Path moon = new Path();
        moon.moveTo(58, 8); moon.cubicTo(27, 3, 8, 24, 8, 49); moon.cubicTo(8, 76, 30, 93, 56, 88); moon.cubicTo(72, 85, 84, 72, 88, 57); moon.cubicTo(75, 72, 57, 76, 42, 68); moon.cubicTo(19, 56, 19, 29, 37, 14); moon.cubicTo(43, 10, 51, 8, 58, 8); moon.close();
        canvas.drawPath(moon, p);
        if (minutes >= 0 && minutes < 60) {
            String value = String.valueOf(minutes);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            p.setTextSize(value.length() == 1 ? 38 : 30);
            float x = (96 - p.measureText(value)) / 2f;
            canvas.drawText(value, x, 59, p);
            p.setXfermode(null);
        }
        return bitmap;
    }

    private void scheduleMinuteUpdate() {
        if (minuteTicker != null) handler.removeCallbacks(minuteTicker);
        minuteTicker = new Runnable() {
            @Override public void run() {
                updateNotification();
                long delay = 60000 - (System.currentTimeMillis() % 60000);
                handler.postDelayed(this, Math.max(1000, delay));
            }
        };
        long delay = 60000 - (System.currentTimeMillis() % 60000);
        handler.postDelayed(minuteTicker, Math.max(1000, delay));
    }

    @Override public void onDestroy() {
        if (minuteTicker != null) handler.removeCallbacks(minuteTicker);
        super.onDestroy();
    }
}
