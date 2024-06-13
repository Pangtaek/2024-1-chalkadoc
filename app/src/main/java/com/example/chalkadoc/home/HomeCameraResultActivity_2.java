package com.example.chalkadoc.home;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.chalkadoc.R;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class HomeCameraResultActivity_2 extends AppCompatActivity {

    private ImageView imageView;
    private TextView resultTextView;
    private Interpreter tflite;
    private TextView tv_detailPage;
    public String resultToNextPage;
    private static final String TAG = "ResultActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_camera_result_2);

        imageView = findViewById(R.id.iv_camera);
        resultTextView = findViewById(R.id.tv_teeth_result);
        tv_detailPage = findViewById(R.id.tv_detailResult);

        tv_detailPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(HomeCameraResultActivity_2.this, HomeCameraDetailResultActivity.class);
//                intent.putExtra("imageUri", resultToNextPage);

                // DentalActivity 실행
                startActivity(intent);
            }
        });

        String imageUriString = getIntent().getStringExtra("imageUri");
        if (imageUriString != null) {
            try {
                Uri imageUri = Uri.parse(imageUriString);
                Log.d(TAG, "Received imageUri: " + imageUri.toString());

                try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                    if (is != null) {
                        Bitmap bitmap = BitmapFactory.decodeStream(is);
                        imageView.setImageBitmap(bitmap);
                        Log.d(TAG, "Image received and set successfully");

                        tflite = new Interpreter(loadModelFile());
                        Log.d(TAG, "TensorFlow Lite 모델 로드 성공");

                        String result = analyzeImage(bitmap);
                        resultTextView.setText(result);
                        resultToNextPage = result;
                    } else {
                        throw new Exception("InputStream is null");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                resultTextView.setText("이미지 로드 실패");
                Log.e(TAG, "이미지 로드 실패", e);
            }
        } else {
            resultTextView.setText("이미지 데이터가 없습니다");
            Log.e(TAG, "이미지 URI가 없습니다");
        }
    }

    private MappedByteBuffer loadModelFile() throws Exception {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("dentalbest-fp16.tflite");
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private String analyzeImage(Bitmap bitmap) {
        try {
            // 이미지 리사이즈
            int inputImageWidth = 640;
            int inputImageHeight = 640;
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true);

            // 입력 ByteBuffer 생성
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * inputImageWidth * inputImageHeight * 3);
            inputBuffer.order(ByteOrder.nativeOrder());

            int[] intValues = new int[inputImageWidth * inputImageHeight];
            resizedBitmap.getPixels(intValues, 0, inputImageWidth, 0, 0, inputImageWidth, inputImageHeight);

            for (int i = 0; i < intValues.length; ++i) {
                int val = intValues[i];
                inputBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
                inputBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
                inputBuffer.putFloat((val & 0xFF) / 255.0f);
            }

            // 모델의 출력 텐서 형상에 맞게 출력 배열 생성
            float[][][] output = new float[1][25200][10];

            // 모델 실행
            tflite.run(inputBuffer, output);
            Log.d(TAG, "TensorFlow Lite 모델 실행 성공");

            // Bitmap을 Canvas에 그리기 위한 준비
            Bitmap mutableBitmap = resizedBitmap.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(mutableBitmap);
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);

            Paint textPaint = new Paint();
            textPaint.setColor(Color.RED);
            textPaint.setTextSize(24);

            String[] labels = {"잇몸부종", "치석", "충치", "부정교합", "치아착색"};

            // 결과를 저장할 변수
            String result = "분석 실패";

            // 가장 높은 확률의 객체 찾기
            int maxIndex = -1;
            float maxProbability = -1.0f;

            for (int i = 0; i < 25200; i++) {
                float objectProbability = output[0][i][4]; // 객체 확률
                if (objectProbability > maxProbability) {
                    maxProbability = objectProbability;
                    maxIndex = i;
                }
            }

            // 임계값 설정
            float threshold = 0.5f;

            if (maxIndex != -1 && maxProbability >= threshold) {
                // 가장 높은 확률을 가진 객체의 클래스 정보 추출
                int classIndex = -1;
                float maxClassProbability = -1.0f;
                for (int j = 5; j < 10; j++) {
                    float classProbability = output[0][maxIndex][j];
                    if (classProbability > maxClassProbability) {
                        maxClassProbability = classProbability;
                        classIndex = j - 5; // 클래스 인덱스를 맞추기 위해 5를 뺍니다.
                    }
                }

                if (classIndex != -1) {
                    // 경계 상자 좌표 추출
                    float centerX = output[0][maxIndex][0] * inputImageWidth;
                    float centerY = output[0][maxIndex][1] * inputImageHeight;
                    float width = output[0][maxIndex][2] * inputImageWidth;
                    float height = output[0][maxIndex][3] * inputImageHeight;

                    float left = centerX - (width / 2);
                    float top = centerY - (height / 2);
                    float right = centerX + (width / 2);
                    float bottom = centerY + (height / 2);

                    // 경계 상자 그리기
                    canvas.drawRect(left, top, right, bottom, paint);

                    // 클래스 이름 그리기
                    canvas.drawText(labels[classIndex], left, top - 10, textPaint);

                    result = "예측: " + labels[classIndex] + " (정확도: " + (int) (maxClassProbability * 100) + "%)";
                }
            } else {
                // 임계값 이상의 객체가 없는 경우
                result = "결과 없음";
            }

            // 결과 반환
            imageView.setImageBitmap(mutableBitmap);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "TensorFlow Lite 모델 실행 실패", e);
            return "분석 실패";
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) {
            tflite.close();
        }
    }
}
