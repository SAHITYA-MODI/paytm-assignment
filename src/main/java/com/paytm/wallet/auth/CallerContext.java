package com.paytm.wallet.auth;

/**
 * Where {@link AuthenticationFilter} parks the authenticated caller for the rest of the
 * request. A servlet request attribute rather than a ThreadLocal: Spring MVC can hand it
 * to a controller directly via {@code @RequestAttribute}, and it cannot leak across
 * requests when Tomcat recycles the thread.
 */
public final class CallerContext {

    /** Request attribute holding the authenticated userId (a String). */
    public static final String USER_ID_ATTRIBUTE = "wallet.callerUserId";

    private CallerContext() {
    }
}