package com.halo.expressassistant.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DeviceIdHelper {

    public static String get(Context context) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final StringBuilder detail = new StringBuilder();
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                detail.append("connected;");
                try {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken("com.xiaomi.passport.IPassportCommonService");
                        data.writeString("passport");
                        data.writeInt(1);
                        boolean ok = binder.transact(1, data, reply, 0);
                        detail.append("transact=").append(ok).append(';');
                        reply.readException();
                        int flag = reply.readInt();
                        detail.append("flag=").append(flag).append(';');
                        if (flag != 0) {
                            Bundle bundle = reply.readBundle(DeviceIdHelper.class.getClassLoader());
                            detail.append("bundle=").append(bundle == null ? "null" : bundle.keySet()).append(';');
                            if (bundle != null) {
                                detail.append("bundleVals=");
                                for (String k : bundle.keySet()) {
                                    detail.append(k).append('=').append(bundle.get(k)).append('|');
                                }
                                Bundle info = bundle.getBundle("device_info");
                                detail.append("info=").append(info == null ? "null" : info.keySet()).append(';');
                                if (info != null) {
                                    detail.append("infoVals=");
                                    for (String k : info.keySet()) {
                                        detail.append(k).append('=').append(info.get(k)).append('|');
                                    }
                                    result.set(info.getString("hashed_device_id"));
                                }
                            }
                        }
                    } finally {
                        reply.recycle();
                        data.recycle();
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                detail.append("disconnected;");
                latch.countDown();
            }
        };
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.xiaomi.account", "com.xiaomi.account.service.PassportCommonService"));
        boolean bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        detail.append("bound=").append(bound).append(';');
        if (!bound) return "BIND_FAILED;" + detail;
        if (!latch.await(6, TimeUnit.SECONDS)) {
            try { context.unbindService(conn); } catch (Throwable ignored) {}
            return "TIMEOUT;" + detail;
        }
        try { context.unbindService(conn); } catch (Throwable ignored) {}
        if (error.get() != null) throw new RuntimeException(error.get() + " DETAIL=" + detail);
        return result.get() == null ? "NULL;" + detail : result.get();
    }
}
