# MSNMessageSender

A small private Android application that sends one SMS message to an editable list using the phone's SIM. No external SMS provider or server is required.

## Version 1

- Recipient list and message are editable and saved only on the phone
- Full Markdown tables can be pasted; formatting and empty cells are ignored
- Default message: **Buenos días**
- Peruvian mobile numbers are normalized to the `+51` format
- Duplicate numbers are removed and invalid numbers are rejected
- Confirmation is required before sending
- Messages are spaced 1.8 seconds apart
- Maximum of 50 unique recipients per batch
- Larger lists are split into batches with a minimum 30-minute wait between them
- Delayed batches are persisted with Android WorkManager and can continue after the app closes
- Android requests SMS permission at runtime

The app reports when Android has accepted an SMS for sending. Final network delivery still depends on the SIM, signal, balance, and mobile operator.

## Download the APK from GitHub Actions

1. Open the repository's **Actions** tab.
2. Select **Build Android APK**.
3. Open the latest successful run.
4. Download the `MSNMessageSender-debug` artifact.
5. Extract the ZIP and copy `app-debug.apk` to the Samsung A52.
6. Open the APK and allow installation from the selected file manager when Android asks.
7. Open **SMS Sender**, enter the recipients, and grant its SMS permission.

Every push to `main` starts a new APK build automatically.

## Build with Android Studio

Open the repository folder as an existing Android project. Allow Gradle synchronization to finish, then use **Build > Build APK(s)**.

## Important

- SMS charges and operator limits apply.
- Only message recipients who have agreed to receive the messages.
- The current release uses the phone's default SMS subscription.
- Android battery management can delay a scheduled batch beyond 30 minutes; it will never intentionally start sooner.
- Long messages can be billed as multiple SMS segments.
- Recipient numbers are intentionally not stored in this public repository.
