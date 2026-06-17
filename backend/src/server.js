const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 3000;
const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'Aryanayush@1';
const DB_FILE = path.join(__dirname, '..', 'db.json');

// Middleware
app.use(cors());
app.use(express.json());

// Ensure uploads folder exists
const UPLOADS_DIR = path.join(__dirname, '..', 'uploads');
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

// Serve static frontend files
app.use(express.static(path.join(__dirname, '..', '..', 'frontend')));
app.use('/uploads', express.static(UPLOADS_DIR));

// Helper to read database from JSON file
function readDb() {
  try {
    if (fs.existsSync(DB_FILE)) {
      const data = fs.readFileSync(DB_FILE, 'utf8');
      return JSON.parse(data);
    }
  } catch (err) {
    console.error('Error reading db file:', err);
  }
  return {};
}

// Helper to write database to JSON file
function writeDb(db) {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
  } catch (err) {
    console.error('Error writing db file:', err);
  }
}

// Get or initialize user state helper
function getUserState(db, email) {
  const normalizedEmail = email.toLowerCase().trim();
  if (!db[normalizedEmail]) {
    db[normalizedEmail] = {
      token: null,
      alarmActive: false,
      location: null,
      lastUpdated: null,
      deviceInfo: null
    };
  }
  return db[normalizedEmail];
}

// Initialize Firebase Admin SDK
let firebaseApp = null;
let isFirebaseInitialized = false;

// 1. First check if credentials are passed in env variable as a JSON string
const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
// 2. Fallback to file path
const serviceAccountPath = process.env.FIREBASE_SERVICE_ACCOUNT || './service-account.json';

if (serviceAccountJson) {
  try {
    const serviceAccount = JSON.parse(serviceAccountJson);
    firebaseApp = admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    isFirebaseInitialized = true;
    console.log('✅ Firebase Admin SDK initialized successfully using environment JSON.');
  } catch (error) {
    console.error('❌ Failed to initialize Firebase Admin SDK using environment JSON:', error.message);
  }
} else if (serviceAccountPath) {
  const fullPath = path.resolve(serviceAccountPath);
  if (fs.existsSync(fullPath)) {
    try {
      const serviceAccount = require(fullPath);
      firebaseApp = admin.initializeApp({
        credential: admin.credential.cert(serviceAccount)
      });
      isFirebaseInitialized = true;
      console.log('✅ Firebase Admin SDK initialized successfully using credentials file:', fullPath);
    } catch (error) {
      console.error('❌ Failed to initialize Firebase Admin with file:', error.message);
    }
  }
}

if (!isFirebaseInitialized) {
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

// 1. Get status for a specific email
app.get('/api/status', (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  res.json({
    success: true,
    data: {
      ...userState,
      firebaseInitialized: isFirebaseInitialized
    }
  });
});

// 2. Register FCM Device Token by Email (called by Android app)
app.post('/api/register', (req, res) => {
  const { email, token, deviceInfo } = req.body;
  if (!email || !token) {
    return res.status(400).json({ success: false, error: 'Email and Device token are required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  userState.token = token;
  userState.lastUpdated = new Date().toISOString();
  if (deviceInfo) {
    userState.deviceInfo = deviceInfo;
  }

  writeDb(db);
  console.log(`📱 Registered device for email: ${normalizedEmail}`);
  res.json({ success: true, message: 'Device token registered successfully under email' });
});

// 3. Post device location (called by Android app when alarm triggers)
app.post('/api/location', (req, res) => {
  const { email, latitude, longitude, accuracy } = req.body;
  if (!email || latitude === undefined || longitude === undefined) {
    return res.status(400).json({ success: false, error: 'Email, Latitude and Longitude are required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  userState.location = {
    latitude,
    longitude,
    accuracy: accuracy || null,
    timestamp: new Date().toISOString()
  };
  userState.lastUpdated = new Date().toISOString();

  writeDb(db);
  console.log(`📍 Received location for email ${normalizedEmail}: Lat ${latitude}, Lng ${longitude}`);
  res.json({ success: true, message: 'Location updated successfully' });
});

// 4. Trigger Alarm by Email (called by Web Admin Panel)
app.post('/api/trigger', authenticateAdmin, async (req, res) => {
  const { email, sound } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email is required to trigger alarm' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = db[normalizedEmail];

  if (!userState || !userState.token) {
    return res.status(400).json({ success: false, error: `No device registered for email: ${normalizedEmail}. Open app on phone first.` });
  }

  userState.alarmActive = true;
  userState.alarmSound = sound || 'default';
  userState.lastUpdated = new Date().toISOString();
  writeDb(db);

  console.log(`🚀 Triggering alarm command for email: ${normalizedEmail} with sound ${sound}...`);

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
      sound: sound || 'default',
      timestamp: new Date().toISOString()
    },
    token: userState.token,
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  try {
    const response = await admin.messaging().send(message);
    console.log(`✅ FCM message sent to ${normalizedEmail}:`, response);
    res.json({ success: true, message: 'Alarm trigger command sent to device via FCM' });
  } catch (error) {
    console.error(`❌ Failed to send FCM message to ${normalizedEmail}:`, error);
    res.status(500).json({ success: false, error: `FCM Error: ${error.message}` });
  }
});

// 5. Stop Alarm by Email (called by Web Admin Panel)
app.post('/api/stop', authenticateAdmin, async (req, res) => {
  const { email } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email is required to stop alarm' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = db[normalizedEmail];

  if (!userState || !userState.token) {
    return res.status(400).json({ success: false, error: `No device registered for email: ${normalizedEmail}` });
  }

  userState.alarmActive = false;
  userState.lastUpdated = new Date().toISOString();
  writeDb(db);

  console.log(`🛑 Sending stop alarm command for email: ${normalizedEmail}...`);

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
    token: userState.token,
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  try {
    const response = await admin.messaging().send(message);
    console.log(`✅ FCM stop message sent to ${normalizedEmail}:`, response);
    res.json({ success: true, message: 'Alarm stop command sent to device via FCM' });
  } catch (error) {
    console.error(`❌ Failed to send FCM stop message to ${normalizedEmail}:`, error);
    res.status(500).json({ success: false, error: `FCM Error: ${error.message}` });
  }
});

// 6. Upload Custom Alarm Sound (called by Web Admin Panel)
app.post('/api/upload-sound', authenticateAdmin, express.raw({ type: 'audio/*', limit: '10mb' }), (req, res) => {
  const { email, ext } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email query parameter is required' });
  }

  const fileExt = ext || 'mp3';
  const safeEmail = email.toLowerCase().replace(/[^a-z0-9]/g, '_');
  const filename = `${safeEmail}_custom.${fileExt}`;
  const filePath = path.join(UPLOADS_DIR, filename);

  try {
    fs.writeFileSync(filePath, req.body);
    const fileUrl = `/uploads/${filename}`;
    console.log(`🎵 Custom sound uploaded for ${email} saved as ${filename}`);
    res.json({ success: true, url: fileUrl });
  } catch (err) {
    console.error('Failed to save uploaded audio file:', err);
    res.status(500).json({ success: false, error: 'Failed to save uploaded audio file' });
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
