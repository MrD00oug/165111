package com.lucas.trippermeteor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Resposta do instalador do Android durante a atualização. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(c, "Tripper Meteor atualizado", Toast.LENGTH_LONG).show();
        } else {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(c, "Atualização falhou: " + msg, Toast.LENGTH_LONG).show();
            Core.get(c).onLog("atualização falhou: " + msg, Pod.LOG_ERR);
        }
    }
}
