package com.iishko.selfhost;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.lang.reflect.Method;

public final class StartUtils {
    private StartUtils() {}

    public static void startServerService(Context context) {
        Intent i = new Intent(context, ServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(context, i);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            context.startService(i);
        } catch (Throwable ignored) {}
    }
}
