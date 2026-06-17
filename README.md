# Remote Phone Alarm Trigger System (GuardianLink)

GuardianLink is a complete remote alarm trigger system. It allows you to trigger a loud, continuous alarm sound (with flashlight blinking) on an Android phone from a web-based admin control panel, bypassing silent profiles and Do Not Disturb (DND) modes. The phone also streams its live GPS location back to the dashboard, rendering it on an interactive dark-themed Leaflet map.

---

## Folder Structure

```text
remote-phone-alarm/
├── android-app/             # Java-based Android Studio project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/example/remotealarm/
│   │   │   │   ├── MainActivity.java            # UI, configuration & permissions
│   │   │   │   ├── AlarmService.java            # Foreground siren service (sound, flash, GPS)
│   │   │   │   └── MyFirebaseMessagingService.java # FCM receiver & token reporter
│   │   │   ├── res/layout/
│   │   │   │   └── activity_main.xml            # Dashboard layout
│   │   │   └── AndroidManifest.xml              # Permissions & Service registries
│   │   └── build.gradle                         # App level dependencies
│   ├── build.gradle                             # Project build settings
│   └── settings.gradle                          # Module registry
├── backend/                 # Node.js + Express backend API
│   ├── src/
│   │   └── server.js        # Express endpoints & Firebase Admin SDK wrapper
│   ├── .env                 # Server configuration credentials
│   ├── .env.example         # Configuration template
│   └── package.json         # Node packaging metadata
├── frontend/                # Web Admin Panel UI
│   ├── index.html           # Dashboard interface
│   ├── css/
│   │   └── style.css        # Premium Glassmorphic design styling
│   └── js/
│   │   └── app.js           # API polling and interactive Leaflet map controller
└── README.md                # This instructions document
```

---

## 1. Firebase Configuration Guide (FCM Setup)

To route trigger messages to the Android client from the backend, you must configure a Firebase project.

