package com.aferrante.msnmessagesender;

import android.Manifest;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "sms_sender_preferences";
    private static final String DEFAULT_NUMBERS = "";
    private static final String DEFAULT_MESSAGE = "Buenos días";
    private static final int MAX_RECIPIENTS = 50;
    private static final long SEND_DELAY_MS = 1800L;

    private EditText recipientsInput;
    private EditText messageInput;
    private Button sendButton;
    private TextView statusText;
    private List<String> pendingRecipients;
    private String pendingMessage;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && pendingRecipients != null) {
                    beginSending(pendingRecipients, pendingMessage);
                } else {
                    statusText.setText("Permiso de SMS rechazado.");
                    Toast.makeText(this, "La aplicación necesita permiso para enviar SMS.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
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
        if (parsed.validNumbers.size() > MAX_RECIPIENTS) {
            showMessage("Demasiados destinatarios",
                    "Esta versión permite hasta " + MAX_RECIPIENTS + " números por envío.");
            return;
        }
        if (message.isEmpty()) {
            showMessage("Falta el mensaje", "Escribe el mensaje que deseas enviar.");
            return;
        }

        pendingRecipients = parsed.validNumbers;
        pendingMessage = message;

        new AlertDialog.Builder(this)
                .setTitle("Confirmar envío")
                .setMessage("Se enviará un SMS individual a " + parsed.validNumbers.size()
                        + " destinatario(s).\n\nMensaje:\n" + message
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            beginSending(pendingRecipients, pendingMessage);
        } else {
            permissionLauncher.launch(Manifest.permission.SEND_SMS);
        }
    }

    private void beginSending(List<String> recipients, String message) {
        sendButton.setEnabled(false);
        recipientsInput.setEnabled(false);
        messageInput.setEnabled(false);
        statusText.setText("Preparando envío…");

        Handler handler = new Handler(Looper.getMainLooper());
        sendNext(handler, recipients, message, 0, 0, new ArrayList<>());
    }

    private void sendNext(Handler handler, List<String> recipients, String message,
                          int index, int queued, List<String> failures) {
        if (index >= recipients.size()) {
            finishSending(queued, failures);
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
                () -> sendNext(handler, recipients, message, index + 1, finalNextQueued, failures),
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

    private void finishSending(int queued, List<String> failures) {
        sendButton.setEnabled(true);
        recipientsInput.setEnabled(true);
        messageInput.setEnabled(true);
        pendingRecipients = null;
        pendingMessage = null;

        String result = queued + " mensaje(s) entregados al sistema de envío.";
        if (!failures.isEmpty()) {
            result += "\n\nNo se pudieron preparar:\n" + String.join("\n", failures);
        }
        statusText.setText(result);
        showMessage("Proceso terminado", result);
    }

    private ParseResult parseRecipients(String raw) {
        Set<String> unique = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();

        for (String entry : raw.split("[\\n,;]+")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = normalizePeruvianMobile(trimmed);
            if (normalized == null) {
                invalid.add(trimmed);
            } else {
                unique.add(normalized);
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
