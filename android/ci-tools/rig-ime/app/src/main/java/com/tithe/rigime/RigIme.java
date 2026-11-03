package com.tithe.rigime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/**
 * Wallet-rig test IME. Commits broadcast text verbatim through the
 * current input connection — no autocorrect, no suggestions, no
 * composing region. The stock keyboard on the google_apis emulator
 * image corrupts injected key events into Compose text fields
 * (wallet-rig runs 41167632342 and 41169729500: characters dropped,
 * and suggested phrases appended to a 44-char address on focus
 * change), which makes long-string field entry ungradeable. The rig
 * driver sends: adb shell am broadcast -a com.tithe.rigime.INPUT_TEXT
 * --es text "&lt;value&gt;" while the target field is focused.
 * Test tooling only; never shipped with the app.
 */
public class RigIme extends InputMethodService {

    public static final String ACTION_INPUT_TEXT = "com.tithe.rigime.INPUT_TEXT";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String text = intent.getStringExtra("text");
            if (text == null) {
                return;
            }
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
                ic.commitText(text, 1);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION_INPUT_TEXT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
            // already unregistered
        }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        TextView view = new TextView(this);
        view.setText("TITHE RIG IME");
        return view;
    }
}
