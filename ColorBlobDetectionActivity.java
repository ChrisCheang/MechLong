package org.opencv.samples.colorblobdetect;

import static java.lang.Math.atan;
import static java.lang.Math.tan;

import java.util.Collections;
import java.util.List;
import java.util.Set;

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

import android.Manifest;
import android.annotation.SuppressLint;
// the above suppresses missing bluetooth permission
import android.annotation.TargetApi;
//this was added as a recommendation to get bluetoothmanager getsystemservice, could affect usability over older android versions
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
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

//@TargetApi(Build.VERSION_CODES.M)
public class ColorBlobDetectionActivity extends CameraActivity implements OnTouchListener, CvCameraViewListener2 {
    private static final String TAG = "OCVSample::Activity";

    private boolean mIsColorSelected = false;
    private Mat mRgba;
    private Scalar mBlobColorRgba;
    private Scalar mBlobColorHsv;
    private ColorBlobDetector mDetector;
    private Mat mSpectrum;
    private Size SPECTRUM_SIZE;
    private Scalar CONTOUR_COLOR;

    private static final int REQUEST_CODE_BLUETOOTH_CONNECT = 1;

    private CameraBridgeViewBase mOpenCvCameraView;

    // Bluetooth permissions
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private static final String[] BLUETOOTH_PERMS = {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
    };

    private BluetoothAdapter mBluetoothAdapter;
    private boolean mBluetoothInitialized = false;
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_DISCOVERABLE = 2;


    public ColorBlobDetectionActivity() {
        Log.i(TAG, "Instantiated new " + this.getClass());
    }

    /** Called when the activity is first created. */
    @SuppressLint("MissingPermission")
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

