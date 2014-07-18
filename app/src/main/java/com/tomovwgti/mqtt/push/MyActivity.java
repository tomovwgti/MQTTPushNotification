package com.tomovwgti.mqtt.push;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


public class MyActivity extends Activity {
    private static final String TAG = MyActivity.class.getSimpleName();
    private final MyActivity self = this;

    private String mDeviceID;
    private EditText mServer;
    private EditText mTopic;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        mDeviceID = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        ((TextView) findViewById(R.id.deviceid)).setText(mDeviceID);

        final Button startButton = ((Button) findViewById(R.id.start_button));
        final Button stopButton = ((Button) findViewById(R.id.stop_button));
        mServer = (EditText) findViewById(R.id.server_address);
        mTopic = (EditText) findViewById(R.id.topic);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mServer.getText().toString().equals("")
                        || mTopic.getText().toString().equals("")) {
                    return;
                }
                SharedPreferences.Editor editor = getSharedPreferences(PushService.TAG, MODE_PRIVATE).edit();
                editor.putString(PushService.PREF_DEVICE_ID, mDeviceID);
                editor.putString(PushService.PREF_SERVER_ADDRESS, mServer.getText().toString());
                editor.putString(PushService.PREF_TOPIC, mTopic.getText().toString());
                editor.apply();
                mServer.setEnabled(false);
                mTopic.setEnabled(false);
                PushService.actionStart(self.getApplicationContext());
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PushService.actionStop(self.getApplicationContext());
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                mServer.setEnabled(true);
                mTopic.setEnabled(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences p = getSharedPreferences(PushService.TAG, MODE_PRIVATE);
        boolean started = p.getBoolean(PushService.PREF_STARTED, false);

        mServer.setText(p.getString(PushService.PREF_SERVER_ADDRESS, ""));
        mTopic.setText(p.getString(PushService.PREF_TOPIC, ""));

        ((Button) findViewById(R.id.start_button)).setEnabled(!started);
        ((Button) findViewById(R.id.stop_button)).setEnabled(started);
        if (started) {
            mServer.setEnabled(false);
            mTopic.setEnabled(false);
        }
    }
}
