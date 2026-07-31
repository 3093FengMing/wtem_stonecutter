package me.fengming.wtem.common.util;

/** Small helpers for resource identifiers stored as strings.
 * @author FengMing*/
public final class ResourceIds {
    private ResourceIds() {}

    public static String path(String id) {
        if (id == null) return "";
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }
}
