package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 「我的」页里那张作者头像：从酷安主页自动取，取到之后缓存进 prefs。
 *
 * <p>酷安网页版对未登录的访客只给一张「请用酷安 App 扫码查看详情」的门禁页，
 * 拿不到头像；所以这里走 App 客户端那套接口：{@code /v6/user/profile?uid=}，
 * 头部按客户端的方式带上 {@code X-App-Token}（由公开的 app token + 时间戳 +
 * 设备标识算出来，见 {@link #appToken}），响应里 {@code data.userAvatar} 就是头像地址，
 * 再把它下下来。</p>
 *
 * <p>整条链路都可能失败（没网、接口变了、被挡），所以：<b>失败一律静默</b>，
 * 调用方继续用原来那张兜底图标；成功的话写进 prefs，下次进来先铺缓存、再去取新的，
 * 不会出现「每次都要等网络」的白屏。</p>
 */
final class CoolapkAvatar {

    private static final String TAG = "SystemUIHook";
    /** 作者的酷安 uid。 */
    private static final String UID = "24154051";
    /** 头像缓存的 prefs 键（PNG 的 Base64）。 */
    private static final String KEY_AVATAR = "authorAvatarB64";
    /** 生成 {@code X-App-Token} 用的公开 app token 与包名。 */
    private static final String APP_TOKEN = "c67ef5943784d09750dcfbb31020f0ab";
    private static final String APP_ID = "com.coolapk.market";
    /** 头像最长边：界面里只显示 56dp，256 足够清晰，存 prefs 也不会太大。 */
    private static final int MAX_SIDE = 256;
    private static final int TIMEOUT_MS = 8000;

    /** 头像取到了；回调在主线程。 */
    interface Callback {
        void onAvatar(Bitmap bitmap);
    }

    /** 同一时刻只跑一趟，页面来回切不会叠出一堆请求。 */
    private static volatile boolean sLoading;

    private CoolapkAvatar() {
    }

    /** 上次取到的头像；没有返回 null。同步读，用来当占位图。 */
    static Bitmap cached(final Context context) {
        String base64 = prefs(context).getString(KEY_AVATAR, "");
        if (base64 == null || base64.length() == 0) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 去酷安取一张作者头像，成功后回调（主线程）。失败什么都不做。
     *
     * <p>网络只能放子线程。</p>
     */
    static void load(final Context context, final Callback callback) {
        if (sLoading) {
            return;
        }
        sLoading = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = avatarUrl(app);
                    if (url == null) {
                        return;
                    }
                    byte[] png = downloadAvatar(url);
                    if (png == null) {
                        return;
                    }
                    final Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (bitmap == null) {
                        return;
                    }
                    prefs(app).edit().putString(KEY_AVATAR,
                            Base64.encodeToString(png, Base64.DEFAULT)).apply();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onAvatar(bitmap);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "coolapk avatar failed - " + t);
                } finally {
                    sLoading = false;
                }
            }
        }, "coolapk-avatar").start();
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PrefKeys.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 从接口响应里抠出头像地址。 */
    private static String avatarUrl(final Context context) throws Exception {
        String body = get("https://api.coolapk.com/v6/user/profile?uid=" + UID, context);
        JSONObject data = new JSONObject(body).optJSONObject("data");
        if (data == null) {
            Log.w(TAG, "coolapk profile: no data field");
            return null;
        }
        String[] keys = {"userAvatar", "avatar", "userBigAvatar"};
        for (String key : keys) {
            String url = data.optString(key, "");
            if (url != null && url.length() > 0) {
                return url;
            }
        }
        Log.w(TAG, "coolapk profile: no avatar field");
        return null;
    }

    /** 下载头像并降采样 + 重新编码，保证缓存里那份体积可控。 */
    private static byte[] downloadAvatar(final String url) throws Exception {
        byte[] raw = getBytes(url, null);
        if (raw == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (bitmap == null) {
            return null;
        }
        // 用 PNG：头像可能是带透明底的插画，转 JPEG 会把透明处压成黑色
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    private static String get(final String url, final Context context) throws Exception {
        byte[] raw = getBytes(url, context);
        return raw == null ? null : new String(raw, "UTF-8");
    }

    private static byte[] getBytes(final String url, final Context context) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (context != null) {
                // 客户端那套接口要校验身份，缺一个头就是 403
                String device = deviceId(context);
                conn.setRequestProperty("X-App-Id", APP_ID);
                conn.setRequestProperty("X-App-Version", "11.0.0");
                conn.setRequestProperty("X-App-Code", "2101010");
                conn.setRequestProperty("X-App-Token", appToken(device));
                conn.setRequestProperty("X-Api-Version", "3");
                conn.setRequestProperty("X-App-Mode", "universal");
                conn.setRequestProperty("X-App-Supported", "2000000");
                conn.setRequestProperty("X-Sdk-Int", "30");
                conn.setRequestProperty("X-Sdk-Locale", "zh-CN");
                conn.setRequestProperty("X-Dark-Mode", "0");
                conn.setRequestProperty("Accept", "application/json");
            }
            conn.setRequestProperty("User-Agent", "okhttp/4.9.3");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "coolapk HTTP " + conn.getResponseCode() + " " + url);
                return null;
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 客户端算的 {@code X-App-Token}：{@code md5(token://包名/appToken?时间戳$设备标识&包名) + "0x" + 时间戳十六进制}。
     */
    private static String appToken(final String deviceId) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        String plain = "token://" + APP_ID + "/" + APP_TOKEN + "?" + timestamp + "$" + deviceId
                + "&" + APP_ID;
        return md5(plain) + "0x" + Long.toHexString(timestamp);
    }

    private static String deviceId(final Context context) {
        try {
            String id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return "0000000000000000";
    }

    private static String md5(final String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5")
                .digest(text.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private static byte[] readAll(final InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
