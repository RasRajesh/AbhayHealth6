# AbhayHealth – Your Health Journey, Connected.

**AbhayHealth** is a patient-first, hospital-ready, ABDM-ready mobile application designed to empower individuals and families—starting with Telugu-speaking families in Telangana and Andhra Pradesh—to securely track, manage, and understand their health records.

---

## 1. Executive Summary & Core Philosophy

- **Application ID:** `in.abhayhealth.app`
- **Firebase Project ID:** `abhayhealth-a8e0d`
- **Target Audience:** Telugu and English speaking users requiring personal health management, document extraction, medication reminders, and health trend analytics.
- **Local-First Data Architecture:** Patient health records, prescriptions, extracted lab metrics, vitals, symptom logs, and medication schedules reside strictly on-device inside a secure Room SQLite database. Medical documents and personal health records are **NEVER uploaded to Firebase or cloud servers**.
- **Authentication Model:** Genuine Firebase Phone Authentication using 6-digit SMS OTP (+91 E.164 format) as the primary identity provider, with optional Gmail account linking via Jetpack Credential Manager.

---

## 2. System Architecture & Tech Stack

```
AbhayHealth App (in.abhayhealth.app)
│
├── UI & Navigation Layer (Jetpack Compose & Navigation)
│   ├── Auth Flow: EnterPhoneScreen ──> VerifyOtpScreen (Firebase SMS OTP)
│   ├── Onboarding & Profile Setup (Local Profile Repository)
│   └── Main Application Shell (Home, Records, Medicines, Trends, AI Assistant, Me)
│
├── Authentication & Security (Firebase Auth SDK & Credential Manager)
│   ├── FirebaseAuthRepository (PhoneAuthOptions 60s timeout, E.164 +91 normalization)
│   └── AppCheckInitializer (Debug & Production Integrity Verification)
│
├── Local Storage Layer (Local-First Architecture)
│   ├── MedicalRecordDatabase (Room SQLite for Records, ExtractedInfo, Vitals, Symptoms, IntakeLogs)
│   └── UserPreferences (DataStore for onboarding state and app settings)
│
└── ML & Document Processing Engine
    ├── ML Kit Text Recognition (On-device OCR for prescriptions & lab reports)
    ├── MedicalFieldParser & DocumentExtractionEngine (Regex & NLP parsing)
    └── HealthMetricsPdfGenerator (Local PDF report generation)
```

---

## 3. Real-Time Firebase Phone OTP Authentication Flow

1. **Mobile Entry:** User enters a 10-digit Indian mobile number on `EnterPhoneScreen`. The application normalizes input to E.164 standard (`+91XXXXXXXXXX`).
2. **OTP Generation & Verification:** Firebase Authentication SDK triggers real-time SMS delivery to the mobile device.
3. **6-Digit OTP Screen:** App automatically transitions to `VerifyOtpScreen` displaying a countdown timer (30s resend cooldown), auto-retrieval capability, and manual entry field.
4. **Firebase Session & Local Identity:** Upon successful OTP verification, Firebase returns an authoritative `FirebaseUser.uid`.
5. **Account Isolation:** Patient health records in local Room database are strictly partitioned by `accountUid`. Logging out or switching accounts isolates data per UID without data loss.

---

## 4. Local-First Health Data Privacy & Security

> [!IMPORTANT]
> **Data Privacy Guarantee:** AbhayHealth does not transmit medical documents, prescription images, lab values, or personal health records to cloud servers. All document classification, OCR extraction, vitals tracking, and trend analysis are performed 100% on-device.

- **Storage Engine:** Android Room Database (`MedicalRecordDatabase.db`).
- **Account Isolation:** All database tables filter queries using the active `accountUid`.
- **Sign-Out Behavior:** Signing out clears session state while retaining local database records safely on device for the verified UID.

---

## 5. Build & Development Instructions

### Prerequisites
- **Android Studio:** Ladybug (2024.2.1+) or newer
- **JDK:** Java 11 or higher
- **Android SDK:** API Level 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`)

### Running Local Builds

```bash
# 1. Clean build cache
./gradlew clean

# 2. Execute unit test suite (50+ tests covering Auth, Field Parsers, PDF Generators, Vitals)
./gradlew :app:testDebugUnitTest --console=plain

# 3. Assemble Debug APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug --console=plain

# 4. Assemble Release APK (app/build/outputs/apk/release/app-release-unsigned.apk)
./gradlew :app:assembleRelease --console=plain

# 5. Build Production Release App Bundle (.aab for Google Play Store)
./gradlew :app:bundleRelease --console=plain
```

---

## 6. Release Signing Configuration

To sign release builds using your production upload key without committing secrets to version control:

Create `keystore.properties` in the root directory (ignored by `.gitignore`):

```properties
storeFile=/path/to/upload-keystore.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

Alternatively, set environment variables:
- `KEYSTORE_FILE`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If `keystore.properties` is absent, `./gradlew :app:bundleRelease` generates an unsigned AAB suitable for Google Play App Signing enrollment.

---

## 7. Firebase Console Manual Configuration Checklist

To ensure real-time SMS delivery on production physical devices:

1. **Firebase Authentication:**
   - Navigate to [Firebase Console](https://console.firebase.google.com/u/0/project/abhayhealth-a8e0d/authentication/providers).
   - Under **Sign-in method**, verify **Phone** provider is **Enabled**.
2. **SHA-1 & SHA-256 Fingerprints:**
   - Under **Project Settings > General > Your Apps (`in.abhayhealth.app`)**, add:
     - Development SHA-1 / SHA-256 fingerprints.
     - Google Play App Signing SHA-1 / SHA-256 fingerprints (from Play Console).
3. **App Check & Integrity:**
   - Enable **Play Integrity** or **reCAPTCHA Enterprise** provider under Firebase App Check for `in.abhayhealth.app`.

---

## 8. Google Play Store Release Checklist

- [x] Application ID verified as `in.abhayhealth.app`.
- [x] `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- [x] ProGuard rules enabled for release shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`).
- [x] Automated test suite verified (all unit tests passing).
- [x] Production Release AAB generated at `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Complete Play Console Data Safety declaration (declare Local Storage only for Health Data; Firebase Auth for Account Management).
- [ ] Submit AAB to Google Play Internal Testing track.
