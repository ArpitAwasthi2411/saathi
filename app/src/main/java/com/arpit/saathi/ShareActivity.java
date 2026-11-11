package com.arpit.saathi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Receives "Share → Saathi" from any app. No screen of its own: saves, confirms with a toast, closes. */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent in = getIntent();
            String text = "";
            CharSequence subject = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            CharSequence body = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (subject != null) text += subject + "\n";
            if (body != null) text += body;

            List<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
                ArrayList<Uri> many = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (many != null) uris.addAll(many);
            } else {
                Uri one = in.getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) uris.add(one);
            }
            final List<Bitmap> shots = new ArrayList<>();
            for (Uri u : uris) {
                if (shots.size() >= Capture.MAX_IMAGES) break;
                // only content the sending app handed over; never a raw file path
                if (u == null || !"content".equals(u.getScheme())) continue;
                Bitmap bmp = decode(u);
                if (bmp != null) shots.add(bmp);
            }

            if (text.trim().isEmpty() && shots.isEmpty()) {
                Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show();
            } else {
                final String fText = text;
                new Thread(() -> {
                    if (shots.isEmpty()) {
                        Capture.save(getApplicationContext(), fText, null, "share", null, false);
                    }
                    for (int i = 0; i < shots.size(); i++) {
                        // several pictures shared together become one item
                        Capture.save(getApplicationContext(), i == 0 ? fText : "", null, "share", shots.get(i), i > 0);
                    }
                }).start();
                Toast.makeText(this, "Saved to Saathi ✓", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save that", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private Bitmap decode(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 3200) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, o2);
        }
    }
}
