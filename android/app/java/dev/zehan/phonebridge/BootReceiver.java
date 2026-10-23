package dev.zehan.phonebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：不然重启手机后批准/响铃全静默。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
                || "android.intent.action.QUICKBOOT_POWERON".equals(i.getAction())) {
            LinkService.ensure(c);
        }
    }
}
