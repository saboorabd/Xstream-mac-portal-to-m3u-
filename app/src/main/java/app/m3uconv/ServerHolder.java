package app.m3uconv;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.m3uconv.core.Http;
import app.m3uconv.core.LocalServer;

/** Keeps one LocalServer alive for the whole process (used by the activity and the foreground service). */
final class ServerHolder {
    static final int PREFERRED_PORT = 8765;
    private static LocalServer server;

    private ServerHolder() {
    }

    static synchronized int ensure(Context ctx) throws IOException {
        if (server == null) {
            final Context app = ctx.getApplicationContext();
            server = new LocalServer(PREFERRED_PORT, () -> readIndex(app));
        }
        return server.start();   // no-op when already running
    }

    private static String readIndex(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("index.html")) {
            return new String(Http.readAll(in), StandardCharsets.UTF_8);
        }
    }
}
