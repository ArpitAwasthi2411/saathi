package com.arpit.saathi;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Covers the app until the phone's own fingerprint, face or screen lock confirms it is you. */
public class LockActivity extends Activity {
    private boolean asking, askedOnce;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        box.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("Saathi is locked");
        t.setTextSize(22);
        t.setTextColor(getColor(R.color.text));
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        TextView btn = new TextView(this);
        btn.setText("Unlock");
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(getColor(R.color.onAccent));
        btn.setBackgroundResource(R.drawable.bg_btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 200), Ui.dp(this, 48));
        lp.topMargin = Ui.dp(this, 24);
        btn.setOnClickListener(v -> ask());
        box.addView(btn, lp);
        setContentView(box);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (App.unlocked || !Prefs.lock(this)) {
            finish();
            return;
        }
        if (!askedOnce) { // ask by itself once; after a cancel the Unlock button asks again
            askedOnce = true;
            ask();
        }
    }

    private void ok() {
        App.unlocked = true;
        finish();
    }

    @SuppressWarnings("deprecation")
    private void ask() {
        if (asking) return;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) {
            ok(); // the phone itself has no lock, so there is nothing to check against
            return;
        }
        asking = true;
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricPrompt.Builder pb = new BiometricPrompt.Builder(this).setTitle("Unlock Saathi");
            if (Build.VERSION.SDK_INT >= 30) {
                pb.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                pb.setDeviceCredentialAllowed(true);
            }
            pb.build().authenticate(new CancellationSignal(), getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            asking = false;
                            ok();
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence msg) {
                            asking = false; // cancelled or failed: stay locked, the button asks again
                        }
                    });
        } else {
            Intent i = km.createConfirmDeviceCredentialIntent("Unlock Saathi", null);
            if (i == null) {
                asking = false;
                ok();
                return;
            }
            startActivityForResult(i, 7);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        asking = false;
        if (requestCode == 7 && resultCode == RESULT_OK) ok();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
