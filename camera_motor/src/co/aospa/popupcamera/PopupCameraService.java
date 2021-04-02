/*
 * Copyright (C) 2019 The LineageOS Project
 *               2019 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.aospa.popupcamera;

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.res.Resources;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;

import java.util.List;

import co.aospa.popupcamera.R;
import co.aospa.popupcamera.utils.FileUtils;
import co.aospa.popupcamera.utils.LimitSizeList;

import vendor.xiaomi.hardware.motor.V1_0.IMotor;
import vendor.xiaomi.hardware.motor.V1_0.IMotorCallback;
import vendor.xiaomi.hardware.motor.V1_0.MotorEvent;

public class PopupCameraService extends Service implements Handler.Callback {

    private static final String TAG = "PopupCameraService";
    private static final boolean DEBUG = true;
    private static final String closeCameraState = "0";
    private static final String openCameraState = "1";
    private static String mCameraState = "-1";
    public static final String FRONT_CAMERA_ID = "1";

    public static final int CAMERA_EVENT_DELAY_TIME = 100; //ms
    public static final int MSG_CAMERA_CLOSED = 1001;
    public static final int MSG_CAMERA_OPEN = 1002;
    public static final int MSG_CAMERA_ERROR = 1003;

    public static final int MOTOR_STATUS_POPUP = 11;
    public static final int MOTOR_STATUS_POPUP_JAM = 12;
    public static final int MOTOR_STATUS_TAKEBACK = 13;
    public static final int MOTOR_STATUS_TAKEBACK_JAM = 14;

    public static final String CLOSE_CAMERA_STATE = "0";
    public static final String OPEN_CAMERA_STATE = "1";

    private IMotor mMotor = null;
    private IMotorCallback mMotorStatusCallback;
    private final Object mLock = new Object();
    private boolean mMotorBusy = false;
    private boolean mMotorCalibrating = false;
    private long mClosedEvent;
    private long mOpenEvent;

    private SensorManager mSensorManager;
    private Sensor mFreeFallSensor;
    private static final int FREE_FALL_SENSOR_ID = 33171042;

    private static final String GREEN_LED_PATH = "/sys/class/leds/green/brightness";
    private static final String BLUE_LED_PATH = "/sys/class/leds/blue/brightness";

    private Handler mHandler = new Handler(this);

    private CameraManager.AvailabilityCallback availabilityCallback =
            new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(@NonNull String cameraId) {
                    super.onCameraAvailable(cameraId);
                    if (cameraId.equals(FRONT_CAMERA_ID)) {
                        mClosedEvent = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - mOpenEvent
                                < CAMERA_EVENT_DELAY_TIME && mHandler.hasMessages(
                                MSG_CAMERA_OPEN)) {
                            mHandler.removeMessages(MSG_CAMERA_OPEN);
                        }
                        mHandler.sendEmptyMessageDelayed(MSG_CAMERA_CLOSED,
                                CAMERA_EVENT_DELAY_TIME);
                    }
                }

                @Override
                public void onCameraUnavailable(@NonNull String cameraId) {
                    super.onCameraAvailable(cameraId);
                    if (cameraId.equals(FRONT_CAMERA_ID)) {
                        mOpenEvent = SystemClock.elapsedRealtime();
                        if (SystemClock.elapsedRealtime() - mClosedEvent
                                < CAMERA_EVENT_DELAY_TIME && mHandler.hasMessages(
                                MSG_CAMERA_CLOSED)) {
                            mHandler.removeMessages(MSG_CAMERA_CLOSED);
                        }
                        mHandler.sendEmptyMessageDelayed(MSG_CAMERA_OPEN,
                                CAMERA_EVENT_DELAY_TIME);
                    }
                }
            };

    // Motor status
    private static final int MOTOR_STATUS_POPUP_OK = 11;
    private static final int MOTOR_STATUS_POPUP_JAMMED = 12;
    private static final int MOTOR_STATUS_TAKEBACK_OK = 13;
    private static final int MOTOR_STATUS_TAKEBACK_JAMMED = 14;
    private static final int MOTOR_STATUS_PRESSED = 15;
    private static final int MOTOR_STATUS_CALIB_OK = 17;
    private static final int MOTOR_STATUS_CALIB_ERROR = 18;
    private static final int MOTOR_STATUS_REQUEST_CALIB = 19;

    // Error dialog
    private boolean mDialogShowing;
    private int mPopupFailedRecord = 0;
    private int mTakebackFailedRecord = 0;
    private static final int POPUP_FAILED_MAX_TRIES = 3;
    private static final int TAKEBACK_FAILED_MAX_TRIES = 3;

    // Frequent dialog
    private static final int FREQUENT_TRIGGER_COUNT = SystemProperties.getInt("persist.sys.popup.frequent_times", 10);
    private LimitSizeList<Long> mPopupRecordList;

    // Proximity sensor
    private ProximitySensor mProximitySensor;
    private boolean mProximityNear;
    private boolean mShouldTryUpdateMotor;

    @Override
    public void onCreate() {
        CameraManager cameraManager = getSystemService(CameraManager.class);
        cameraManager.registerAvailabilityCallback(availabilityCallback, null);
        mSensorManager = getSystemService(SensorManager.class);
        mFreeFallSensor = mSensorManager.getDefaultSensor(FREE_FALL_SENSOR_ID);
        mProximitySensor = new ProximitySensor(this, mSensorManager, mProximityListener);
        mPopupRecordList = new LimitSizeList<>(FREQUENT_TRIGGER_COUNT);
        try {
            mMotor = IMotor.getService();
            mMotorStatusCallback = new MotorStatusCallback();
            mMotor.setMotorCallback(mMotorStatusCallback);
            int status = mMotor.getMotorStatus();
            if (status == MOTOR_STATUS_POPUP || status == MOTOR_STATUS_POPUP_JAM
                    || status == MOTOR_STATUS_TAKEBACK_JAM) {
                mHandler.sendEmptyMessage(MSG_CAMERA_CLOSED);
            }
        } catch(RemoteException e) {
        }
    }

    private void setProximitySensor(boolean enabled) {
        if (mProximitySensor == null) return;
        if (enabled) {
            if (DEBUG) Log.d(TAG, "Proximity sensor enabling");
            mProximitySensor.enable();
        } else {
            if (DEBUG) Log.d(TAG, "Proximity sensor disabling");
            mProximitySensor.disable();
        }
    }

    private ProximitySensor.ProximityListener mProximityListener =
            new ProximitySensor.ProximityListener() {
        public void onEvent(boolean isNear, long timestamp) {
            mProximityNear = isNear;
            if (DEBUG) Log.d(TAG, "Proximity sensor: isNear " + mProximityNear);
            if (!mProximityNear && mShouldTryUpdateMotor){
                if (DEBUG) Log.d(TAG, "Proximity sensor: mShouldTryUpdateMotor " + mShouldTryUpdateMotor);
                mShouldTryUpdateMotor = false;
                updateMotor(openCameraState);
            }
        }
        public void onInit(boolean isNear, long timestamp) {
            if (DEBUG) Log.d(TAG, "Proximity sensor init : " + isNear);
            mProximityNear = isNear;
        }
    };

    private void checkFrequentOperate() {
        mPopupRecordList.add(Long.valueOf(SystemClock.elapsedRealtime()));
        if (mPopupRecordList.isFull() && ((Long) mPopupRecordList.getLast()).longValue() - ((Long) mPopupRecordList.getFirst()).longValue() < 20000) {
            showFrequentOperateDialog();
        }
    }

    private void showFrequentOperateDialog(){
        if (mDialogShowing){
            return;
        }
        mDialogShowing = true;
        mHandler.post(() -> {
            Resources res = getResources();
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.SystemAlertDialogTheme)
                    .setTitle(res.getString(R.string.popup_camera_tip))
                    .setMessage(res.getString(R.string.stop_operate_camera_frequently))
                    .setPositiveButton(res.getString(android.R.string.ok) + " (5)", null);
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            alertDialog.setCancelable(false);
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    mDialogShowing = false;
                }
            });
            final Button btn = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            btn.setEnabled(false);
            CountDownTimer countDownTimer = new CountDownTimer(6000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    btn.setText(res.getString(android.R.string.ok) + " (" + Long.valueOf(millisUntilFinished / 1000) + ")");
                }

                @Override
                public void onFinish() {
                    btn.setEnabled(true);
                    btn.setText(res.getString(android.R.string.ok));
                }
            };
            countDownTimer.start();
        });
    }

    private final class MotorStatusCallback extends IMotorCallback.Stub {
        public MotorStatusCallback() {
        }

        @Override
        public void onNotify(MotorEvent event) {
            int status = event.vaalue;
            int cookie = event.cookie;
            if (DEBUG) Log.d(TAG, "onNotify: cookie=" + cookie + ",status=" + status);
            synchronized (mLock) {
                if (status == MOTOR_STATUS_CALIB_OK || status == MOTOR_STATUS_CALIB_ERROR) {
                    mMotorCalibrating = false;
                    showCalibrationResult(status);
                }else if (status == MOTOR_STATUS_PRESSED) {
                    forceTakeback();
                    goBackHome();
                }else if (status == MOTOR_STATUS_POPUP_JAMMED || status == MOTOR_STATUS_TAKEBACK_JAMMED) {
                    mHandler.sendEmptyMessage(MSG_CAMERA_ERROR);
                }
            }
        }
    }

    private void calibrateMotor() {
        synchronized (mLock) {
            if (mMotorCalibrating || mMotor == null) return;
            try {
                mMotorCalibrating = true;
                mMotor.calibration();
            } catch (Exception e) {
            }
        }
    }

    private void forceTakeback(){
        updateMotor(closeCameraState);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Starting service");
        setProximitySensor(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service");
        setProximitySensor(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateMotor(String cameraState) {
        mCameraState = cameraState;
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                if (mMotor == null) return;
                mMotorBusy = true;
                try {
                    int status = mMotor.getMotorStatus();
                    if (DEBUG) Log.d(TAG, "updateMotor: status=" + status);
                    if (mMotorCalibrating){
                        mMotorBusy = false;
                        goBackHome();
                        showCalibrationResult(-1);
                        return;
                    }else if (cameraState.equals(openCameraState) && (status == MOTOR_STATUS_TAKEBACK_OK || status == MOTOR_STATUS_CALIB_OK)) {
                        mTakebackFailedRecord = 0;
                        if (!mProximityNear){
                            lightUp();
                            mMotor.popupMotor(1);
                            mSensorManager.registerListener(mFreeFallListener, mFreeFallSensor, SensorManager.SENSOR_DELAY_NORMAL);
                            checkFrequentOperate();
                        }else{
                            mShouldTryUpdateMotor = true;
                        }
                    } else if (cameraState.equals(closeCameraState) && (status == MOTOR_STATUS_POPUP_OK || status == MOTOR_STATUS_CALIB_OK)) {
                        mPopupFailedRecord = 0;
                        lightUp();
                        mMotor.takebackMotor(1);
                        mSensorManager.unregisterListener(mFreeFallListener, mFreeFallSensor);
                        checkFrequentOperate();
                    }else{
                        mMotorBusy = false;
                        if (status == MOTOR_STATUS_REQUEST_CALIB || status == MOTOR_STATUS_POPUP_JAMMED || status == MOTOR_STATUS_TAKEBACK_JAMMED || status == MOTOR_STATUS_CALIB_ERROR){
                            mHandler.sendEmptyMessage(MSG_CAMERA_ERROR);
                        }
                        return;
                    }
                } catch(RemoteException e) {
                }
                mHandler.postDelayed(() -> { mMotorBusy = false; }, 1200);
            }
        };
        if (mMotorBusy){
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mMotorBusy){
                        mHandler.postDelayed(this, 100);
                    }else{
                        mHandler.post(r);
                    }
                }
            }, 100);
        }else{
            mHandler.post(r);
        }
    }

    private void showCalibrationResult(int status){
        if (mDialogShowing){
            return;
        }
        mDialogShowing = true;
        mHandler.post(() -> {
            Resources res = getResources();
            int dialogMessageResId = mMotorCalibrating ? R.string.popup_camera_calibrate_running : (status == MOTOR_STATUS_CALIB_OK ?
                    R.string.popup_camera_calibrate_success :
                    R.string.popup_camera_calibrate_failed);
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.SystemAlertDialogTheme);
            alertDialogBuilder.setMessage(res.getString(dialogMessageResId));
            alertDialogBuilder.setPositiveButton(android.R.string.ok, null);
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            alertDialog.setCancelable(false);
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    mDialogShowing = false;
                }
            });
        });
    }

    private void handleError(){
        if (mDialogShowing){
            return;
        }
        mDialogShowing = true;
        goBackHome();
        mHandler.post(() -> {
            int status = -1;
            try {
                status = mMotor.getMotorStatus();
            } catch(RemoteException e) {

            }
            boolean needsCalib = false;
            if (status == MOTOR_STATUS_REQUEST_CALIB || status == MOTOR_STATUS_CALIB_ERROR){
                needsCalib = true;
            }else if (status == MOTOR_STATUS_POPUP_JAMMED){
                if (mPopupFailedRecord >= POPUP_FAILED_MAX_TRIES){
                    needsCalib = true;
                }else{
                    mPopupFailedRecord++;
                }
            }else if (status == MOTOR_STATUS_TAKEBACK_JAMMED){
                if (mTakebackFailedRecord >= TAKEBACK_FAILED_MAX_TRIES){
                    needsCalib = true;
                }else{
                    mTakebackFailedRecord++;
                    try {
                        mMotor.takebackMotor(1);
                    } catch(RemoteException e) {
                    }
                }
            }
            Resources res = getResources();
            int dialogMessageResId = needsCalib ? (mCameraState.equals(closeCameraState) ?
                R.string.popup_camera_takeback_falied_times_calibrate :
                R.string.popup_camera_popup_falied_times_calibrate) :
                    (mCameraState.equals(closeCameraState) ?
                        R.string.takeback_camera_front_failed :
                        R.string.popup_camera_front_failed);
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this, R.style.SystemAlertDialogTheme)
                    .setTitle(res.getString(R.string.popup_camera_tip));
            alertDialogBuilder.setMessage(res.getString(dialogMessageResId));
            if (needsCalib){
                alertDialogBuilder.setPositiveButton(res.getString(R.string.popup_camera_calibrate_now),
                        (dialog, which) -> {
                        calibrateMotor();
                });
                alertDialogBuilder.setNegativeButton(res.getString(android.R.string.cancel), null);
            }else{
                alertDialogBuilder.setPositiveButton(android.R.string.ok, null);
            }
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            alertDialog.setCancelable(false);
            alertDialog.setCanceledOnTouchOutside(false);
            alertDialog.show();
            alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    mDialogShowing = false;
                }
            });
        });
    }

    private void lightUp() {
        FileUtils.writeLine(GREEN_LED_PATH, "255");
        FileUtils.writeLine(BLUE_LED_PATH, "255");

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                FileUtils.writeLine(GREEN_LED_PATH, "0");
                FileUtils.writeLine(BLUE_LED_PATH, "0");
            }
        }, 1000);
    }

    private SensorEventListener mFreeFallListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == FREE_FALL_SENSOR_ID && event.values[0] == 2.0f) {
                forceTakeback();
                goBackHome();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    public void goBackHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_CAMERA_CLOSED: {
                updateMotor(CLOSE_CAMERA_STATE);
            }
            break;
            case MSG_CAMERA_OPEN: {
                updateMotor(OPEN_CAMERA_STATE);
            }
            break;
            case MSG_CAMERA_ERROR: {
                handleError();
            }
            break;
        }
        return true;
    }
}
