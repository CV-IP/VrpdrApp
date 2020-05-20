package com.example.vrpdrapp;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {
    private static final String TAG = MainActivity.class.getSimpleName();

    private CameraBridgeViewBase cameraBridgeViewBase;
    private BaseLoaderCallback baseLoaderCallback;

    private Mat currentFrame;
    private Mat processingFrame;
    private Yolo yolo;
    List<Rect> boundingBoxes;

    private CharactersExtraction charactersExtraction;
    boolean ocrProcessing = false;

    private String ocrPrediction;

    private EMNISTNet emnistNet;

    private HashMap<Integer, Mat> characters = new HashMap<>(); // NOTE: used for debugging
    private int selectedCharacterIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraBridgeViewBase = (JavaCameraView) findViewById(R.id.CameraView);
        cameraBridgeViewBase.setVisibility(SurfaceView.VISIBLE);
        cameraBridgeViewBase.setCvCameraViewListener(this);

        baseLoaderCallback = new BaseLoaderCallback(this) {
            @Override
            public void onManagerConnected(int status) {
                super.onManagerConnected(status);
                switch (status) {
                    case BaseLoaderCallback.SUCCESS:
                        cameraBridgeViewBase.enableView();
                        break;
                    default:
                        super.onManagerConnected(status);
                        break;
                }
            }
        };

        Button predictButton = (Button) findViewById(R.id.recog_button);
        predictButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Imgproc.cvtColor(currentFrame, processingFrame, Imgproc.COLOR_RGBA2RGB);

                predict(processingFrame);
            }
        });

        Button clearButton = (Button) findViewById(R.id.clear_button);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ocrProcessing = false;
                ocrPrediction = "";
                characters.clear();

                ((ImageView) findViewById(R.id.crop_lp_preview)).setImageResource(0);
                ((ImageView) findViewById(R.id.lp_preprocessing_preview)).setImageResource(0);
                ((ImageView) findViewById(R.id.digit_preview)).setImageResource(0);

                ((TextView) findViewById(R.id.ocr_prediction)).setText("");
            }
        });


        // NOTE: used for debugging
        Button digit_back = (Button) findViewById(R.id.digit_back);
        digit_back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!characters.isEmpty()) {
                    --selectedCharacterIndex;
                    if (selectedCharacterIndex < 0) {
                        selectedCharacterIndex = characters.size() - 1;
                    }

                    showMatOnImageView(characters.get(selectedCharacterIndex), findViewById(R.id.digit_preview));
                }
            }
        });
        Button digit_foward = (Button) findViewById(R.id.digit_forward);
        digit_foward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!characters.isEmpty()) {
                    ++selectedCharacterIndex;
                    if (selectedCharacterIndex >= characters.size()) {
                        selectedCharacterIndex = 0;
                    }
                    showMatOnImageView(characters.get(selectedCharacterIndex), findViewById(R.id.digit_preview));
                }
            }
        });
    }

    private void predict(Mat frame) {
        ocrProcessing = true;

        boundingBoxes = yolo.detect(frame, true);

        if(boundingBoxes != null && !boundingBoxes.isEmpty()) {
            for (Rect boundingBox : boundingBoxes) {
                //Log.i(TAG, "ROI(x,y,w,h) ---> ROI("+boundingBox.x+"," +boundingBox.y+","+boundingBox.width+","+boundingBox.height+")");
                //drawLabeledBoundingBox(processingFrame, "Test", 2.0f, new Scalar(0, 0, 255), new Scalar(0, 0, 255), boundingBox, 2);
                if(boundingBox.x < 0 || boundingBox.y < 0
                        || boundingBox.width < 0 || boundingBox.height < 0
                        || boundingBox.x > frame.width() || boundingBox.y > frame.height()
                        || boundingBox.width > frame.width() || boundingBox.height > frame.height()) {
                    ocrProcessing = false;
                    return;
                }

                Log.i(TAG, "ROI(x,y,w,h) ---> ROI("+boundingBox.x+"," +boundingBox.y+","+boundingBox.width+","+boundingBox.height+")");
                Mat roi = new Mat(frame, boundingBox);
                List<Mat> chars = charactersExtraction.extract2(roi);

                showImageOnDebugPreview(roi, charactersExtraction.getFinalPreprocessedImg(), chars); // NOTE: used for debugging

                ocrPrediction = predictCharacters(chars);
                drawPredictionBoundingBox(boundingBox, ocrPrediction);
            }
        }
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        currentFrame = inputFrame.rgba();

        if(ocrProcessing) {
            return processingFrame;
        }

        Imgproc.cvtColor(currentFrame, currentFrame, Imgproc.COLOR_RGBA2RGB);
        boundingBoxes = yolo.detect(currentFrame, true);

        return currentFrame;
    }

    private String predictCharacters(List<Mat> characters) {
        String predictChars = "";
        for (Mat ch : characters) {
            predictChars += emnistNet.predict(ch);
        }

        return predictChars;
    }

    private void drawLabeledBoundingBox(Mat inputImage, String label, float fontScale, Scalar recColor, Scalar textColor, Rect boundingBox, int thickness) {
        Imgproc.rectangle(inputImage, boundingBox.tl(), boundingBox.br(), recColor, thickness, Core.FILLED);
        Imgproc.putText(inputImage, label, new Point(boundingBox.x, boundingBox.y), Core.FONT_HERSHEY_PLAIN, fontScale, textColor, thickness);
    }

    private void drawPredictionBoundingBox(Rect boundingBox, String prediction) {
        int x = boundingBox.x;
        int y = boundingBox.y + boundingBox.height;
        int w = boundingBox.x + (int) (1.3 * Imgproc.getTextSize(prediction, Core.FONT_HERSHEY_PLAIN, 0.7f, 2, null).width);
        int h = (boundingBox.y + boundingBox.height) + 25;
        Imgproc.rectangle(processingFrame, new Point(x, y), new Point(w, h), new Scalar(0, 255, 255), 2, Core.FILLED);
        Imgproc.putText(processingFrame, prediction, new Point(x+5, y+20), Core.FONT_HERSHEY_PLAIN, 0.7f, new Scalar(0, 255, 255), 2);

        TextView textView = (TextView) findViewById(R.id.ocr_prediction);
        textView.setBackgroundColor(Color.BLACK);
        textView.setTextColor(Color.CYAN);
        textView.setText(prediction);
    }

    private void showImageOnDebugPreview(Mat cropedImage, Mat preprocessedImage, List<Mat> chars) {
        showMatOnImageView(cropedImage, this.findViewById(R.id.crop_lp_preview));

        showMatOnImageView(preprocessedImage, this.findViewById(R.id.lp_preprocessing_preview));

        if(chars != null && !chars.isEmpty()) {
            showMatOnImageView(chars.get(0), this.findViewById(R.id.digit_preview));

            characters.clear();
            int index = 0;
            for(Mat ch : chars) {
                characters.put(index++, ch);
            }
        }
    }

    private void showMatOnImageView(Mat image, ImageView imageView) {
        Bitmap bitmap = null;

        bitmap = Bitmap.createBitmap(image.width(), image.height(),Bitmap.Config.ARGB_8888);

        Utils.matToBitmap(image, bitmap);

        imageView.setAdjustViewBounds(true);
        imageView.setMaxWidth(image.width());
        imageView.setMaxHeight(image.height());
        imageView.setImageBitmap(bitmap);
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        processingFrame = new Mat(height, width, CvType.CV_8UC3);

        yolo = new Yolo(this,
                768, 416,
                "classes.names",
                "yolov3_license_plates_tiny.cfg",
                "yolov3_license_plates_tiny_best.weights",
                0.6f,
                0.5f);

        charactersExtraction = new CharactersExtraction();

        emnistNet = new EMNISTNet(this, "emnist_net_custom_mobile.pth");

        Log.i(TAG, "Modules loaded!");
    }

    @Override
    public void onCameraViewStopped() {
        currentFrame.release();
        processingFrame.release();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(!OpenCVLoader.initDebug()) {
            Toast.makeText(getApplicationContext(), "OpenCV debug couldn't load properly!", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, baseLoaderCallback);
            return;
        }

        baseLoaderCallback.onManagerConnected(baseLoaderCallback.SUCCESS);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if(cameraBridgeViewBase != null){
            cameraBridgeViewBase.disableView();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (cameraBridgeViewBase != null){
            cameraBridgeViewBase.disableView();
        }
    }
}

