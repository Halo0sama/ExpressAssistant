package com.halo.expressassistant.service;

import android.content.Context;

public class AdvertisingIdHelper {

    public static String probe(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String method : new String[]{"getOAID", "getVAID", "getAAID", "getUDID"}) {
            try {
                Class<?> cls = Class.forName("com.android.id.impl.IdProviderImpl");
                Object inst = cls.newInstance();
                Object value = cls.getMethod(method, Context.class).invoke(inst, context);
                sb.append(method).append('=').append(value == null ? "null" : value).append('\n');
            } catch (Throwable t) {
                Throwable c = t;
                while (c.getCause() != null) c = c.getCause();
                sb.append(method).append("=ERR ").append(c).append('\n');
            }
        }
        return sb.toString();
    }
}
