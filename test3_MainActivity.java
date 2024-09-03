package com.example.test3;

import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraActivity;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.List;
import java.util.Vector;


public class MainActivity extends CameraActivity {

    private static String LOGTAG = "OpenCV_Log";
    private CameraBridgeViewBase mOpenCvCameraView;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status){
                case LoaderCallbackInterface.SUCCESS:{
                    Log.v(LOGTAG,"OpenCV Loaded");
                    mOpenCvCameraView.enableView();
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }

        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        if (OpenCVLoader.initLocal()) {
            Log.d(LOGTAG,"OpenCV initialized");
        }

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.opencv_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(cvCameraViewListener);

    }

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        return Collections.singletonList(mOpenCvCameraView);
    }

    // camera view listener that takes in opencv camera view, three methods
    private CameraBridgeViewBase.CvCameraViewListener2 cvCameraViewListener = new CameraBridgeViewBase.CvCameraViewListener2() {
        @Override
        public void onCameraViewStarted(int width, int height) {

        }

        @Override
        public void onCameraViewStopped() {

        }

        @Override
        public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {  // called every time new frame is available
            // do stuff here

            Mat input_color = inputFrame.rgba();   // rgb version of camera input
            Mat input_gray = inputFrame.gray();   // greyscale version of camera input

            Mat hsv = new Mat();
            Mat filtered = new Mat();
            Mat blur = new Mat();
            Mat edges = new Mat();
            Mat lines = new Mat();

            Imgproc.cvtColor(input_color, hsv, Imgproc.COLOR_BGR2HSV); // convertion to HSV for inRange

            // table blue (tentative): 80-120, 0-120, 100-170 (tested on screen-displayed image, not reliable but as a reference)
            Scalar lowerColorBound = new Scalar(80, 0, 100); // HSV bounds - white is no saturation, max value, "singularity" hue
            Scalar upperColorBound = new Scalar(120, 120, 170);  // H: 0-179, S: 0-255, V: 0-255

            Core.inRange(hsv, lowerColorBound, upperColorBound, filtered);

            // note on thresholding: threshold gives global boundary values, while adaptivethreshold infers boundaries against local colours

            //Imgproc.GaussianBlur(filtered,blur,new Size(45,45),1,1);
            Imgproc.medianBlur(filtered,blur,3);
            Imgproc.Canny(blur,edges,300,600); // recommended low:high ratio 1:2 to 1:3, the higher they are the tighter the edge requirement
            Imgproc.HoughLinesP(edges,lines,1, 3.14159/180,50,20, 50);

            // note: look into findCountours, which selects the contour with the largest area, which is hopefully the table

            //Mat result = input_rgba.getInstance().getImage().clone();

            for (int i = 0; i < lines.cols(); i++) {
                double[] val = lines.get(0, i);
                Imgproc.line(edges, new Point(val[0], val[1]), new Point(val[2], val[3]), new Scalar(255, 0, 0), 10);
            }

            Size size = input_color.size();
            Log.d(LOGTAG, String.valueOf(size.height));

            return edges;  // returns input frame to the screen display
        }
    };

    @Override //Disables camera view when app is paused by overriding stuff in the superclass
    public void onPause() {
        super.onPause();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    @Override // checks if opencv is initialised, if not
    public void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(LOGTAG, "OpenCV not found, Initializing");
            //OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, AppContext: this, mLoaderCallback); // original ver, initAsync doensn't exist for my version
            OpenCVLoader.initLocal();  // just use local init?
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override //For when the app is closed
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

}
