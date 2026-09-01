package com.aferrante.msnmessagesender;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class SmsBatchWorker extends Worker {
    public static final String KEY_RECIPIENTS = "recipients";
    public static final String KEY_MESSAGE = "message";
    public static final String WORK_TAG = "scheduled-sms-batch";

    private static final int MAX_PER_BATCH = 50;
    private static final long SEND_DELAY_MS = 1800L;
    private static final long BATCH_WAIT_MINUTES = 30L;

    public SmsBatchWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        String[] recipients = getInputData().getStringArray(KEY_RECIPIENTS);
        String message = getInputData().getString(KEY_MESSAGE);

        if (recipients == null || recipients.length == 0 || message == null || message.trim().isEmpty()) {
            return Result.failure();
        }

        int batchSize = Math.min(MAX_PER_BATCH, recipients.length);
        int queued = 0;
        int failed = 0;
        SmsManager smsManager = getSmsManager();

        for (int index = 0; index < batchSize; index++) {
            try {
                ArrayList<String> parts = smsManager.divideMessage(message);
                if (parts.size() > 1) {
                    smsManager.sendMultipartTextMessage(recipients[index], null, parts, null, null);
                } else {
                    smsManager.sendTextMessage(recipients[index], null, message, null, null);
                }
                queued++;
            } catch (Exception exception) {
                failed++;
            }

            if (index + 1 < batchSize) {
                try {
                    Thread.sleep(SEND_DELAY_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    saveResult(queued, failed, recipients.length - index - 1, "interrumpido");
                    return Result.success();
                }
            }
        }

        String[] remaining = Arrays.copyOfRange(recipients, batchSize, recipients.length);
        if (remaining.length > 0) {
            enqueueNextBatch(remaining, message);
        }

        saveResult(queued, failed, remaining.length, remaining.length > 0 ? "programado" : "terminado");
        return Result.success();
    }

    private SmsManager getSmsManager() {
        int subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId();
        SmsManager manager = getApplicationContext().getSystemService(SmsManager.class);
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return manager.createForSubscriptionId(subscriptionId);
        }
        return manager;
    }

    private void enqueueNextBatch(String[] recipients, String message) {
        Data data = new Data.Builder()
                .putStringArray(KEY_RECIPIENTS, recipients)
                .putString(KEY_MESSAGE, message)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SmsBatchWorker.class)
                .setInputData(data)
                .setInitialDelay(BATCH_WAIT_MINUTES, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(getApplicationContext()).enqueue(request);
    }

    private void saveResult(int queued, int failed, int remaining, String state) {
        SharedPreferences preferences = getApplicationContext()
                .getSharedPreferences("sms_sender_preferences", Context.MODE_PRIVATE);
        preferences.edit()
                .putString("last_background_result",
                        "Último bloque: " + queued + " preparado(s), " + failed
                                + " error(es), " + remaining + " pendiente(s). Estado: " + state + ".")
                .apply();
    }
}
