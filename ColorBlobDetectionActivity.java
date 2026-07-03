package org.opencv.samples.colorblobdetect;

import static java.lang.Math.atan;
import static java.lang.Math.tan;

import java.util.Collections;
import java.util.List;

import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.imgproc.Imgproc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnTouchListener;
import android.view.SurfaceView;
import android.widget.Toast;

import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point3;

import android.os.AsyncTask;
import java.net.URI;
import java.net.URISyntaxException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.util.concurrent.TimeUnit;

// Camera2 API specific imports
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;

public class ColorBlobDetectionActivity extends CameraActivity implements OnTouchListener, CvCameraViewListener2 {
    private static final String  TAG              = "OCVSample::Activity";

    private boolean              mIsColorSelected = false;
    private Mat                  mRgba;
    private Scalar               mBlobColorRgba;
    private Scalar               mBlobColorHsv;
    private ColorBlobDetector    mDetector;
    private Mat                  mSpectrum;
    private Size                 SPECTRUM_SIZE;
    private Scalar               CONTOUR_COLOR;

    private WebSocketClient webSocketClient;
    private long lastSendTime = 0;
    private static final long SEND_INTERVAL_MS = 10; // Send every 10ms (100Hz)

    // Switched to JavaCamera2View to enable low-level sensor settings access
    private org.opencv.android.JavaCamera2View mOpenCvCameraView;

    private static final int camera = 1; // change this to switch between camera versions

    // User tweakable manual camera variables
    private int mExposureTimeDenominator = 500; // Default to 1/500s exposure speed to lock motion
    private int mIsoValue = 800;                 // Higher ISO compensates for dark frames under fast exposure
    private boolean mExposureSettingsApplied = false;

