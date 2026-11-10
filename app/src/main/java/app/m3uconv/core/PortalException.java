package app.m3uconv.core;

/** Anything that goes wrong while talking to the portal. */
public class PortalException extends RuntimeException {
    public PortalException(String m) {
        super(m);
    }

    public PortalException(String m, Throwable cause) {
        super(m, cause);
    }

    /** Portal rejected our token (expired / not logged in). */
    public static class Auth extends PortalException {
        public Auth(String m) {
            super(m);
        }
    }
}
