package com.aferrante.msnmessagesender;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String PREFS = "sms_sender_preferences";
    private static final String DEFAULT_NUMBERS = "";
    private static final String DEFAULT_MESSAGE = "Buenos días";
    private static final int MAX_PER_BATCH = 50;
    private static final int MAX_TOTAL_RECIPIENTS = 500;
    private static final long BATCH_WAIT_MINUTES = 30L;
    private static final long SEND_DELAY_MS = 1800L;
    private static final int SMS_PERMISSION_REQUEST = 1001;
    private static final Pattern PERU_MOBILE_PATTERN = Pattern.compile(
            "(?:\\+?51[\\s.-]*)?(9(?:[\\s.-]*\\d){8})"
    );

    private EditText recipientsInput;
    private EditText messageInput;
    private Button sendButton;
    private TextView statusText;
    private List<String> pendingRecipients;
    private String pendingMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recipientsInput = findViewById(R.id.recipientsInput);
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        statusText = findViewById(R.id.statusText);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        recipientsInput.setText(preferences.getString("recipients", DEFAULT_NUMBERS));
        messageInput.setText(preferences.getString("message", DEFAULT_MESSAGE));

        sendButton.setOnClickListener(v -> validateAndConfirm());
    }

    private void validateAndConfirm() {
        ParseResult parsed = parseRecipients(recipientsInput.getText().toString());
        String message = messageInput.getText().toString().trim();

        if (!parsed.invalidEntries.isEmpty()) {
            showMessage("Números inválidos",
                    "Corrige estos números antes de enviar:\n\n" + String.join("\n", parsed.invalidEntries));
            return;
        }
        if (parsed.validNumbers.isEmpty()) {
            showMessage("Faltan destinatarios", "Agrega por lo menos un número de celular.");
            return;
        }
        if (parsed.validNumbers.size() > MAX_TOTAL_RECIPIENTS) {
            showMessage("Demasiados destinatarios",
                    "Esta versión permite hasta " + MAX_TOTAL_RECIPIENTS
                            + " números en una cola de envío.");
            return;
        }
        if (message.isEmpty()) {
            showMessage("Falta el mensaje", "Escribe el mensaje que deseas enviar.");
            return;
        }

        pendingRecipients = parsed.validNumbers;
        pendingMessage = message;

        int batchCount = (parsed.validNumbers.size() + MAX_PER_BATCH - 1) / MAX_PER_BATCH;
        String scheduleWarning = batchCount > 1
                ? "\n\nAVISO: La lista se dividirá en " + batchCount
                    + " bloques de máximo " + MAX_PER_BATCH
                    + ". Después de cada bloque, la aplicación esperará por lo menos "
                    + BATCH_WAIT_MINUTES + " minutos antes de continuar."
                : "";

        new AlertDialog.Builder(this)
                .setTitle("Confirmar envío")
                .setMessage("Se enviará un SMS individual a " + parsed.validNumbers.size()
                        + " destinatario(s)." + scheduleWarning
                        + "\n\nMensaje:\n" + message
                        + "\n\nSe aplicarán los cargos normales de tu operador.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Enviar", (dialog, which) -> requestPermissionOrSend())
                .show();
    }

    private void requestPermissionOrSend() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("recipients", recipientsInput.getText().toString().trim())
                .putString("message", pendingMessage)
                .apply();

        if (checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            beginSending(pendingRecipients, pendingMessage);
        } else {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != SMS_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingRecipients != null) {
            beginSending(pendingRecipients, pendingMessage);
        } else {
            statusText.setText("Permiso de SMS rechazado.");
            Toast.makeText(this, "La aplicación necesita permiso para enviar SMS.", Toast.LENGTH_LONG).show();
        }
    }

    private void beginSending(List<String> recipients, String message) {
        sendButton.setEnabled(false);
        recipientsInput.setEnabled(false);
        messageInput.setEnabled(false);
        statusText.setText("Preparando envío…");

        int immediateCount = Math.min(MAX_PER_BATCH, recipients.size());
        List<String> immediate = new ArrayList<>(recipients.subList(0, immediateCount));
        List<String> deferred = new ArrayList<>(recipients.subList(immediateCount, recipients.size()));

        Handler handler = new Handler(Looper.getMainLooper());
        sendNext(handler, immediate, deferred, message, 0, 0, new ArrayList<>());
    }

    private void sendNext(Handler handler, List<String> recipients, List<String> deferred,
                          String message, int index, int queued, List<String> failures) {
        if (index >= recipients.size()) {
            finishSending(queued, failures, deferred, message);
            return;
        }

        String number = recipients.get(index);
        statusText.setText("Enviando " + (index + 1) + " de " + recipients.size() + "…");

        int nextQueued = queued;
        try {
            SmsManager smsManager = getSmsManager();
            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() > 1) {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(number, null, message, null, null);
            }
            nextQueued++;
        } catch (Exception exception) {
            failures.add(number + ": " + readableError(exception));
        }

        int finalNextQueued = nextQueued;
        handler.postDelayed(
                () -> sendNext(handler, recipients, deferred, message, index + 1, finalNextQueued, failures),
                SEND_DELAY_MS
        );
    }

    private SmsManager getSmsManager() {
        int subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId();
        SmsManager manager = getSystemService(SmsManager.class);
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return manager.createForSubscriptionId(subscriptionId);
        }
        return manager;
    }

    private void finishSending(int queued, List<String> failures,
                               List<String> deferred, String message) {
        sendButton.setEnabled(true);
        recipientsInput.setEnabled(true);
        messageInput.setEnabled(true);
        pendingRecipients = null;
        pendingMessage = null;

        String result = queued + " mensaje(s) entregados al sistema de envío.";
        if (!failures.isEmpty()) {
            result += "\n\nNo se pudieron preparar:\n" + String.join("\n", failures);
        }
        if (!deferred.isEmpty()) {
            scheduleDeferredBatch(deferred, message);
            result += "\n\nQuedan " + deferred.size()
                    + " mensaje(s). El siguiente bloque se enviará después de esperar "
                    + BATCH_WAIT_MINUTES + " minutos como mínimo.";
        }
        statusText.setText(result);
        showMessage(deferred.isEmpty() ? "Proceso terminado" : "Primer bloque terminado", result);
    }

    private void scheduleDeferredBatch(List<String> recipients, String message) {
        Data data = new Data.Builder()
                .putStringArray(SmsBatchWorker.KEY_RECIPIENTS, recipients.toArray(new String[0]))
                .putString(SmsBatchWorker.KEY_MESSAGE, message)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SmsBatchWorker.class)
                .setInputData(data)
                .setInitialDelay(BATCH_WAIT_MINUTES, TimeUnit.MINUTES)
                .addTag(SmsBatchWorker.WORK_TAG)
                .build();

        WorkManager.getInstance(this).enqueue(request);
    }

    private ParseResult parseRecipients(String raw) {
        Set<String> unique = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();

        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            Matcher matcher = PERU_MOBILE_PATTERN.matcher(trimmed);
            boolean foundNumber = false;
            while (matcher.find()) {
                String normalized = normalizePeruvianMobile(matcher.group());
                if (normalized != null) {
                    unique.add(normalized);
                    foundNumber = true;
                }
            }

            // Markdown table borders and empty cells are intentionally ignored.
            // A line containing digits but no valid mobile number is reported.
            if (!foundNumber && trimmed.matches(".*\\d.*")) {
                invalid.add(trimmed);
            }
        }
        return new ParseResult(new ArrayList<>(unique), invalid);
    }

    private String normalizePeruvianMobile(String input) {
        String digits = input.replaceAll("\\D", "");
        if (digits.startsWith("0051")) {
            digits = digits.substring(4);
        } else if (digits.startsWith("51") && digits.length() == 11) {
            digits = digits.substring(2);
        }
        if (digits.length() == 9 && digits.startsWith("9")) {
            return "+51" + digits;
        }
        return null;
    }

    private String readableError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Aceptar", null)
                .show();
    }

    private static final class ParseResult {
        final List<String> validNumbers;
        final List<String> invalidEntries;

        ParseResult(List<String> validNumbers, List<String> invalidEntries) {
            this.validNumbers = validNumbers;
            this.invalidEntries = invalidEntries;
        }
    }
}
