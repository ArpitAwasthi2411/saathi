package com.arpit.saathi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;

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

            Bitmap bmp = null;
            Uri stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            String type = in.getType();
            if (stream != null && type != null && type.startsWith("image/")) bmp = decode(stream);

            if (text.trim().isEmpty() && bmp == null) {
                Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show();
            } else {
                final String fText = text;
                final Bitmap fBmp = bmp;
                new Thread(() -> Capture.save(getApplicationContext(), fText, null, "share", fBmp)).start();
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
