package app.m3uconv;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                saveDownload(url, contentDisposition));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        startForegroundService(new Intent(this, ServerService.class));   // keeps the server alive in background

        new Thread(() -> {
            try {
                final int port = ServerHolder.ensure(getApplicationContext());
                runOnUiThread(() -> web.loadUrl("http://127.0.0.1:" + port + "/"));
            } catch (IOException e) {
                toast("Cannot start local server: " + e.getMessage());
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** JavaScript hooks used by index.html (copy link / open in a player app). */
    public final class Bridge {
        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("m3u", text));
            toast("Link copied");
        }

        @JavascriptInterface
        public void open(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(url), "audio/x-mpegurl");
                startActivity(Intent.createChooser(i, "Open playlist with"));
            } catch (ActivityNotFoundException e) {
                copy(url);
                toast("No player app found - link copied, paste it in your IPTV app");
            }
        }
    }

    /** "Download .m3u" button: save the playlist into the phone's Downloads folder. */
    private void saveDownload(final String url, final String contentDisposition) {
        new Thread(() -> {
            try {
                String name = "playlist.m3u";
                if (contentDisposition != null) {
                    Matcher m = Pattern.compile("filename=\"?([^\";]+)\"?").matcher(contentDisposition);
                    if (m.find()) name = m.group(1);
                }
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(180000);
                if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());

                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("cannot create file");
                try (InputStream in = c.getInputStream(); OutputStream os = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                toast("Saved in Downloads: " + name);
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            }
        }).start();
    }
}
