package app.m3uconv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.m3uconv.core.Engine;
import app.m3uconv.core.MiniJson;

/**
 * Thin shell: a WebView showing assets/index.html, and one JavaScript bridge (window.Android.call).
 * All real work happens in core.Engine. There is no server and no background service.
 */
public class MainActivity extends Activity {
    private static final Engine ENGINE = new Engine();
    private static final ExecutorService BG = Executors.newCachedThreadPool();
    private static final int BG_COLOR = 0xFF0B0B12;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG_COLOR);
        getWindow().setNavigationBarColor(BG_COLOR);

        web = new WebView(this);
        web.setBackgroundColor(BG_COLOR);
        setContentView(web);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);                       // file:///android_asset still works
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);   // channel logos are often http
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", v -> {
            if (v == null || !v.contains("1")) finish();
        });
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** window.Android.call(id, method, argsJson): runs on a worker thread, answers via window.__native(id, resultJson). */
    public final class Bridge {
        @JavascriptInterface
        public void call(final String id, final String method, final String args) {
            BG.execute(() -> {
                String res;
                try {
                    res = dispatch(method, args);
                } catch (Throwable t) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("error", "Internal error: " + t);
                    m.put("code", "internal");
                    res = MiniJson.write(m);
                }
                final String out = res;
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.__native(" + MiniJson.quote(id) + "," + MiniJson.quote(out) + ")", null));
            });
        }
    }

    private static String obj(String k, String v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return MiniJson.write(m);
    }

    private static String error(String msg) {
        return obj("error", msg);
    }

    private String dispatch(String method, String args) {
        Map<String, Object> a = null;
        try {
            a = MiniJson.map(MiniJson.parse(args == null || args.isEmpty() ? "{}" : args));
        } catch (IllegalArgumentException ignored) {
        }
        if (a == null) a = new HashMap<>();
        switch (method) {
            case "kvGet":
                return obj("value", getSharedPreferences("m3u", MODE_PRIVATE).getString(MiniJson.str(a.get("key")), ""));
            case "kvSet":
                getSharedPreferences("m3u", MODE_PRIVATE).edit()
                        .putString(MiniJson.str(a.get("key")), MiniJson.str(a.get("value"))).apply();
                return "{}";
            case "keepAwake": {
                final boolean on = Boolean.TRUE.equals(a.get("on"));
                runOnUiThread(() -> {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
                return "{}";
            }
            case "copy": {
                final String text = MiniJson.str(a.get("text"));
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("m3u", text));
                });
                return "{}";
            }
            case "save":
                return save(a);
            case "view":
                return openFile(a, false);
            case "share":
                return openFile(a, true);
            case "openUrl":
                return openUrl(a);
            default:
                return ENGINE.call(method, args);
        }
    }

    /** Writes the finished playlist into the public Downloads folder. */
    private String save(Map<String, Object> a) {
        String job = MiniJson.str(a.get("job"));
        String m3u = ENGINE.jobResult(job);
        if (m3u == null) return error("Nothing to save yet");
        String name = ENGINE.jobFileName(job);
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return error("Cannot create the file in Downloads");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(m3u.getBytes(StandardCharsets.UTF_8));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("uri", uri.toString());
            r.put("name", name);
            return MiniJson.write(r);
        } catch (Exception e) {
            return error("Save failed: " + e.getMessage());
        }
    }

    private String openFile(Map<String, Object> a, final boolean share) {
        final Uri uri = Uri.parse(MiniJson.str(a.get("uri")));
        runOnUiThread(() -> {
            try {
                Intent i;
                if (share) {
                    i = new Intent(Intent.ACTION_SEND);
                    i.setType("audio/x-mpegurl");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                } else {
                    i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "audio/x-mpegurl");
                }
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, share ? "Share playlist" : "Open playlist with"));
            } catch (ActivityNotFoundException e) {
                toast("No app found for this. The file is in your Downloads folder.");
            }
        });
        return "{}";
    }

    private String openUrl(Map<String, Object> a) {
        final Uri uri = Uri.parse(MiniJson.str(a.get("url")));
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "video/*");
                startActivity(Intent.createChooser(i, "Play with"));
            } catch (ActivityNotFoundException e) {
                toast("No video player app found");
            }
        });
        return "{}";
    }
}
