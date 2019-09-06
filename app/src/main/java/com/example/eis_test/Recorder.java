package com.example.eis_test;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Recorder extends AppCompatActivity {
    private TextureView mPreview;  // For displaying the live camera preview
    private Camera mCamera;        // Object to contact the camera hardware
    private MediaRecorder mMediaRecorder;    // Store the camera's image stream as a video

    private boolean isRecording = false; // Is video being recoded?
    private Button btnRecord;            // Button that triggers recording

    //The SensorManager object manages all sensors on the hardware
    private SensorManager mSensorManager;
    private Sensor mGyro;
    private PrintStream mGyroFile;
    private long mStartTime = -1;

    private static String TAG = "GyroRecorder";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //这应该是显示一个页面的
        setContentView(R.layout.activity_main);

        //id is elements's id to find which elements you choose
        mPreview = (TextureView)findViewById(R.id.textureView1);
        btnRecord = (Button)findViewById(R.id.button1);

        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onCaptureClick(view);
            }
        });

        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mGyro = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        //fetching the gyroscope sensor and registering that this class should receive events (registerListener)
        mSensorManager.registerListener((SensorEventListener) this, mGyro, SensorManager.SENSOR_DELAY_FASTEST);
    }

    //这是按键按下的操作
    public void onCaptureClick(View view) {
        if (isRecording) {
            // Already recording? Release camera lock for others
            mMediaRecorder.stop();
            releaseMediaRecorder();
            mCamera.lock();

            isRecording = false;
            releaseCamera();
            mGyroFile.close();
            mGyroFile = null;
            btnRecord.setText("Start");
            mStartTime = -1;
        } else {
            // Not recording – launch new "thread" to initiate!
            new MediaPrepareTask().execute(null, null, null);
        }
    }

    private void releaseMediaRecorder() {
        if(mMediaRecorder != null) {
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
            mCamera.lock();
        }
    }

    private void releaseCamera() {
        if(mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }
    //The onPause method is called whenever the user switches to another app.
    // It's being a good citizen to release hardware dependencies when you're not using them.
    @Override
    protected void onPause() {
        super.onPause();

        releaseMediaRecorder();
        releaseCamera();
    }

    class MediaPrepareTask extends AsyncTask<Void, Void, Boolean> {
        //automatically creates a new thread and runs doInBackground in that thread
        @Override
        protected Boolean doInBackground(Void... voids) {
            //identifying supported image sizes from the camera, finding the suitable height,
            // setting the bitrate of the video and specifying the destination video file.
            if(prepareVideoRecorder()) {
                mMediaRecorder.start();
                isRecording = true;
            } else {
                releaseMediaRecorder();
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if(!result) {
                Recorder.this.finish();
            }

            btnRecord.setText("Stop");
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean prepareVideoRecorder() {
        // because mCamera is privated object, so other class can't used
        mCamera = Camera.open();

        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();

        // find the optimal image size for the camera
        Camera.Size optimalSize = getOptimalPreviewSize(mSupportedPreviewSizes,mPreview.getWidth(),mPreview.getHeight());
        parameters.setPreviewSize(optimalSize.width,optimalSize.height);

        //With the optimal size in hand, we can now set up the camera recorder settings
        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
        profile.videoFrameWidth = optimalSize.width;
        profile.videoFrameHeight = optimalSize.height;

        //contact the camera hardware and set up these parameters
        mCamera.setParameters(parameters);
        try {
            mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
        } catch(IOException e) {
            Log.e(TAG,"Surface texture is unavailable or unsuitable" + e.getMessage());
            return false;
        }
        //set up the media recorder
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth,profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(
                profile.videoBitRate);

        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        mMediaRecorder.setOutputFile(getOutputMediaFile().toString());
        //initialize the PrintStream
        try {
            mGyroFile = new PrintStream(getOutputGyroFile());
            mGyroFile.append("gyro\n");
        } catch(IOException e) {
            Log.d(TAG, "Unable to create acquisition file");
            return false;
        }

        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double)w / h;

        if(sizes == null) {
            return null;
        }

        Camera.Size optimalSize = null;

        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        for (Camera.Size size : sizes) {
            double ratio = (double)size.width / size.height;
            double diff = Math.abs(ratio - targetRatio);

            if(Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
                continue;

            if(Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        if(optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for(Camera.Size size : sizes) {
                if(Math.abs(size.height-targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height-targetHeight);
                }
            }
        }

        return optimalSize;
    }

    //finds the media storage location for pictures and appends a timestamp to the filename.
    private File getOutputMediaFile() {
        if(!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            return null;
        }

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Recorder");

        if(!mediaStorageDir.exists()) {
            if(!mediaStorageDir.mkdirs()) {
                Log.d("Recorder", "Failed to create directory");
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        mediaFile = new File(mediaStorageDir.getPath() + File.separator + "VID_" + timeStamp + ".mp4");

        return mediaFile;
    }

    //returns a .csv file of gyro data
    private File getOutputGyroFile() {
        if(!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            return null;
        }

        File gyroStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Recorder");

        if(!gyroStorageDir.exists()) {
            if(!gyroStorageDir.mkdirs()) {
                Log.d("Recorder", "Failed to create directory");
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File gyroFile;
        gyroFile = new File(gyroStorageDir.getPath() + File.separator + "VID_" + timeStamp + "gyro.csv");

        return gyroFile;
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Empty on purpose
        // Required because we implement SensorEventListener
    }

    public void onSensorChanged(SensorEvent sensorEvent) {
        if(isRecording) {
            if(mStartTime == -1) {
                mStartTime = sensorEvent.timestamp;
            }
            mGyroFile.append(sensorEvent.values[0] + "," +
                    sensorEvent.values[1] + "," +
                    sensorEvent.values[2] + "," +
                    (sensorEvent.timestamp-mStartTime) + "\n");
        }
    }
}

