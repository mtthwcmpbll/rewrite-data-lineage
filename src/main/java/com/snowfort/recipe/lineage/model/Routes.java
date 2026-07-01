package com.snowfort.recipe.lineage.model;

import org.jspecify.annotations.Nullable;

/**
 * Helpers for normalizing HTTP routes into the path-only form used by {@link ExternalIdentifier},
 * keeping any scheme+authority separate so inbound and outbound identifiers compare equal on path
 * (FR-004 / FR-005 / SC-006).
 */
public final class Routes {

    private Routes() {
    }

    /** The scheme+host/authority of an absolute URL (e.g. {@code inventory} for {@code http://inventory/x}), or {@code null} if relative. */
    public static @Nullable String authorityOf(String rawUrl) {
        int scheme = rawUrl.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        int start = scheme + 3;
        int slash = rawUrl.indexOf('/', start);
        String authority = slash < 0 ? rawUrl.substring(start) : rawUrl.substring(start, slash);
        return authority.isEmpty() ? null : authority;
    }

    /**
     * The path-only portion of a URL in template form: scheme, authority and query string removed,
     * with a leading slash guaranteed. {@code http://inventory/reserve?x=1} &rarr; {@code /reserve};
     * {@code /orders/{id}} &rarr; {@code /orders/{id}}.
     */
    public static String pathTemplate(String rawUrl) {
        String s = rawUrl;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash < 0 ? "" : s.substring(slash);
        }
        int query = s.indexOf('?');
        if (query >= 0) {
            s = s.substring(0, query);
        }
        if (s.isEmpty()) {
            return "/";
        }
        if (s.charAt(0) != '/') {
            s = '/' + s;
        }
        // Collapse a trailing slash (but keep the root "/").
        if (s.length() > 1 && s.charAt(s.length() - 1) == '/') {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Join a class-level route prefix with a method-level path, normalizing slashes. */
    public static String join(@Nullable String prefix, @Nullable String path) {
        String p = prefix == null ? "" : prefix.trim();
        String m = path == null ? "" : path.trim();
        if (p.isEmpty()) {
            return m.isEmpty() ? "/" : ensureLeadingSlash(m);
        }
        if (m.isEmpty()) {
            return ensureLeadingSlash(p);
        }
        String left = ensureLeadingSlash(p);
        if (left.length() > 1 && left.charAt(left.length() - 1) == '/') {
            left = left.substring(0, left.length() - 1);
        }
        String right = m.charAt(0) == '/' ? m : '/' + m;
        return left + right;
    }

    private static String ensureLeadingSlash(String s) {
        return s.charAt(0) == '/' ? s : '/' + s;
    }
}
