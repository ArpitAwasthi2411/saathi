package com.arpit.saathi.assist;

import android.content.Intent;
import android.speech.RecognitionService;

/** Android requires an assistant to declare a speech recogniser. Saathi does not listen, so this is empty. */
public class SaathiRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