### Step 1: Create a Firebase Project
1. Open the [Firebase Console](https://console.firebase.google.com/).
2. Click **Add project** (or **Create a project**).
3. Name your project (e.g., `GuardianLink Remote Alarm`) and click **Continue**.
4. Choose whether to enable Google Analytics (optional, you can disable it for this test project) and click **Create project**. Wait for setup to finish.

### Step 2: Register the Android Client
1. In the Project Overview screen, click the **Android** icon (or go to Project settings -> Add app -> Android).
2. Enter the Package Name: **`com.example.remotealarm`** (Must match the name configured in `AndroidManifest.xml`).
3. (Optional) Provide an App Nickname (e.g., `Alarm Client`).
4. Click **Register app**.
5. Download the **`google-services.json`** file.
6. Copy `google-services.json` and paste it inside the `android-app/app/` folder of your project structure.
7. Click **Next** on the remaining steps and return to console.

### Step 3: Generate Backend Service Account Key (OAuth2 FCM v1)
Since the legacy FCM Server Keys are deprecated, the backend uses the secure FCM HTTP v1 API which requires a Service Account JSON certificate.
1. In the Firebase Console, click the **Gear Icon (Project Settings)** in the top-left menu.
2. Select **Service accounts** tab.
3. Click the **Generate new private key** button at the bottom.
4. Click **Generate key** to download the configuration `.json` file.
5. Save this file to the `backend/` folder and name it **`firebase-adminsdk.json`** (Matches the default path in your backend `.env` file).

---

## 2. Backend Setup & Run

The backend is built with Node.js and Express. It acts as the API endpoint for register tokens, coordinates logging, and triggering commands.

### Prerequisites
- [Node.js](https://nodejs.org/) (version 16 or newer) installed.

### Setup Steps
1. Navigate to the `backend/` folder in your terminal:
   ```bash
   cd backend
   ```
2. Install the node dependencies:
   ```bash
   npm install
   ```
3. Copy the configuration template to create your `.env` file:
   ```bash
   cp .env.example .env
   ```
4. Configure the `.env` variables:
   - `PORT`: Server port (default is `3000`).
   - `ADMIN_TOKEN`: A custom password/token (default: `super_secret_admin_token_123`) which authorizes admin panel trigger operations.
   - `FIREBASE_SERVICE_ACCOUNT`: Path to the downloaded service account key (e.g., `./firebase-adminsdk.json`).

> [!NOTE]
> **FCM Mock / Demo Mode:** If the server doesn't find a service account certificate at start-up, it will automatically switch to **Mock Mode**. In Mock Mode, you can test registering device tokens, saving locations, and operating the Admin Panel UI without sending physical push notifications.

5. Start the backend server:
   ```bash
   npm start
   ```
   You should see output indicating:
   `🔊 Remote Alarm Backend running at http://localhost:3000`

---

## 3. Frontend Admin Dashboard Setup

The Web Admin Panel is static, loading from the `frontend/` folder.
- **Local Access:** The backend is configured to host the frontend automatically! Simply open a web browser and navigate to **`http://localhost:3000`**.
- **Alternative Access:** You can open `frontend/index.html` directly in a browser.

### Configuring the Dashboard
1. When you first open the dashboard, the connection status badge in the header might indicate **Disconnected** (if loading via file:// rather than the server).
2. Click the **Gear icon (Settings)** in the top right.
3. Enter your **Server API URL** (e.g., `http://localhost:3000` or your deployed URL).
4. Enter the **Admin Security Token** configured in the backend `.env` (default: `super_secret_admin_token_123`).
5. Click **Save Configurations**. The header badge should immediately change to green: **Server Connected**.

---

## 4. Android App Setup

### Importing & Compiling
1. Launch **Android Studio**.
2. Click **File -> Open** and select the `android-app/` directory.
3. Android Studio will resolve and download the Gradle dependencies automatically.
4. Ensure the `google-services.json` you downloaded is placed in the `android-app/app/` directory.
5. Compile and run the project on a physical Android device or emulator with Google Play Services.

### Permissions Configuration
To override silent modes and access GPS tracking, you must approve the following prompts inside the app:
1. **Notifications Permission (Android 13+):** Approved to display FCM updates.
2. **Location Permission:** Approved to send the GPS coordinates back during alarms.
3. **Camera Permission:** Approved to enable camera flash blinking.
4. **Do Not Disturb (DND) Policy:**
   - Tap the **Grant Do Not Disturb Policy** button in the app.
   - You will be redirected to the Android system settings.
   - Find **Remote Alarm** in the list and toggle **Allow Do Not Disturb Access** to **ON**. This allows the audio volume to override system silences.

### Server Connection on Mobile
1. Enter your server's endpoint URL in the input field.
   - **Emulator Testing:** If testing on the Android emulator and the backend is running on your local machine, use **`http://10.0.2.2:3000`**.
   - **Physical Device Testing:** Ensure both your phone and PC are connected to the same Wi-Fi network, and use your PC's local IP (e.g., `http://192.168.1.45:3000`).
2. Tap **Save & Connect**.
3. Once connected, your FCM Device Token will print inside the text view, and the app will automatically upload it to the backend server.

---

## 5. End-to-End Verification & Testing

### Test 1: Local Diagnostics (Testing Speaker & Flash)
1. In the Android App, scroll down to the **Local Diagnostic Panel**.
2. Tap **Test Siren**.
   - Your phone should play a loud alarm sound at maximum volume.
   - The camera flash on the back of the phone will begin blinking rapidly.
3. Tap **Stop Siren** to turn it off.

### Test 2: Remote Triggering via Web Dashboard
1. Open the Web Admin Panel at `http://localhost:3000`.
2. Verify that **Device Status** shows your device name (e.g., Pixel 6) and the registered FCM token.
3. Click the **RING MY PHONE** button in the control panel.
   - The server receives the command, validates your admin token, and sends a high-priority data push to FCM.
   - The device receives the push notification in the background, forces volume to maximum, starts the foreground alarm service, activates flashlight loops, captures the current location, and posts coordinates back to the API.
   - The Web Admin Dashboard automatically updates: the central status indicators pulse red, details for location coordinates populate, and a map pins the exact tracking spot.
4. Click **Stop Siren** on the dashboard (or tap **DISMISS SIREN** in the phone's notification drawer) to turn it off.

---

## 6. Build APK in the Cloud (Without Android Studio)

If you do not have Java, Gradle, or Android Studio installed on your computer, you can build the APK file using the automated GitHub Action workflow provided in this repository.

### Steps to Build Online:
1. **Create a GitHub Repository**:
   - Go to [GitHub](https://github.com/) and create a new public or private repository.
2. **Push the Code to GitHub**:
   - Upload the entire `remote-phone-alarm` directory (including the `.github` folder) to your repository.
   - Or run the following commands in your project root folder:
     ```bash
     git init
     git add .
     git commit -m "Initialize Remote Alarm Project"
     git branch -M main
     git remote add origin YOUR_REPOSITORY_URL
     git push -u origin main
     ```
3. **Trigger the Build**:
   - Once pushed, navigate to the **Actions** tab on your GitHub repository.
   - Under Workflows, select **Build Android APK**.
   - If it didn't trigger automatically on push, click **Run workflow** to start the builder.
4. **Download the APK**:
   - Wait for the build job to complete (typically takes 2-3 minutes).
   - Click on the completed build run.
   - Scroll down to the **Artifacts** section at the bottom of the page and click **remote-alarm-app-debug** to download your ready-to-use APK!
5. **Install on Phone**:
   - Transfer the downloaded `.zip` file, extract the `app-debug.apk` inside it, send it to your phone, and install.

