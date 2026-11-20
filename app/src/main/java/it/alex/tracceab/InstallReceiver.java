package it.alex.tracceab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/** Riceve l'esito dell'installazione dell'aggiornamento. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            else confirm = getLegacy(intent);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(ctx, "Aggiornamento non installato" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent getLegacy(Intent i) { return i.getParcelableExtra(Intent.EXTRA_INTENT); }
}
