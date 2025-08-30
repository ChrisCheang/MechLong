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
    private static final long SEND_INTERVAL_MS = 50; // Send every 50ms (20Hz)

    private CameraBridgeViewBase mOpenCvCameraView;

    private static final int camera = 2; // change this to switch between camera versions

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

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.color_blob_detection_activity_surface_view);
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

    @SuppressLint("StaticFieldLeak") // given way to suppress memory leak issue (could lead to further issues?)
    private void connectWebSocket() {
        // Run WebSocket connection in background thread
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    URI serverUri = new URI("ws://192.168.0.237:8765"); // Change to your server IP
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

                    // Set connection timeout
                    webSocketClient.connect();

                    // Wait for connection with timeout
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
    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    public boolean onTouch(View v, MotionEvent event) {
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

        // Calculate average color of touched region
        mBlobColorHsv = Core.sumElems(touchedRegionHsv);
        int pointCount = touchedRect.width*touchedRect.height;
        for (int i = 0; i < mBlobColorHsv.val.length; i++)
            mBlobColorHsv.val[i] /= pointCount;

        mBlobColorRgba = convertScalarHsv2Rgba(mBlobColorHsv);

        Log.i(TAG, "Touched rgba color: (" + mBlobColorRgba.val[0] + ", " + mBlobColorRgba.val[1] +
                ", " + mBlobColorRgba.val[2] + ", " + mBlobColorRgba.val[3] + ")");

        Log.i(TAG, "" + mBlobColorHsv);

        mDetector.setHsvColor(mBlobColorHsv);

        Imgproc.resize(mDetector.getSpectrum(), mSpectrum, SPECTRUM_SIZE, 0, 0, Imgproc.INTER_LINEAR_EXACT);

        mIsColorSelected = true;

        touchedRegionRgba.release();
        touchedRegionHsv.release();

        return false; // don't need subsequent touch events
    }

    private void sendBallData(double nx, double ny, int detected) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSendTime < SEND_INTERVAL_MS) {
            return; // Throttle sending to avoid overloading
        }

        lastSendTime = currentTime;


        if (webSocketClient != null && webSocketClient.isOpen()) {
            try {
                // Create JSON data
                String jsonData = String.format(
                        "{\"ballAxes\": {\"nx\": %.4f, \"ny\": %.4f}, " +
                                "\"timestamp\": %d, " +
                                "\"detected\": %d, " +
                                "\"source\": %d}",
                        nx, ny, currentTime, detected, camera
                );

                // Send in background thread to avoid blocking camera frame processing
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
        mRgba = inputFrame.rgba();


        double viewWidth = mRgba.cols();
        double viewHeight = mRgba.rows();


        // Testing camera location - origin is left corner of table
        // First try using desmos projection representation of camera view
        // Values can later be informed by either checkerboard calibration, table detection or kept hardcoded for rigid mounting

        // view rotation angles based on https://www.desmos.com/calculator/efc34da5b9?lang=zh-TW convention
        double[] ss = new double[] {-1.57, 0};
        double[] us = new double[] {0.27, 0.237};
        double s = ss[camera-1]; // actual table camera 1: -0.391, camera 2: 0.391
        double u = us[camera-1]; // actual table camera 1: 0.177, camera 2: 0.177

        Point normalised = new Point(0,0);
        int detected = 0;

        if (mIsColorSelected) {
            mDetector.process(mRgba);
            List<MatOfPoint> contours = mDetector.getContours();
            //Log.i(TAG, "Contours count: " + contours.size());

            for (MatOfPoint contour : contours) {
                Point[] points = contour.toArray();


                if (points.length > 0) {
                    detected = 1;
                    MatOfPoint2f contour2f = new MatOfPoint2f(points);
                    // Calculate minimum enclosing circle
                    Point center = new Point();
                    float[] radius = new float[1];
                    Imgproc.minEnclosingCircle(contour2f, center, radius);

                    // Draw the center point and enclosing circle
                    Imgproc.circle(mRgba, center, 5, new Scalar(0, 255, 0, 255), -1); // Green filled circle
                    Imgproc.circle(mRgba, center, (int)radius[0], new Scalar(0, 255, 0, 255), 2); // Green circle outlin

                    // Center coordinates with offset:
                    Point centered = new Point();

                    centered.x = center.x - viewWidth /2;
                    centered.y = -(center.y - viewHeight /2);
                    //Log.i(TAG, "Offset center: (" + centered.x + ", " + centered.y + ")");

                    // xy normalisation - check
                    double[] sensorHorViewAngle = new double[] {68.2, 65.3}; // sensor stats: 71.6, 68.3, these are empirical to remove the need for correction factors
                    double xbcv = tan(0.5*sensorHorViewAngle[camera-1]*3.1416/180);
                    double ybcv = xbcv/(viewWidth/viewHeight); // 1.787 is screen aspect ratio

                    normalised.x = (2*xbcv*centered.x)/viewWidth;
                    normalised.y = (2*ybcv*centered.y)/viewHeight;
                    //Log.i(TAG, "Normalised ball center: (" + normalised.x + ", " + normalised.y + ")");

                    // Update local ball view roll and pitch
                    //Log.i(TAG, "thetaHor: " + thetaHor + ", thetaVer: " + thetaVer);


                    // Draw ball axes information on screen for debugging
                    String axesTextB = String.format("Ball view vector local normalised coords: (%.2f, %.2f)",
                            normalised.x, normalised.y);
                    Imgproc.putText(mRgba, axesTextB, new Point(50, 200),
                            Imgproc.FONT_HERSHEY_SIMPLEX, 0.7, new Scalar(255, 255, 255, 255), 2);

                    // Draw ball axes information on screen for debugging
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

        }

        sendBallData(normalised.x, normalised.y, detected);

        // View placement assists
        Imgproc.circle(mRgba, new Point(viewWidth / 2, viewHeight / 2), (int) 10, new Scalar(0, 255, 0, 255), 2);
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
