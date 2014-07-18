package com.tomovwgti.mqtt.push;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.Log;
import android.widget.Toast;

import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttPersistenceException;
import com.ibm.mqtt.MqttSimpleCallback;

import java.io.IOException;

public class PushService extends Service {
    public static final String TAG = PushService.class.getSimpleName();

    // Let's not use the MQTT persistence.
    private static MqttPersistence MQTT_PERSISTENCE = null;
    // Set quality of services to 0 (at most once delivery), since we don't want
    // push notifications
    // arrive more than once. However, this means that some messages might get
    // lost (delivery is not guaranteed)
    private static int[] MQTT_QUALITIES_OF_SERVICE = {
            0
    };

    // MQTT client ID, which is given the broker. In this example, I also use
    // this for the topic header.
    // You can use this to run push notifications for multiple apps with one
    // MQTT broker.
    public static String MQTT_CLIENT_ID = "android";

    // These are the actions for the service (name are descriptive enough)
    private static final String ACTION_START = MQTT_CLIENT_ID + ".START";
    private static final String ACTION_STOP = MQTT_CLIENT_ID + ".STOP";
    private static final String ACTION_KEEPALIVE = MQTT_CLIENT_ID + ".KEEP_ALIVE";
    private static final String ACTION_RECONNECT = MQTT_CLIENT_ID + ".RECONNECT";

    // Connection log for the push service. Good for debugging.
    private ConnectionLog mLog;

    // Connectivity manager to determining, when the phone loses connection
    private ConnectivityManager mConnMan;

    // Whether or not the service has been started.
    private boolean mStarted;

    // This the application level keep-alive interval, that is used by the
    // AlarmManager
    // to keep the connection active, even when the device goes to sleep.
    private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

    // Retry intervals, when the connection is lost.
    private static final long INITIAL_RETRY_INTERVAL = 1000 * 10;
    private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

    // Preferences instance
    private SharedPreferences mPrefs;
    // We store in the preferences, whether or not the service has been started
    public static final String PREF_STARTED = "isStarted";
    // We also store the device ID
    public static final String PREF_DEVICE_ID = "deviceID";
    // We also store the server address
    public static final String PREF_SERVER_ADDRESS = "server";
    // We also store the topic
    public static final String PREF_TOPIC = "topic";
    // We store the last retry interval
    public static final String PREF_RETRY = "retryInterval";

    // Notification id
    private static final int NOTIF_CONNECTED = 0;

    // This is the instance of an MQTT connection.
    private MQTTConnection mConnection;
    private long mStartTime;

    // Static method to start the service
    public static void actionStart(Context ctx) {
        Intent i = new Intent(ctx, PushService.class);
        i.setAction(ACTION_START);
        ctx.startService(i);
    }

