package com.example.test3;

import static org.opencv.calib3d.Calib3d.decomposeProjectionMatrix;
import static org.opencv.core.Core.bitwise_not;
import static org.opencv.core.Core.max;
import static org.opencv.imgproc.Imgproc.getPerspectiveTransform;

import static java.lang.Math.tan;

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

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
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

            Imgproc.cvtColor(input_color, hsv, Imgproc.COLOR_RGB2HSV); // convertion to HSV for inRange




            // table detection tests

            // table blue (tentative): 80-120, 0-120, 100-170 (tested on screen-displayed image, not reliable but as a reference)
            Scalar lowerColorBound = new Scalar(80, 0, 100); // HSV bounds - white is no saturation, max value, "singularity" hue
            Scalar upperColorBound = new Scalar(120, 120, 255);  // H: 0-179, S: 0-255, V: 0-255

            Imgproc.medianBlur(hsv,blur,1);

            Core.inRange(blur, lowerColorBound, upperColorBound, filtered);

            // note on thresholding: threshold gives global boundary values, while adaptivethreshold infers boundaries against local colours

            Imgproc.Canny(filtered,edges,300,600); // recommended low:high ratio 1:2 to 1:3, the higher they are the tighter the edge requirement
            Imgproc.HoughLinesP(edges,lines,1, 3.14159/180,20,20, 50);

            // note: look into findCountours, which selects the contour with the largest area, which is hopefully the table

            //Mat result = input_rgba.getInstance().getImage().clone();

            for (int i = 0; i < lines.cols(); i++) {
                double[] val = lines.get(0, i);
                Imgproc.line(edges, new Point(val[0], val[1]), new Point(val[2], val[3]), new Scalar(255, 0, 0), 10);
            }






            // ball detection testing

            // colour bounds for orange ball
            lowerColorBound = new Scalar(10, 100, 100); // HSV bounds
            upperColorBound = new Scalar(30, 255, 255);  // H: 0-179, S: 0-255, V: 0-255

            Mat ballImage = new Mat();

            // color filtration for orange balls test

            Mat testing = new Mat();

            Imgproc.medianBlur(hsv, ballImage,5);
            Imgproc.medianBlur(hsv, testing,5);
            Core.inRange(ballImage, lowerColorBound, upperColorBound, ballImage);



            // using HoughCircles - needs to tune the parameters first, not too reliable and many many false readings currently


            Mat circles = new Mat();

            Imgproc.HoughCircles(ballImage, circles, Imgproc.HOUGH_GRADIENT, 1.0,
                    (double)blur.rows()/4, // change this value to detect circles with different distances to each other
                    30.0, 10.0, 1, 30);


            for (int x = 0; x < circles.cols(); x++) {
                double[] c = circles.get(0, x);
                Point center = new Point(Math.round(c[0]), Math.round(c[1]));
                // circle center
                Imgproc.circle(ballImage, center, 1, new Scalar(0, 100, 100), 3, 8, 0);
                // circle outline
                int radius = (int) Math.round(c[2]);
                Imgproc.circle(ballImage, center, radius, new Scalar(255, 0, 255), 3, 8, 0);
            }

            Log.d(LOGTAG, String.valueOf(circles.cols()));



            // Testing findContours
            /*
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(ballImage, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            Log.d(LOGTAG, String.valueOf(contours.size()));

            for (int i = 0; i < contours.size(); i++) {
                Imgproc.drawContours(input_color, contours, i, new Scalar(255, 255, 255), -1);
            }
            */





            // image size
            int width = 864;
            int height = 480;

            double fx = width / tan(1.2497/2);   // focal lengths based on angle (check resources - intrinsic parameters calculation)
            double fy = height / tan(0.9913/2);   // source of fov angles in resources doc in pixels

            double cx = (double) (width) /2;    // note: some sources say width + 1 (so 432.5 instead of 432), see which works?
            double cy = (double) (height) /2;

            Mat cameraMatrix = new Mat (3,3, CvType.CV_64FC1);
            int row = 0, col = 0;
            cameraMatrix.put(row,col,fx,0,cx,0,fy,cy,0,0,1);


            //String dump = cameraMatrix.dump();
            //Log.d(LOGTAG, dump);

            // solvePnP test

            double tx = 1.525;
            double ty = 2.73;
            double tz = 0.76;

            // object (i.e. table) vertices - constant
            MatOfPoint3f objectPoints = new MatOfPoint3f(
                    new Point3(0,ty,0),
                    new Point3(tx,ty,0),
                    new Point3(0,0,0),
                    new Point3(tx,0,0));

            // initiate image vertices - top left, top right, bottom left, bottom right in that order
            // these points will come from either HoughlinesP, findContours or manual later
            // here are some test points
            Point[] image_vertices = new Point[4];
            image_vertices[0] = new Point(-102,5);
            image_vertices[1] = new Point(105,4);
            image_vertices[2] = new Point(-278,-150);
            image_vertices[3] = new Point(236,-156);

            MatOfPoint2f imagePoints = new MatOfPoint2f(
                    image_vertices[0],
                    image_vertices[1],
                    image_vertices[2],
                    image_vertices[3]);




            MatOfDouble coeff = new MatOfDouble();
            Mat rvec = new Mat();
            Mat tvec = new Mat();


            boolean findSolution = Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, coeff, rvec, tvec);

            //dump = rvec.dump();
            //Log.d(LOGTAG, dump);





            return ballImage;  // returns input frame to the screen display
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