    public ColorBlobDetectionActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed!");
            (Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG)).show();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.color_blob_detection_surface_view);

        // Bind view and ensure your activity layout XML uses org.opencv.android.JavaCamera2View
        mOpenCvCameraView = (org.opencv.android.JavaCamera2View) findViewById(R.id.color_blob_detection_activity_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        disconnectWebSocket();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.enableView();
            mOpenCvCameraView.setOnTouchListener(ColorBlobDetectionActivity.this);
        }
        connectWebSocket();
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        disconnectWebSocket();
    }

    @SuppressLint("StaticFieldLeak")
    private void connectWebSocket() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    URI serverUri = new URI("ws://10.79.201.117:8765"); // Change to your server IP
                    webSocketClient = new WebSocketClient(serverUri) {
                        @Override
                        public void onOpen(ServerHandshake handshakedata) {
                            Log.i(TAG, "WebSocket connection opened");
                        }

                        @Override
                        public void onMessage(String message) {
                            Log.i(TAG, "Received WebSocket message: " + message);
                        }

                        @Override
                        public void onClose(int code, String reason, boolean remote) {
                            Log.i(TAG, "WebSocket connection closed: " + reason);
                        }

                        @Override
                        public void onError(Exception ex) {
                            Log.e(TAG, "WebSocket error: " + ex.getMessage());
                        }
                    };

                    webSocketClient.connect();

                    int timeoutMs = 5000;
                    long startTime = System.currentTimeMillis();
                    while (!webSocketClient.isOpen() &&
                            (System.currentTimeMillis() - startTime) < timeoutMs) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (webSocketClient.isOpen()) {
                        Log.i(TAG, "WebSocket connected successfully");
                    } else {
                        Log.e(TAG, "WebSocket connection failed or timed out");
                    }

                } catch (URISyntaxException e) {
                    Log.e(TAG, "Invalid WebSocket URI: " + e.getMessage());
                }
                return null;
            }
        }.execute();
    }

    private void disconnectWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
        }
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mDetector = new ColorBlobDetector();
        mSpectrum = new Mat();
        mBlobColorRgba = new Scalar(255);
        mBlobColorHsv = new Scalar(255);
        SPECTRUM_SIZE = new Size(200, 64);
        CONTOUR_COLOR = new Scalar(255,0,0,255);
        mExposureSettingsApplied = false; // Reset settings state to hook new session
    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    /**
     * Intercepts underlying JavaCamera2View variables via reflection to configure manual sensor specs.
     */
    private void applyManualCameraSettings() {
        try {
            CameraCaptureSession captureSession = null;
            CaptureRequest.Builder builder = null;

            // FIX: Search the JavaCamera2View class directly, NOT the superclass
            java.lang.reflect.Field[] fields = mOpenCvCameraView.getClass().getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                field.setAccessible(true);
                if (field.getType() == CameraCaptureSession.class) {
                    captureSession = (CameraCaptureSession) field.get(mOpenCvCameraView);
                } else if (field.getType() == CaptureRequest.Builder.class) {
                    builder = (CaptureRequest.Builder) field.get(mOpenCvCameraView);
                }
            }

            // Session might take a few frames to initialize asynchronously
            if (captureSession != null && builder != null) {

                // 1. Turn off internal Auto-Exposure
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

                // 2. Depending on the device manufacturer (like Google Pixel), you may also need to
                // disable global auto-controls for the manual sensor values to be respected.
                // Uncomment the line below if it still auto-adjusts after fixing the reflection:
                // builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);

                // 3. Target exposure time calculation (1 second = 1,000,000,000 nanoseconds)
                long exposureTimeNs = 1000000000L / mExposureTimeDenominator;
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs);

                // 4. Target Sensitivity
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, mIsoValue);

                // 5. Push configuration updates directly back into active repeating preview thread
                captureSession.setRepeatingRequest(builder.build(), null, null);
                mExposureSettingsApplied = true;
                Log.i(TAG, "Camera2 Overrides Engaged -> Exposure: 1/" + mExposureTimeDenominator + "s | ISO: " + mIsoValue);
            } else {
                Log.e(TAG, "Reflection failed: Could not find CameraCaptureSession or Builder in JavaCamera2View.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Reflection tracking configuration update failure: " + e.getMessage());
        }
    }

    public boolean onTouch(View v, MotionEvent event) {
        float screenX = event.getX();
        float screenY = event.getY();
        float screenWidth = mOpenCvCameraView.getWidth();
        float screenHeight = mOpenCvCameraView.getHeight();

        // Top 150-pixel row of the device acts as an interactive configuration utility dashboard
        if (screenY < 150) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (screenX < screenWidth * 0.25) {
                    mExposureTimeDenominator = Math.max(30, mExposureTimeDenominator - 50); // Elongates exposure
                } else if (screenX < screenWidth * 0.5) {
                    mExposureTimeDenominator = Math.min(4000, mExposureTimeDenominator + 50); // Quickens exposure (eliminates blur)
                } else if (screenX < screenWidth * 0.75) {
                    mIsoValue = Math.max(100, mIsoValue - 50); // Lower gain (less noise)
                } else {
                    mIsoValue = Math.min(3200, mIsoValue + 50); // Higher gain (brighter frame)
                }

                mExposureSettingsApplied = false; // Mark configuration dirty to enforce reload on next frame loop

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ColorBlobDetectionActivity.this,
                                "Tuned -> Exposure: 1/" + mExposureTimeDenominator + "s | ISO: " + mIsoValue,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return true;
        }

        // Standard touch color parsing logic sequence remains unchanged
        int cols = mRgba.cols();
        int rows = mRgba.rows();

        int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
        int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

        int x = (int)event.getX() - xOffset;
        int y = (int)event.getY() - yOffset;

        Log.i(TAG, "Touch image coordinates: (" + x + ", " + y + ")");

        if ((x < 0) || (y < 0) || (x > cols) || (y > rows)) return false;

        Rect touchedRect = new Rect();

        touchedRect.x = (x>4) ? x-4 : 0;
        touchedRect.y = (y>4) ? y-4 : 0;

        touchedRect.width = (x+4 < cols) ? x + 4 - touchedRect.x : cols - touchedRect.x;
        touchedRect.height = (y+4 < rows) ? y + 4 - touchedRect.y : rows - touchedRect.y;

        Mat touchedRegionRgba = mRgba.submat(touchedRect);

        Mat touchedRegionHsv = new Mat();
        Imgproc.cvtColor(touchedRegionRgba, touchedRegionHsv, Imgproc.COLOR_RGB2HSV_FULL);

        mBlobColorHsv = Core.sumElems(touchedRegionHsv);
        int pointCount = touchedRect.width*touchedRect.height;
        for (int i = 0; i < mBlobColorHsv.val.length; i++)
            mBlobColorHsv.val[i] /= pointCount;

        mBlobColorRgba = convertScalarHsv2Rgba(mBlobColorHsv);

        Log.i(TAG, "Touched rgba color: (" + mBlobColorRgba.val[0] + ", " + mBlobColorRgba.val[1] +
                ", " + mBlobColorRgba.val[2] + ", " + mBlobColorRgba.val[3] + ")");

        mDetector.setHsvColor(mBlobColorHsv);
        Imgproc.resize(mDetector.getSpectrum(), mSpectrum, SPECTRUM_SIZE, 0, 0, Imgproc.INTER_LINEAR_EXACT);
        mIsColorSelected = true;

        touchedRegionRgba.release();
        touchedRegionHsv.release();

        return false;
    }

    private void sendBallData(double nx, double ny, int detected) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSendTime < SEND_INTERVAL_MS) {
            return;
        }

        lastSendTime = currentTime;

        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                String jsonData = String.format(
                        "{\"ballAxes\": {\"nx\": %.4f, \"ny\": %.4f}, " +
                                "\"timestamp\": %d, " +
                                "\"detected\": %d, " +
                                "\"source\": %d}",
                        nx, ny, currentTime, detected, camera
                );

                new AsyncTask<String, Void, Void>() {
                    @Override
                    protected Void doInBackground(String... data) {
                        webSocketClient.send(data[0]);
                        return null;
                    }
                }.execute(jsonData);

            } catch (Exception e) {
                Log.e(TAG, "Error sending WebSocket data: " + e.getMessage());
            }
        }
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // Intercept frame pipeline loop to inject hardware overrides if configurations were altered
        if (!mExposureSettingsApplied) {
            applyManualCameraSettings();
        }

        mRgba = inputFrame.rgba();

        double viewWidth = mRgba.cols();
        double viewHeight = mRgba.rows();

        double[] ss = new double[] {-1.57, 0};
        double[] us = new double[] {0.27, 0.237};
        double s = ss[camera-1];
        double u = us[camera-1];

        Point normalised = new Point(0,0);
        int detected = 0;

        Mat mask = new Mat();

        if (mIsColorSelected) {
            mDetector.process(mRgba);
            List<MatOfPoint> contours = mDetector.getContours();
            mask = mDetector.getMask();
            Scalar maskCount = Core.sumElems(mask);
            double maskSum = maskCount.val[0]/255;

            for (MatOfPoint contour : contours) {
                Point[] points = contour.toArray();

                if (points.length > 0) {
                    detected = 1;
                    MatOfPoint2f contour2f = new MatOfPoint2f(points);
                    Point center = new Point();
                    float[] radius = new float[1];
                    Imgproc.minEnclosingCircle(contour2f, center, radius);

                    Imgproc.circle(mRgba, center, 5, new Scalar(0, 255, 0, 255), -1);
                    Imgproc.circle(mRgba, center, (int)radius[0], new Scalar(0, 255, 0, 255), 2);

                    Point centered = new Point();
                    centered.x = center.x - viewWidth /2;
                    centered.y = -(center.y - viewHeight /2);

                    double[] sensorHorViewAngle = new double[] {68.2, 65.3};
                    double xbcv = tan(0.5*sensorHorViewAngle[camera-1]*3.1416/180);
                    double ybcv = xbcv/(viewWidth/viewHeight);

                    normalised.x = (2*xbcv*centered.x)/viewWidth;
                    normalised.y = (2*ybcv*centered.y)/viewHeight;

                    String axesTextB = String.format("Ball view vector local normalised coords: (%.2f, %.2f)",
                            normalised.x, normalised.y);
                    Imgproc.putText(mRgba, axesTextB, new Point(50, 200),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 255, 255, 255), 2);

                    String axesTextC = String.format("Screensize: (%.2f, %.2f)",
                            viewWidth, viewHeight);
                    Imgproc.putText(mRgba, axesTextC, new Point(50, 150),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 255, 255, 255), 2);
                }
            }

            Mat colorLabel = mRgba.submat(4, 68, 4, 68);
            colorLabel.setTo(mBlobColorRgba);

            Mat spectrumLabel = mRgba.submat(4, 4 + mSpectrum.rows(), 70, 70 + mSpectrum.cols());
            mSpectrum.copyTo(spectrumLabel);

            String axesTextD = String.format("maskSum: %.2f",
                    maskSum);
            Imgproc.putText(mRgba, axesTextD, new Point(50, 250),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 255, 255, 255), 2);
        }

        sendBallData(normalised.x, normalised.y, detected);

        // HUD overlay displaying real-time manual control status parameters
        String camera2SettingsHud = String.format("MANUAL CAMERA2 [Tap top to tune] -> Exp: 1/%ds | ISO: %d",
                mExposureTimeDenominator, mIsoValue);
        Imgproc.putText(mRgba, camera2SettingsHud, new Point(50, 100),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(0, 165, 255, 255), 2);

        Imgproc.circle(mRgba, new Point(viewWidth / 2, viewHeight / 2), 10, new Scalar(0, 255, 0, 255), 2);
        Imgproc.circle(mRgba, new Point(viewWidth / 2, viewHeight / 2), 5, new Scalar(0, 255, 0, 255), -1);
        Imgproc.line(mRgba,new Point(viewWidth/2,viewHeight/2),new Point(viewWidth/2,viewHeight),new Scalar(0, 255, 0, 255), 1);

        return mRgba;
    }

    private Scalar convertScalarHsv2Rgba(Scalar hsvColor) {
        Mat pointMatRgba = new Mat();
        Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
        Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL, 4);
        return new Scalar(pointMatRgba.get(0, 0));
    }
}