        checkBluetoothPermissions();

        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        mBluetoothAdapter.startDiscovery();
    }

    // Create a BroadcastReceiver for ACTION_FOUND
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device. Get the BluetoothDevice
                // object and its info from the Intent.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    Activity#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for Activity#requestPermissions for more details.
                        return;
                    }
                }
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress(); // MAC address
            }
        }
    };

    private void checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Check if we have all required permissions for Android 12+
            boolean allPermissionsGranted = true;
            for (String permission : BLUETOOTH_PERMS) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (!allPermissionsGranted) {
                // Request the missing permissions
                requestPermissions(BLUETOOTH_PERMS, REQUEST_BLUETOOTH_PERMISSIONS);
            } else {
                // Permissions already granted, initialize Bluetooth
                initializeBluetooth();
            }
        } else {
            // For older versions, just initialize Bluetooth
            initializeBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                initializeBluetooth();
            } else {
                Log.e(TAG, "Bluetooth permissions denied");
                Toast.makeText(this, "Bluetooth permissions are required for this app", Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void initializeBluetooth() {
        // Only proceed if we have permissions (for Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnectPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!hasConnectPermission) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                return;
            }
        }

        BluetoothManager bluetoothManager = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothManager = getSystemService(BluetoothManager.class);
        }

        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();

            if (mBluetoothAdapter == null) {
                Log.e(TAG, "Device does not support Bluetooth");
                return;
            }

            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                makeDiscoverable();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void makeDiscoverable() {
        // Check permissions again before making discoverable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasConnectPermission = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!hasConnectPermission) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for discoverable");
                return;
            }
        }

        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                makeDiscoverable();
            } else {
                Log.e(TAG, "Bluetooth not enabled");
            }
        } else if (requestCode == REQUEST_DISCOVERABLE) {
            if (resultCode == Activity.RESULT_OK) {
                mBluetoothInitialized = true;
                Log.i(TAG, "Bluetooth discoverable");
            } else {
                Log.e(TAG, "Bluetooth not discoverable");
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.enableView();
            mOpenCvCameraView.setOnTouchListener(ColorBlobDetectionActivity.this);
        }
    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        unregisterReceiver(receiver);
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mDetector = new ColorBlobDetector();
        mSpectrum = new Mat();
        mBlobColorRgba = new Scalar(255);
        mBlobColorHsv = new Scalar(255);
        SPECTRUM_SIZE = new Size(200, 64);
        CONTOUR_COLOR = new Scalar(255, 0, 0, 255);

    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    public boolean onTouch(View v, MotionEvent event) {
        int cols = mRgba.cols();
        int rows = mRgba.rows();

        int xOffset = (mOpenCvCameraView.getWidth() - cols) / 2;
        int yOffset = (mOpenCvCameraView.getHeight() - rows) / 2;

        int x = (int) event.getX() - xOffset;
        int y = (int) event.getY() - yOffset;

        Log.i(TAG, "Touch image coordinates: (" + x + ", " + y + ")");

        if ((x < 0) || (y < 0) || (x > cols) || (y > rows)) return false;

        Rect touchedRect = new Rect();

        touchedRect.x = (x > 4) ? x - 4 : 0;
        touchedRect.y = (y > 4) ? y - 4 : 0;

        touchedRect.width = (x + 4 < cols) ? x + 4 - touchedRect.x : cols - touchedRect.x;
        touchedRect.height = (y + 4 < rows) ? y + 4 - touchedRect.y : rows - touchedRect.y;

        Mat touchedRegionRgba = mRgba.submat(touchedRect);

        Mat touchedRegionHsv = new Mat();
        Imgproc.cvtColor(touchedRegionRgba, touchedRegionHsv, Imgproc.COLOR_RGB2HSV_FULL);

        // Calculate average color of touched region
        mBlobColorHsv = Core.sumElems(touchedRegionHsv);
        int pointCount = touchedRect.width * touchedRect.height;
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

    // the above suppresses missing bluetooth permission
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();

        // Centermark circle for view alignment
        double viewWidth = mOpenCvCameraView.getWidth() - 256;
        double viewHeight = mOpenCvCameraView.getHeight();

        Imgproc.circle(mRgba, new Point(viewWidth / 2, viewHeight / 2), (int) 10, new Scalar(0, 255, 0, 255), 2);
        Imgproc.circle(mRgba, new Point(viewWidth / 2, viewHeight / 2), 5, new Scalar(0, 255, 0, 255), -1);

        // Testing camera location - origin is left corner of table
        // First try using desmos projection representation of camera view
        // Values can later be informed by either checkerboard calibration, table detection or kept hardcoded for rigid mounting
        Point3 cameraLocation = new Point3(-0.4, -0.4, 0.5);
        // view rotation angles based on https://www.desmos.com/calculator/efc34da5b9?lang=zh-TW convention
        double s = 0; //-0.8
        double u = 0.4; //0.4

        Point3 opticalAxes = new Point3(1, 0, 0);
        Quaternion cameraStaticRotations = Quaternion.fromEuler(u, -s, 0);

        opticalAxes = cameraStaticRotations.rotateVector(opticalAxes);
        Log.i(TAG, "opticalAxes = (" + opticalAxes.x + ", " + opticalAxes.y + ", " + opticalAxes.z + ")");

        Point3 ballAxes = new Point3(1, 0, 0);


        if (mIsColorSelected) {
            mDetector.process(mRgba);
            List<MatOfPoint> contours = mDetector.getContours();
            //Log.i(TAG, "Contours count: " + contours.size());

            for (MatOfPoint contour : contours) {
                Point[] points = contour.toArray();

                if (points.length > 0) {
                    MatOfPoint2f contour2f = new MatOfPoint2f(points);
                    // Calculate minimum enclosing circle
                    Point center = new Point();
                    float[] radius = new float[1];
                    Imgproc.minEnclosingCircle(contour2f, center, radius);

                    // Draw the center point and enclosing circle
                    Imgproc.circle(mRgba, center, 5, new Scalar(0, 255, 0, 255), -1); // Green filled circle
                    Imgproc.circle(mRgba, center, (int)radius[0], new Scalar(0, 255, 0, 255), 2); // Green circle outline

                    // Center coordinates with offset:
                    Point centered = new Point();

                    centered.x = center.x - viewWidth /2;
                    centered.y = -(center.y - viewHeight /2);
                    //Log.i(TAG, "Offset center: (" + centered.x + ", " + centered.y + ")");

                    // xy normalisation
                    double xbcv = (1930./2180.)*0.721223; // see desmos, xb = tan(thetahor/2)
                    double ybcv = xbcv/1.787; // 1.787 is screen aspect ratio
                    Point normalised = new Point();
                    normalised.x = (2*xbcv*centered.x)/1930;
                    normalised.y = (2*ybcv*centered.y)/1080;
                    //Log.i(TAG, "Normalised ball center: (" + normalised.x + ", " + normalised.y + ")");

                    double thetaHor = atan(normalised.x);
                    double thetaVer = atan(normalised.y);
                    //Log.i(TAG, "thetaHor: " + thetaHor + ", thetaVer: " + thetaVer);

                    Quaternion cameraTotalRotations = Quaternion.fromEuler(u-thetaVer,-s-thetaHor,0);
                    ballAxes = cameraTotalRotations.rotateVector(ballAxes);


                }

            }


            Mat colorLabel = mRgba.submat(4, 68, 4, 68);
            colorLabel.setTo(mBlobColorRgba);

            Mat spectrumLabel = mRgba.submat(4, 4 + mSpectrum.rows(), 70, 70 + mSpectrum.cols());
            mSpectrum.copyTo(spectrumLabel);


        }

        Log.i(TAG, "ballAxes = (" + ballAxes.x + ", " + ballAxes.y + ", " + ballAxes.z + ")");

        return mRgba;
    }

    private Scalar convertScalarHsv2Rgba(Scalar hsvColor) {
        Mat pointMatRgba = new Mat();
        Mat pointMatHsv = new Mat(1, 1, CvType.CV_8UC3, hsvColor);
        Imgproc.cvtColor(pointMatHsv, pointMatRgba, Imgproc.COLOR_HSV2RGB_FULL, 4);

        return new Scalar(pointMatRgba.get(0, 0));
    }
}
