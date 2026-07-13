package com.example.amudai;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Scanner;

/**
 * MainActivity — Amud AI
 * ------------------------------------------------------------------
 * Voice-driven AI assistant built entirely with programmatic UI
 * (no XML layout files required).
 *
 * Flow:
 *   1. User taps the mic button -> SpeechRecognizer converts speech to text (STT)
 *   2. The recognized text is sent to an AI API (Gemini) on a background thread
 *   3. The AI's reply is displayed on screen AND spoken aloud via TextToSpeech (TTS)
 *
 * IMPORTANT PRODUCTION NOTE:
 *   Do NOT ship this app with a real API key hardcoded in GEMINI_API_KEY.
 *   Anyone can decompile an APK and extract embedded string constants.
 *   Route requests through your own backend/proxy server that holds the
 *   real key server-side, and have the app call your backend instead.
 * ------------------------------------------------------------------
 */
public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "AmudAI";
    private static final int REQ_RECORD_AUDIO = 101;

    // TODO: Replace with your own key, or better yet, point this at your own backend proxy.
    private static final String GEMINI_API_KEY = "YOUR_GEMINI_API_KEY_HERE";
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="
                    + GEMINI_API_KEY;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;
    private TextView conversationLog;
    private Button micButton;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: starting Amud AI");

        try {
            // Order matters:
            // 1) Build the UI first so views exist before anything references them.
            buildUi();
            // 2) Initialize TTS engine (async callback -> onInit()).
            initTextToSpeech();
            // 3) Check/request mic permission last, since it may show a system dialog.
            checkAndRequestAudioPermission();
        } catch (Exception e) {
            Log.e(TAG, "onCreate: fatal error during initialization", e);
            Toast.makeText(this, "App failed to start.", Toast.LENGTH_LONG).show();
        }
    }

    // ==========================================================
    // 1) Build all UI programmatically (no XML layout dependency)
    // ==========================================================
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#0D0D0D")); // dark cyberpunk theme
        root.setPadding(24, 48, 24, 24);

        // Scrollable conversation log
        conversationLog = new TextView(this);
        conversationLog.setTextColor(Color.parseColor("#00FFAA"));
        conversationLog.setTextSize(16);
        conversationLog.setText("Amud AI is ready.\nTap the mic button and start speaking...\n");

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)); // fill remaining space
        scrollView.addView(conversationLog);

        // Mic button
        micButton = new Button(this);
        micButton.setText("🎙 Talk to Amud AI");
        micButton.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        micButton.setOnClickListener(v -> startListening());

        root.addView(scrollView);
        root.addView(micButton);

        setContentView(root);
    }

    private void appendLog(String speaker, String text) {
        runOnUiThread(() -> {
            conversationLog.append("\n[" + speaker + "]: " + text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ==========================================================
    // 2) Runtime permission handling for the microphone
    // ==========================================================
    private void checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Microphone permission granted");
            } else {
                Toast.makeText(this,
                        "Microphone permission is required to use voice input.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==========================================================
    // 3) Start listening (STT) using Android's built-in SpeechRecognizer
    // ==========================================================
    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestAudioPermission();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this device.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            // Change this locale to match your target audience, e.g. "en-US"
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    micButton.setText("🎙 Listening...");
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    micButton.setText("🎙 Talk to Amud AI");
                    if (matches != null && !matches.isEmpty()) {
                        String userText = matches.get(0);
                        appendLog("You", userText);
                        sendToAi(userText);
                    }
                }

                @Override
                public void onError(int error) {
                    Log.e(TAG, "SpeechRecognizer error code: " + error);
                    micButton.setText("🎙 Talk to Amud AI");
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });

            speechRecognizer.startListening(intent);

        } catch (Exception e) {
            Log.e(TAG, "startListening: error starting speech recognition", e);
        }
    }

    // ==========================================================
    // 4) Send recognized text to the AI API (Gemini) on a background thread
    // ==========================================================
    private void sendToAi(String userText) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject part = new JSONObject().put("text", userText);
                JSONObject content = new JSONObject()
                        .put("parts", new JSONArray().put(part));
                JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(content));

                URL url = new URL(GEMINI_ENDPOINT);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode == 200) ? conn.getInputStream() : conn.getErrorStream();

                String responseText = "";
                try (Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    responseText = scanner.hasNext() ? scanner.next() : "";
                }

                if (responseCode == 200) {
                    JSONObject json = new JSONObject(responseText);
                    String aiReply = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text");

                    appendLog("Amud AI", aiReply);
                    speak(aiReply);
                } else {
                    Log.e(TAG, "sendToAi: API error " + responseCode + " -> " + responseText);
                    appendLog("System", "AI could not respond right now (code " + responseCode + ").");
                }

            } catch (Exception e) {
                Log.e(TAG, "sendToAi: error calling AI API", e);
                appendLog("System", "Could not connect to AI.");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ==========================================================
    // 5) Text-to-Speech (TTS) setup and playback
    // ==========================================================
    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = textToSpeech.setLanguage(new Locale("th", "TH"));
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "onInit: Thai language not supported for TTS on this device");
            }
        } else {
            Log.e(TAG, "onInit: TextToSpeech failed to initialize");
        }
    }

    private void speak(String text) {
        if (textToSpeech != null) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amud_ai_utterance");
        }
    }

    // ==========================================================
    // 6) Back button: no WebView here, so default behavior is fine.
    //    (Kept as a placeholder in case a WebView or fragment stack is added later.)
    // ==========================================================
    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    // ==========================================================
    // 7) Cleanup on destroy to avoid leaks
    // ==========================================================
    @Override
    protected void onDestroy() {
        try {
            if (speechRecognizer != null) {
                speechRecognizer.destroy();
            }
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
            }
        } catch (Exception e) {
            Log.e(TAG, "onDestroy: error during cleanup", e);
        }
        super.onDestroy();
    }
}
