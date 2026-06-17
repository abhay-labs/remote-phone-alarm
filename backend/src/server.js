const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 3000;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'super_secret_admin_token_123';
const STATE_FILE = path.join(__dirname, '..', 'state.json');

// Middleware
app.use(cors());
app.use(express.json());

// Serve static frontend files from parent directory's frontend folder
app.use(express.static(path.join(__dirname, '..', '..', 'frontend')));

// Helper to read state from JSON file
function readState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const data = fs.readFileSync(STATE_FILE, 'utf8');
      return JSON.parse(data);
    }
  } catch (err) {
    console.error('Error reading state file:', err);
  }
  return {
    token: null,
    alarmActive: false,
    location: null,
    lastUpdated: null,
    deviceInfo: null
  };
}

// Helper to write state to JSON file
function writeState(state) {
  try {
    state.lastUpdated = new Date().toISOString();
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), 'utf8');
  } catch (err) {
    console.error('Error writing state file:', err);
  }
}

// Initialize Firebase Admin SDK
let firebaseApp = null;
let isFirebaseInitialized = false;

const serviceAccountPath = process.env.FIREBASE_SERVICE_ACCOUNT;

if (serviceAccountPath) {
  const fullPath = path.resolve(serviceAccountPath);
  if (fs.existsSync(fullPath)) {
    try {
      const serviceAccount = require(fullPath);
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert(serviceAccount)
      });
      isFirebaseInitialized = true;
      console.log('✅ Firebase Admin SDK initialized successfully using service account:', fullPath);
    } catch (error) {
      console.error('❌ Failed to initialize Firebase Admin SDK with service account:', error.message);
    }
  } else {
    console.warn(`⚠️ Firebase service account file not found at: ${fullPath}`);
    console.warn('⚠️ Server running in FCM Mock/Demo mode. Notifications will not be sent to real devices.');
  }
} else {
  console.warn('⚠️ FIREBASE_SERVICE_ACCOUNT environment variable is not defined.');
  console.warn('⚠️ Server running in FCM Mock/Demo mode. Notifications will not be sent to real devices.');
}

// Security Middleware to verify admin token
function authenticateAdmin(req, res, next) {
  const authHeader = req.headers['authorization'];
  if (!authHeader) {
    return res.status(401).json({ success: false, error: 'Unauthorized: No token provided' });
  }

  const token = authHeader.startsWith('Bearer ') ? authHeader.substring(7) : authHeader;
  if (token !== ADMIN_TOKEN) {
    return res.status(403).json({ success: false, error: 'Forbidden: Invalid admin token' });
  }

  next();
}

// API Routes

// 1. Get system status
app.get('/api/status', (req, res) => {
  const state = readState();
  res.json({
    success: true,
    data: {
      ...state,
      firebaseInitialized: isFirebaseInitialized
    }
  });
});

// 2. Register FCM Device Token (called by Android app)
app.post('/api/register', (req, res) => {
  const { token, deviceInfo } = req.body;
  if (!token) {
    return res.status(400).json({ success: false, error: 'Device token is required' });
  }

  const state = readState();
  state.token = token;
  if (deviceInfo) {
    state.deviceInfo = deviceInfo;
  }
  writeState(state);

  console.log(`📱 Registered device token: ${token.substring(0, 20)}...`);
  res.json({ success: true, message: 'Device token registered successfully' });
});

// 3. Post device location (called by Android app when alarm triggers)
app.post('/api/location', (req, res) => {
  const { latitude, longitude, accuracy } = req.body;
  if (latitude === undefined || longitude === undefined) {
    return res.status(400).json({ success: false, error: 'Latitude and Longitude are required' });
  }

  const state = readState();
  state.location = {
    latitude,
    longitude,
    accuracy: accuracy || null,
    timestamp: new Date().toISOString()
  };
  writeState(state);

  console.log(`📍 Received location from device: Lat ${latitude}, Lng ${longitude}`);
  res.json({ success: true, message: 'Location updated successfully' });
});

// 4. Trigger Alarm (called by Web Admin Panel)
app.post('/api/trigger', authenticateAdmin, async (req, res) => {
  const state = readState();
  if (!state.token) {
    return res.status(400).json({ success: false, error: 'No device token registered yet. Open the Android app first.' });
  }

  state.alarmActive = true;
  writeState(state);

  console.log('🚀 Triggering alarm command on device...');

  if (!isFirebaseInitialized) {
    return res.json({
      success: true,
      mockMode: true,
      message: 'Alarm triggered in DEMO mode (Firebase not initialized).'
    });
  }

  // Construct high-priority FCM message (data payload only)
  const message = {
    data: {
      command: 'TRIGGER_ALARM',
      timestamp: new Date().toISOString()
    },
    token: state.token,
    android: {
      priority: 'high', // Delivers immediately and wakes up device
      ttl: 0            // Don't store in cache, deliver now or drop
    }
  };

  try {
    const response = await admin.messaging().send(message);
    console.log('✅ FCM message sent successfully:', response);
    res.json({ success: true, message: 'Alarm trigger command sent to device via FCM' });
  } catch (error) {
    console.error('❌ Failed to send FCM message:', error);
    res.status(500).json({ success: false, error: `FCM Error: ${error.message}` });
  }
});

// 5. Stop Alarm (called by Web Admin Panel)
app.post('/api/stop', authenticateAdmin, async (req, res) => {
  const state = readState();
  if (!state.token) {
    return res.status(400).json({ success: false, error: 'No device token registered' });
  }

  state.alarmActive = false;
  writeState(state);

  console.log('🛑 Sending stop alarm command to device...');

  if (!isFirebaseInitialized) {
    return res.json({
      success: true,
      mockMode: true,
      message: 'Alarm stop sent in DEMO mode (Firebase not initialized).'
    });
  }

  const message = {
    data: {
      command: 'STOP_ALARM',
      timestamp: new Date().toISOString()
    },
    token: state.token,
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  try {
    const response = await admin.messaging().send(message);
    console.log('✅ FCM stop message sent successfully:', response);
    res.json({ success: true, message: 'Alarm stop command sent to device via FCM' });
  } catch (error) {
    console.error('❌ Failed to send FCM stop message:', error);
    res.status(500).json({ success: false, error: `FCM Error: ${error.message}` });
  }
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'ok', firebase: isFirebaseInitialized ? 'connected' : 'mocked' });
});

// Start Server
app.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`🔊 Remote Alarm Backend running at http://localhost:${PORT}`);
  console.log(`🔑 Admin Auth Token: ${ADMIN_TOKEN}`);
  console.log(`=======================================================`);
});
