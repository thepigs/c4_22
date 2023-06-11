package tech.pepar.cared4;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.net.NetworkRequest;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyForegroundService extends Service {
    private static final String TAG = "tech.pepar.cared4";

    private SensorManager mSensorManager;
    private FirebaseAuth mAuth;
    private volatile FirebaseUser currentUser;
    private Sensor mSigMotion;

    private FirebaseFirestore fireStore = FirebaseFirestore.getInstance();
    private Sensor stepCounterSensor;

    private volatile int lastStepCounter = -1;
    private volatile int currentStepCounter;

    private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    private boolean loggedIn;

    private Runnable pinger = new Runnable() {
        @Override
        public void run() {
            if (lastStepCounter > 0) {
                int diff = currentStepCounter - lastStepCounter;
                if (diff>0) {
                    reportSensorData("step_count_diff", mapOf("steps", diff));
                    lastStepCounter = currentStepCounter;
                }
            }
            reportSensorData("ping", mapOf());
            handler.postDelayed(pinger, 5 * 60 * 1000);

        }
    };

    private SensorEventListener stepConterEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {

            currentStepCounter = (int) sensorEvent.values[0];
            if (lastStepCounter < 0)
                lastStepCounter = currentStepCounter;

            Log.i(TAG, "step count " + currentStepCounter);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };
    private float[] gravity = new float[3];
    private float[] linear_acceleration = new float[3];
    private float[][] fall_detect = new float[3][2];

    private SensorEventListener accelEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float alpha = 0.8F;

            // Isolate the force of gravity with the low-pass filter.
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];

            // Remove the gravity contribution with the high-pass filter.
            linear_acceleration[0] = event.values[0] - gravity[0];
            linear_acceleration[1] = event.values[1] - gravity[1];
            linear_acceleration[2] = event.values[2] - gravity[2];
            double raw_mag = Math.sqrt(
                    Math.pow(event.values[0], 2) +
                            Math.pow(event.values[1], 2) +
                            Math.pow(event.values[2], 2)
            );

            double accel_mag = Math.sqrt(
                    Math.pow(linear_acceleration[0], 2) +
                            Math.pow(linear_acceleration[1], 2) +
                            Math.pow(linear_acceleration[2], 2)
            );
            fall_detect[0] = fall_detect[1];
            fall_detect[1] = fall_detect[2];
            fall_detect[2] = new float[]{(float) accel_mag, (float) raw_mag};

            boolean method1 = fall_detect[0][1]<6&&fall_detect[1][1]<6 && fall_detect[2][0]>6;
            boolean method2 = fall_detect[2][1]>20;
            if (method1||method2){
                    Log.i(TAG,"Fall detect!");
                    reportSensorData("fall_detect",mapOf("method1",method1,"method2",method2));
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    private boolean isCharging;
    private Sensor accelSensor;

    public void init() {

        mAuth = FirebaseAuth.getInstance();
        mAuth.addAuthStateListener(new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                currentUser = firebaseAuth.getCurrentUser();
                if (!loggedIn && currentUser != null) {
                    loggedIn = true;
                    startListeners();
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        final String CHANNELID = "Foreground Service ID";
        NotificationChannel channel = new NotificationChannel(
                CHANNELID,
                CHANNELID,
                NotificationManager.IMPORTANCE_HIGH
        );

        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification.Builder notification = new Notification.Builder(this, CHANNELID)
                .setContentText("Service is running")
                .setContentTitle("Service enabled")
                .setSmallIcon(R.mipmap.ic_launcher);

        startForeground(1001, notification.build());
        init();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        if (mSigMotion != null) {
            mSensorManager.cancelTriggerSensor(sigMotionEventListener, mSigMotion);
        }
        if (stepCounterSensor != null) {
            mSensorManager.unregisterListener(stepConterEventListener);
        }
        executor.shutdownNow();
    }

    private void reportSensorData(String sensorName, Map<String, Object> data) {
        Log.i(TAG, "sensor: " + sensorName + ", data: " + data);
        if (currentUser != null) {
            DocumentReference ref = fireStore.collection("sensor_summary/" + currentUser.getEmail() + "/sensors").document(sensorName);
            data.put("timestamp", new Date());
            ref.set(data);
            DocumentReference docRef = fireStore.collection("sensor_history/" + currentUser.getEmail() + "/" + sensorName).document(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            docRef.set(data);
        } else {
            Log.i(TAG, "currentUser==null");
        }
    }

    private Map<String, Object> mapOf(Object... objects) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < objects.length; i += 2) {
            map.put((String) objects[i], objects[i + 1]);
        }
        return map;
    }

    private long lastBatteryReport;
    private boolean wasCharging;
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent batteryStatus) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            float batteryPct = (int) (level * 100 / (float) scale);
            long now = System.currentTimeMillis();
            if (isCharging!=wasCharging || (now - lastBatteryReport > 10 * 60 * 1000)) {
                reportSensorData("battery", mapOf("level", batteryPct, "charging", isCharging, "status", status));
                lastBatteryReport = now;
            }
            wasCharging = isCharging;
        }
    };

    private PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            System.out.println("call state " + state + " " + phoneNumber);
        }

        @Override
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
            System.out.println("display info " + telephonyDisplayInfo);
        }
    };

    private TelephonyCallback callStateListener = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
            new TelephonyCallback() {

            }
            : null;

    private TriggerEventListener sigMotionEventListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent triggerEvent) {
            System.out.println("trigger event " + triggerEvent);
            mSensorManager.requestTriggerSensor(sigMotionEventListener, mSigMotion);
        }
    };
    Handler handler = new Handler();

    void startListeners() {
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        for (Sensor s : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            Log.e("tech.pepar.cared4", s.getName() + " " + s.getStringType() + " " + s.getMaxDelay() + " " + s.getMinDelay() + " " + s.isWakeUpSensor());
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "granted");
            stepCounterSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (stepCounterSensor != null) {
                mSensorManager.registerListener(stepConterEventListener, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            accelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED);
            if (accelSensor != null) {
                mSensorManager.registerListener(accelEventListener, accelSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }

        } else {
            Log.i(TAG, "NOT granted");
        }
        mSigMotion = mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        if (mSigMotion != null) {
            mSensorManager.requestTriggerSensor(sigMotionEventListener, mSigMotion);
        }
        this.registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
            }
        } else {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }
        handler.postDelayed(pinger, 5 * 60 * 1000);
//        executor.scheduleAtFixedRate(pinger, 0, 5, TimeUnit.MINUTES);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