    // Static method to stop the service
    public static void actionStop(Context ctx) {
        Intent i = new Intent(ctx, PushService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // Static method to send a keep alive message
    // Not call
    public static void actionPing(Context ctx) {
        Intent i = new Intent(ctx, PushService.class);
        i.setAction(ACTION_KEEPALIVE);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        log("Creating service");
        mStartTime = System.currentTimeMillis();

        try {
            mLog = new ConnectionLog();
            Log.i(TAG, "Opened log at " + mLog.getPath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to open log", e);
        }

        // Get instances of preferences, connectivity manager and notification
        // manager
        mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
        mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        /*
         * If our process was reaped by the system for any reason we need to
         * restore our state with merely a call to onCreate. We record the last
         * "started" value and restore it here if necessary.
         */
        handleCrashedService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");

        if (intent == null) {
            return START_STICKY;
        }
        // Do an appropriate action based on the intent.
        if (intent.getAction().equals(ACTION_STOP)) {
            String topic = mPrefs.getString(PREF_TOPIC, null);
            try {
                mConnection.unsubscribeToTopic(topic);
                Toast.makeText(this, "Unsubscribe: " + topic, Toast.LENGTH_SHORT).show();
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            stop();
            stopSelf();
        } else if (intent.getAction().equals(ACTION_START)) {
            start();
        } else if (intent.getAction().equals(ACTION_KEEPALIVE)) {
            keepAlive();
        } else if (intent.getAction().equals(ACTION_RECONNECT)) {
            if (isNetworkAvailable()) {
                reconnectIfNecessary();
            }
        }

        return START_STICKY;
    }

    // This method does any necessary clean-up need in case the server has been
    // destroyed by the system
    // and then restarted
    private void handleCrashedService() {
        if (wasStarted()) {
            log("Handling crashed service...");
            // stop the keep alives
            stopKeepAlives();

            // Do a clean start
            start();
        }
    }

    @Override
    public void onDestroy() {
        log("Service destroyed (started=" + mStarted + ")");

        // Stop the services, if it has been started
        if (mStarted) {
            stop();
        }

        try {
            if (mLog != null)
                mLog.close();
        } catch (IOException e) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        return null;
    }

    // log helper function
    private void log(String message) {
        log(message, null);
    }

    private void log(String message, Throwable e) {
        if (e != null) {
            Log.e(TAG, message, e);

        } else {
            Log.i(TAG, message);
        }

        if (mLog != null) {
            try {
                mLog.println(message);
            } catch (IOException ex) {
            }
        }
    }

    // Reads whether or not the service has been started from the preferences
    private boolean wasStarted() {
        return mPrefs.getBoolean(PREF_STARTED, false);
    }

    // Sets whether or not the services has been started in the preferences.
    private void setStarted(boolean started) {
        mPrefs.edit().putBoolean(PREF_STARTED, started).apply();
        mStarted = started;
    }

    private synchronized void start() {
        log("Starting service...");

        // Do nothing, if the service is already running.
        if (mStarted) {
            Log.w(TAG, "Attempt to start connection that is already active");
            return;
        }

        // Establish an MQTT connection
        connect();

        // Register a connectivity listener
        registerReceiver(mConnectivityChanged, new IntentFilter(
                ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private synchronized void stop() {
        // Do nothing, if the service is not running.
        if (!mStarted) {
            Log.w(TAG, "Attempt to stop connection not active.");
            return;
        }

        // Save stopped state in the preferences
        setStarted(false);

        // Remove the connectivity receiver
        unregisterReceiver(mConnectivityChanged);
        // Any existing reconnect timers should be removed, since we explicitly
        // stopping the service.
        cancelReconnect();

        // Destroy the MQTT connection if there is one
        if (mConnection != null) {
            mConnection.disconnect();
            mConnection = null;
        }
    }

    //
    private synchronized void connect() {
        log("Connecting...");

        // fetch the server, topic from the preferences.
        final String server = mPrefs.getString(PREF_SERVER_ADDRESS, null);
        final String topic = mPrefs.getString(PREF_TOPIC, null);
        Log.d(TAG, "server: " + server);
        Log.d(TAG, "topic: " + topic);
        // Create a new connection only if the device id is not NULL
        if (server == null) {
            log("Server not found.");
        } else {
            // 接続は別スレッドで
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mConnection = new MQTTConnection(server, topic);
                    } catch (MqttException e) {
                        // Schedule a reconnect, if we failed to connect
                        log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"));
                        if (isNetworkAvailable()) {
                            scheduleReconnect(mStartTime);
                        }
                    }
                    setStarted(true);
                }
            });
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private synchronized void keepAlive() {
        try {
            // Send a keep alive, if there is a connection.
            if (mStarted && mConnection != null) {
                mConnection.sendKeepAlive();
            }
        } catch (MqttException e) {
            log("MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);

            mConnection.disconnect();
            mConnection = null;
            cancelReconnect();
        }
    }

    // Schedule application level keep-alives using the AlarmManager
    private void startKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, PushService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + KEEP_ALIVE_INTERVAL, KEEP_ALIVE_INTERVAL, pi);
    }

    // Remove all scheduled keep alives
    private void stopKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, PushService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.cancel(pi);
    }

    // We schedule a reconnect based on the starttime of the service
    public void scheduleReconnect(long startTime) {
        // the last keep-alive interval
        long interval = mPrefs.getLong(PREF_RETRY, INITIAL_RETRY_INTERVAL);

        // Calculate the elapsed time since the start
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;

        // Set an appropriate interval based on the elapsed time since start
        if (elapsed < interval) {
            interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
        } else {
            interval = INITIAL_RETRY_INTERVAL;
        }

        log("Rescheduling connection in " + interval + "ms.");

        // Save the new internval
        mPrefs.edit().putLong(PREF_RETRY, interval).apply();

        // Schedule a reconnect using the alarm manager.
        Intent i = new Intent();
        i.setClass(this, PushService.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
    }

    // Remove the scheduled reconnect
    public void cancelReconnect() {
        Intent i = new Intent();
        i.setClass(this, PushService.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.cancel(pi);
    }

    private synchronized void reconnectIfNecessary() {
        if (mStarted && mConnection == null) {
            log("Reconnecting...");
            connect();
        }
    }

    // This receiver listeners for network changes and updates the MQTT
    // connection
    // accordingly
    private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get network info
            NetworkInfo info = (NetworkInfo) intent
                    .getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

            // Is there connectivity?
            boolean hasConnectivity = (info != null && info.isConnected());

            log("Connectivity changed: connected=" + hasConnectivity);

            if (hasConnectivity) {
                reconnectIfNecessary();
            } else if (mConnection != null) {
                // if there no connectivity, make sure MQTT connection is
                // destroyed
                mConnection.disconnect();
                cancelReconnect();
                mConnection = null;
            }
        }
    };

    private void showNotification(String notifyString) {
        // Intent の作成
        Intent intent = new Intent(this, MyActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // LargeIcon の Bitmap を生成
        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(),
                R.drawable.ic_launcher);

        // NotificationBuilderを作成
        Notification.Builder builder = new Notification.Builder(getApplicationContext());
        builder.setContentIntent(contentIntent);
        // ステータスバーに表示されるテキスト
        builder.setTicker("You have message");
        // アイコン
        builder.setSmallIcon(R.drawable.ic_launcher);
        // Notificationを開いたときに表示されるタイトル
        builder.setContentTitle("MQTT push");
        // Notificationを開いたときに表示されるサブタイトル
        builder.setContentText(notifyString);
        // Notificationを開いたときに表示されるアイコン
        builder.setLargeIcon(largeIcon);
        // 通知するタイミング
        builder.setWhen(System.currentTimeMillis());
        // 通知時の音・バイブ・ライト
        builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE
                | Notification.DEFAULT_LIGHTS);
        // タップするとキャンセル(消える)
        builder.setAutoCancel(true);

        // NotificationManagerを取得
        NotificationManager manager = (NotificationManager) getSystemService(Service.NOTIFICATION_SERVICE);
        // Notificationを作成して通知
        manager.notify(NOTIF_CONNECTED, builder.build());
    }

    // Check if we are online
    private boolean isNetworkAvailable() {
        NetworkInfo info = mConnMan.getActiveNetworkInfo();
        if (info == null) {
            return false;
        }
        return info.isConnected();
    }

    // This inner class is a wrapper on top of MQTT client.
    private class MQTTConnection implements MqttSimpleCallback {
        IMqttClient mqttClient = null;
        // the port at which the broker is running.
        private final int MQTT_BROKER_PORT_NUM = 1883;

        // We don't need to remember any state between the connections, so we use a
        // clean start.
        private final boolean MQTT_CLEAN_START = true;
        // Let's set the internal keep alive for MQTT to 15 mins. I haven't tested
        // this value much. It could probably be increased.
        private final short MQTT_KEEP_ALIVE = 60 * 15;
        private final int MQTT_QUALITY_OF_SERVICE = 0;
        // The broker should not retain any messages.
        private final boolean MQTT_RETAINED_PUBLISH = false;
        private final String clientID = "android";

        // Creates a new connection given the broker address and initial topic
        public MQTTConnection(String brokerHostName, String initTopic) throws MqttException {
            // Create connection spec
            String mqttConnSpec = "tcp://" + brokerHostName + "@" + MQTT_BROKER_PORT_NUM;
            // Create the client and connect
            mqttClient = MqttClient.createMqttClient(mqttConnSpec, MQTT_PERSISTENCE);
            mqttClient.connect(clientID, MQTT_CLEAN_START, MQTT_KEEP_ALIVE);

            // register this client app has being able to receive messages
            mqttClient.registerSimpleHandler(this);

            subscribeToTopic(initTopic);

            log("Connection established to " + brokerHostName + " on topic " + initTopic);

            // Save start time
            mStartTime = System.currentTimeMillis();
            // Star the keep-alives
            startKeepAlives();
        }

        // Disconnect
        public void disconnect() {
            try {
                stopKeepAlives();
                mqttClient.disconnect();
            } catch (MqttPersistenceException e) {
                log("MqttException" + (e.getMessage() != null ? e.getMessage() : " NULL"), e);
            }
        }

        /*
         * Send a request to the message broker to be sent messages published
         * with the specified topic name. Wildcards are allowed.
         */
        private void subscribeToTopic(String topicName) throws MqttException {

            if ((mqttClient == null) || (!mqttClient.isConnected())) {
                // quick sanity check - don't try and subscribe if we don't have
                // a connection
                log("Connection error" + "No connection");
            } else {
                String[] topics = {
                        topicName
                };
                mqttClient.subscribe(topics, MQTT_QUALITIES_OF_SERVICE);
            }
        }

        /*
         * Send a request to the message broker to be sent messages published
         * with the specified topic name. Wildcards are allowed.
         */
        private void unsubscribeToTopic(String topicName) throws MqttException {
            if ((mqttClient == null) || (!mqttClient.isConnected())) {
                // quick sanity check - don't try and subscribe if we don't have
                // a connection
                log("Connection error" + "No connection");
            } else {
                String[] topics = {
                        topicName
                };
                mqttClient.unsubscribe(topics);
            }
        }

        /*
         * Sends a message to the message broker, requesting that it be
         * published to the specified topic.
         */
        private void publishToTopic(String topicName, String message) throws MqttException {
            if ((mqttClient == null) || (!mqttClient.isConnected())) {
                // quick sanity check - don't try and publish if we don't have
                // a connection
                log("No connection to public to");
            } else {
                mqttClient.publish(topicName, message.getBytes(), MQTT_QUALITY_OF_SERVICE,
                        MQTT_RETAINED_PUBLISH);
            }
        }

        /*
         * Called if the application loses it's connection to the message
         * broker.
         */
        public void connectionLost() throws Exception {
            log("Loss of connection" + "connection downed");
            stopKeepAlives();
            // null itself
            mConnection = null;
            if (isNetworkAvailable()) {
                reconnectIfNecessary();
            }
        }

        /*
         * Called when we receive a message from the message broker.
         */
        public void publishArrived(String topicName, byte[] payload, int qos, boolean retained) {
            // Show a notification
            String s = new String(payload);
            showNotification(s);
            log("Got message: " + s);
        }

        public void sendKeepAlive() throws MqttException {
            log("Sending keep alive");
            // publish to a keep-alive topic
            publishToTopic(MQTT_CLIENT_ID + "/keepalive", mPrefs.getString(PREF_DEVICE_ID, ""));
        }
    }
}
