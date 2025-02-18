package com.addie.xcall.ui;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.addie.xcall.R;
import com.addie.xcall.model.User;
import com.addie.xcall.services.SinchService;
import com.addie.xcall.utils.AudioPlayer;
import com.addie.xcall.utils.NoResponseHandler;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.sinch.android.rtc.MissingPermissionException;
import com.sinch.android.rtc.PushPair;
import com.sinch.android.rtc.calling.Call;
import com.sinch.android.rtc.calling.CallEndCause;
import com.sinch.android.rtc.calling.CallListener;

import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.BindView;
import butterknife.ButterKnife;
import timber.log.Timber;
import uk.co.chrisjenx.calligraphy.CalligraphyContextWrapper;

/**
 * Displays the caller screen containing duration info, speakerphone, microphone and end call buttons
 */
public class CallScreenActivity extends BaseActivity implements SensorEventListener {

    private AudioPlayer mAudioPlayer;
    private Timer mTimer;
    private UpdateCallDurationTask mDurationTask;
    private static final String SHARED_PREFS_KEY = "shared_prefs";
    private static final String DB_DURATION_KEY = "duration";
    private String mSinchId;
    private static final String CALL_REQUEST_KEY = "call_request";

    private static final String SINCH_ID_KEY = "sinch_id";
    private boolean mServiceConnected = false;

    private AudioManager mAudioManager;

    private String mCallId;

    @BindView(R.id.callDuration)
    TextView mCallDuration;
    @BindView(R.id.callState)
    TextView mCallState;
    @BindView(R.id.fab_call_screen_end_call)
    FloatingActionButton mEndCallButton;
    @BindView(R.id.btn_speakerphone)
    ImageButton mSpeakerPhoneButton;
    @BindView(R.id.btn_microphone)
    ImageButton mMicrophoneButton;

    private String mOriginalCaller;
    private String mOriginalReceiver;
    private FirebaseDatabase mDatabase;
    private DatabaseReference mDBRef;
    private long mTotalDuration;
    private SensorManager mSensorManager;
    private Sensor mProximity;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private boolean mIsSpeakerPhone = false;
    private boolean mIsMicMuted = false;


    private static final String FCM_TOKEN_KEY = "fcm_token";
    private static final String CALLERID_DATA_KEY = "callerId";
    private String mFcmToken;

    private class UpdateCallDurationTask extends TimerTask {

        @Override
        public void run() {
            CallScreenActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_screen);

        ButterKnife.bind(this);

        Timber.d("CallScreenActivity launched");

        mAudioPlayer = new AudioPlayer(this);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        SharedPreferences prefs = getSharedPreferences(SHARED_PREFS_KEY, Context.MODE_PRIVATE);
        mFcmToken = prefs.getString(FCM_TOKEN_KEY, null);
        mSinchId = prefs.getString(SINCH_ID_KEY, null);


        initialiseAuthAndDatabaseReference();

        setupProximitySensor();

        handleCall();

        mEndCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                endCall();
            }
        });

        mSpeakerPhoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setSpeakerphoneOn(!mIsSpeakerPhone);
                mIsSpeakerPhone = !mIsSpeakerPhone;
                String toastMessage;
                if (mIsSpeakerPhone) {
                    toastMessage = getString(R.string.speakerphone_on);
                    mSpeakerPhoneButton.setImageResource(R.drawable.speakerphone_selected);
                } else {
                    toastMessage = getString(R.string.speakerphone_off);
                    mSpeakerPhoneButton.setImageResource(R.drawable.ic_speakerphone);
                }
                Toast.makeText(CallScreenActivity.this, toastMessage, Toast.LENGTH_SHORT).show();

            }
        });
        mMicrophoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAudioManager.setMicrophoneMute(!mIsMicMuted);
                mIsMicMuted = !mIsMicMuted;
                String toastMessage;
                if (mIsMicMuted) {
                    toastMessage = getString(R.string.mic_is_muted);
                    mMicrophoneButton.setImageResource(R.drawable.microphone_selected);
                } else {
                    toastMessage = getString(R.string.mic_is_unmuted);
                    mMicrophoneButton.setImageResource(R.drawable.ic_mic_off);
                }
                Toast.makeText(CallScreenActivity.this, toastMessage, Toast.LENGTH_SHORT).show();
            }
        });

        getDurationInitialValue();

    }

    /**
     * Fetches total call duration value of user from database
     */
    private void getDurationInitialValue() {

        //Get duration value
        mDBRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                saveDuration(dataSnapshot);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                System.out.println("The read failed: " + databaseError.getCode());
            }
        });

    }

    /**
     * Handles a call if it is picked up or if it is to be created
     */
    private void handleCall() {
        // Call picked up
        if (getIntent().hasExtra(CALLERID_DATA_KEY)) {
            mOriginalCaller = getIntent().getStringExtra(CALLERID_DATA_KEY);
            Timber.d("Intent has extra ");
            Timber.d(mOriginalCaller);
            createCallOrTooLate(mOriginalCaller);
        }
        //Call created by caller
        else {

            //Stop handler from creating NoResponseActivity
            NoResponseHandler.stopHandler();
            Intent intent = new Intent("finish_waitingcallactivity");
            sendBroadcast(intent);

            mCallId = getIntent().getStringExtra(SinchService.CALL_ID);
            Timber.d(mCallId);
        }
    }

    private void initialiseAuthAndDatabaseReference() {
        mDatabase = FirebaseDatabase.getInstance();
        mDBRef = mDatabase.getReference();

    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (event.values[0] < event.sensor.getMaximumRange() /*face near phone*/) {

            if (!mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        } else {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    private void setupProximitySensor() {

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mProximity = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        if (mProximity != null) {
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    mPowerManager.isPowerSaveMode()) {
                Toast.makeText(this, "If you experience any problems in the call, turn off device power saving mode and try again", Toast.LENGTH_LONG).show();
            }
            int field = 0x00000020;
            try {
                // Yeah, this is hidden field.
                field = PowerManager.class.getField("PROXIMITY_SCREEN_OFF_WAKE_LOCK").getInt(null);
            } catch (Throwable ignored) {
            }
            mWakeLock = mPowerManager.newWakeLock(field, getLocalClassName());
        }
    }

    /**
     * Creates a call back to the original caller
     * @param callerId
     */
    private void createCallOrTooLate(final String callerId) {


        Query query = mDBRef.child("users");
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.child(callerId).child(CALL_REQUEST_KEY).getValue().equals("true")) {
                    mDBRef.child("users").child(callerId).child(CALL_REQUEST_KEY).setValue("false");
                    if (mServiceConnected) {
                        createCall();
                    }
                } else {
                    //TODO Replace with proper activity
                    Toast.makeText(CallScreenActivity.this, "Too late. Someone else has picked the call", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(CallScreenActivity.this, MainActivity.class));
                    finish();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void saveDuration(DataSnapshot snapshot) {
        DataSnapshot list = snapshot.child("users");
        for (DataSnapshot ds : list.getChildren()) {

            if (ds.getKey().equals(mFcmToken)) {
                mTotalDuration = ds.getValue(User.class).getDuration();
            }
        }
    }

    @Override
    public void onServiceConnected() {
        mServiceConnected = true;
        if (getSinchServiceInterface() != null && !getSinchServiceInterface().isStarted()) {
            getSinchServiceInterface().startClient(mSinchId);
        }
        Call call = getSinchServiceInterface().getCall(mCallId);
        if (call != null) {
            call.answer();
            call.addCallListener(new SinchCallListener());
            mCallState.setText(call.getState().toString());
            if (call.getState().toString().equals("INITIATING")) {
                mCallState.setText(R.string.connecting);
            }
            mOriginalReceiver = call.getRemoteUserId();
        }
    }

    /**
     *
     * Creates a sinch call
     */
    private void createCall() {
        try {
            Call call = getSinchServiceInterface().callUser(mOriginalCaller.substring(0, 25));

            if (call == null) {
                // Service failed for some reason, show a Toast and abort
                Toast.makeText(this, "An error has occurred", Toast.LENGTH_SHORT).show();
                return;
            }

            mTimer = new Timer();
            mDurationTask = new UpdateCallDurationTask();
            mTimer.schedule(mDurationTask, 0, 500);

            mCallId = call.getCallId();
            call.addCallListener(new SinchCallListener());
            if (call.getState().toString().equals("INITIATING")) {

                mCallState.setText(R.string.connecting);
            } else {
                mCallState.setText(call.getState().toString());
            }

        } catch (MissingPermissionException e) {
            ActivityCompat.requestPermissions(this, new String[]{e.getRequiredPermission()}, 0);
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(CalligraphyContextWrapper.wrap(newBase));

    }

    @Override
    public void onPause() {
        super.onPause();


        if (mDurationTask != null) {
            mDurationTask.cancel();
            mTimer.cancel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mProximity != null) {
            if (mWakeLock.isHeld()) {

                mWakeLock.release();
            }
        }
        mSensorManager.unregisterListener(this);

    }

    @Override
    public void onResume() {
        super.onResume();

        if (mProximity != null) {
            mSensorManager.registerListener(this, mProximity, SensorManager.SENSOR_DELAY_NORMAL);
        }
   else {
            mTimer = new Timer();
            mDurationTask = new UpdateCallDurationTask();
            mTimer.schedule(mDurationTask, 0, 500);
        }
    }

    @Override
    public void onBackPressed() {
        // User should exit activity by ending call, not by going back.
    }

    private void endCall() {
        mAudioPlayer.stopProgressTone();
        Call call = getSinchServiceInterface().getCall(mCallId);
        if (call != null) {
            call.hangup();
        }
        Intent postCallIntent = new Intent(CallScreenActivity.this, FeedbackActivity.class);
        postCallIntent.putExtra(CALLERID_DATA_KEY, mOriginalCaller);
        if (mProximity != null) {
            mSensorManager.unregisterListener(this);
        }
        startActivity(postCallIntent);
        finish();
    }

    private String formatTimespan(int totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void updateCallDuration() {
        Call call = getSinchServiceInterface().getCall(mCallId);
        if (call != null) {
            mCallDuration.setText(formatTimespan(call.getDetails().getDuration()));
        }
    }

    private void updateDatabaseCallDuration(final long duration) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Looper.myLooper() == null) {
                    Looper.prepare();
                }
                mTotalDuration += duration;
                mDBRef.child("users").child(mFcmToken).child(DB_DURATION_KEY).setValue(mTotalDuration);
            }
        }).start();
    }

    AudioManager.OnAudioFocusChangeListener mFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                    || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);
            }
        }
    };

    private class SinchCallListener implements CallListener {

        @Override
        public void onCallEnded(Call call) {
            CallEndCause cause = call.getDetails().getEndCause();
            Timber.d("Call ended. Reason: " + cause.toString());
            mAudioPlayer.stopProgressTone();

            // Abandons audio focus so that any interrupted app can gain audio focus
            mAudioManager.abandonAudioFocus(mFocusChangeListener);

            String endMsg = "Call ended";
            Toast.makeText(CallScreenActivity.this, endMsg, Toast.LENGTH_LONG).show();
            long duration = call.getDetails().getDuration();

            if (mProximity != null) {
                mSensorManager.unregisterListener(CallScreenActivity.this);
            }

            updateDatabaseCallDuration(duration);

            endCall();
        }

        @Override
        public void onCallEstablished(Call call) {
            Timber.d("Call established");
            Toast.makeText(CallScreenActivity.this, "Call Connected", Toast.LENGTH_SHORT).show();
            mAudioManager.requestAudioFocus(mFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            mAudioManager.setMode(AudioManager.MODE_IN_CALL);
            mIsSpeakerPhone = false;
            mIsMicMuted = false;
            mAudioManager.setSpeakerphoneOn(mIsSpeakerPhone);
            mAudioManager.setMicrophoneMute(mIsMicMuted);
            mAudioPlayer.stopProgressTone();

            mTimer = new Timer();
            mDurationTask = new UpdateCallDurationTask();
            mTimer.schedule(mDurationTask, 0, 500);

            mCallId = call.getCallId();
            call.addCallListener(new SinchCallListener());
            if (call.getState().toString().equals("INITIATING")) {

                mCallState.setText(R.string.connecting);
            } else {
                mCallState.setText(call.getState().toString());
            }

            String callState = call.getState().toString();
            if (callState.equals("INITIATING")) {
                mCallState.setText(R.string.connecting);
            } else if (callState.equals("ESTABLISHED")) {
                mCallState.setText(R.string.connected);
            } else {
                mCallState.setText(callState);
            }

            final NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancelAll();
        }

        @Override
        public void onCallProgressing(Call call) {
            Timber.d("Call progressing");
            mAudioPlayer.playProgressTone();
        }

        @Override
        public void onShouldSendPushNotification(Call call, List<PushPair> pushPairs) {
            // Send a push through your push provider here, e.g. FCM
        }

    }
}
