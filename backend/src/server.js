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

// Active MJPEG stream connections
const cameraStreams = {};
const screenStreams = {};

// Middleware
app.use(cors());
app.use(express.json());

// Ensure uploads folder exists
const UPLOADS_DIR = path.join(__dirname, '..', 'uploads');
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}
const RECORDINGS_DIR = path.join(UPLOADS_DIR, 'recordings');
if (!fs.existsSync(RECORDINGS_DIR)) {
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
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
      deviceInfo: null,
      cameraSource: 'back',
      cameraActive: false,
      screenShareActive: false,
      recordings: []
    };
  } else {
    if (!db[normalizedEmail].cameraSource) {
      db[normalizedEmail].cameraSource = 'back';
    }
    if (db[normalizedEmail].cameraActive === undefined) {
      db[normalizedEmail].cameraActive = false;
    }
    if (db[normalizedEmail].screenShareActive === undefined) {
      db[normalizedEmail].screenShareActive = false;
    }
    if (!db[normalizedEmail].recordings) {
      db[normalizedEmail].recordings = [];
    }
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
  const { email, localIp } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  if (localIp && localIp !== '0.0.0.0') {
    userState.deviceInfo = userState.deviceInfo || {};
    userState.deviceInfo.localIp = localIp;
    writeDb(db);
  }

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

// Helper to send camera command to device via FCM
async function triggerCameraControl(email, active, source = 'back') {
  const db = readDb();
  const userState = db[email];
  if (!userState || !userState.token) {
    console.warn(`⚠️ No registered token for ${email}, cannot send camera command.`);
    return;
  }

  console.log(`📱 Sending camera control to ${email}: active=${active}, source=${source}`);

  if (!isFirebaseInitialized) {
    return; // Mock mode
  }

  const message = {
    data: {
      command: active ? 'START_CAMERA' : 'STOP_CAMERA',
      cameraSource: source,
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
    console.log(`✅ FCM camera command sent to ${email}:`, response);
  } catch (error) {
    console.error(`❌ Failed to send FCM camera command to ${email}:`, error.message);
  }
}

// Helper to send screen command to device via FCM
async function triggerScreenControl(email, active) {
  const db = readDb();
  const userState = db[email];
  if (!userState || !userState.token) {
    console.warn(`⚠️ No registered token for ${email}, cannot send screen control command.`);
    return;
  }

  console.log(`📱 Sending screen control to ${email}: active=${active}`);

  if (!isFirebaseInitialized) {
    return; // Mock mode
  }

  const message = {
    data: {
      command: active ? 'START_SCREEN_SHARE' : 'STOP_SCREEN_SHARE',
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
    console.log(`✅ FCM screen command sent to ${email}:`, response);
  } catch (error) {
    console.error(`❌ Failed to send FCM screen command to ${email}:`, error.message);
  }
}

// 7. GET Live Camera MJPEG Stream
app.get('/api/camera/stream', (req, res) => {
  const { email, token } = req.query;
  if (!email) {
    return res.status(400).send('Email parameter is required');
  }

  if (token !== ADMIN_TOKEN) {
    return res.status(403).send('Forbidden: Invalid admin token');
  }

  const normalizedEmail = email.toLowerCase().trim();

  // Set boundary headers for MJPEG stream
  res.writeHead(200, {
    'Content-Type': 'multipart/x-mixed-replace; boundary=--frame',
    'Cache-Control': 'no-cache',
    'Connection': 'close',
    'Pragma': 'no-cache'
  });

  if (!cameraStreams[normalizedEmail]) {
    cameraStreams[normalizedEmail] = [];
  }

  cameraStreams[normalizedEmail].push(res);
  console.log(`🎥 Web client connected to camera stream for ${normalizedEmail}. Total: ${cameraStreams[normalizedEmail].length}`);

  req.on('close', () => {
    cameraStreams[normalizedEmail] = cameraStreams[normalizedEmail].filter(c => c !== res);
    console.log(`🎥 Web client disconnected from camera stream for ${normalizedEmail}. Remaining: ${cameraStreams[normalizedEmail].length}`);

    // Auto-stop camera on phone if no clients are viewing
    if (cameraStreams[normalizedEmail].length === 0) {
      const db = readDb();
      const state = getUserState(db, normalizedEmail);
      if (state.cameraActive) {
        state.cameraActive = false;
        writeDb(db);
        triggerCameraControl(normalizedEmail, false);
      }
    }
  });
});

// 8. POST Camera Frame Upload (called by Android App)
app.post('/api/camera/upload', express.raw({ type: 'image/jpeg', limit: '2mb' }), (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const clients = cameraStreams[normalizedEmail];

  if (clients && clients.length > 0) {
    clients.forEach(clientRes => {
      try {
        clientRes.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${req.body.length}\r\n\r\n`);
        clientRes.write(req.body);
        clientRes.write('\r\n');
      } catch (err) {
        console.error('Error piping frame to client:', err.message);
      }
    });
  }

  res.json({ success: true });
});

// 9. POST Camera Control (start/stop/switch)
app.post('/api/camera/control', authenticateAdmin, async (req, res) => {
  const { email, action, cameraSource } = req.body;
  if (!email || !action) {
    return res.status(400).json({ success: false, error: 'Email and Action are required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  if (cameraSource) {
    userState.cameraSource = cameraSource;
  }

  if (action === 'start') {
    userState.cameraActive = true;
    writeDb(db);
    triggerCameraControl(normalizedEmail, true, userState.cameraSource);
    return res.json({ success: true, message: 'Camera stream start sent' });
  } else if (action === 'stop') {
    userState.cameraActive = false;
    writeDb(db);
    triggerCameraControl(normalizedEmail, false);
    return res.json({ success: true, message: 'Camera stream stop sent' });
  } else if (action === 'switch') {
    userState.cameraActive = true;
    writeDb(db);
    triggerCameraControl(normalizedEmail, true, userState.cameraSource);
    return res.json({ success: true, message: `Camera switched to ${userState.cameraSource}` });
  }

  res.status(400).json({ success: false, error: 'Invalid action' });
});

// 10. GET Live Screen MJPEG Stream
app.get('/api/screen/stream', (req, res) => {
  const { email, token } = req.query;
  if (!email) {
    return res.status(400).send('Email parameter is required');
  }

  if (token !== ADMIN_TOKEN) {
    return res.status(403).send('Forbidden: Invalid admin token');
  }

  const normalizedEmail = email.toLowerCase().trim();

  res.writeHead(200, {
    'Content-Type': 'multipart/x-mixed-replace; boundary=--frame',
    'Cache-Control': 'no-cache',
    'Connection': 'close',
    'Pragma': 'no-cache'
  });

  if (!screenStreams[normalizedEmail]) {
    screenStreams[normalizedEmail] = [];
  }

  screenStreams[normalizedEmail].push(res);
  console.log(`🖥️ Web client connected to screen stream for ${normalizedEmail}. Total: ${screenStreams[normalizedEmail].length}`);

  req.on('close', () => {
    screenStreams[normalizedEmail] = screenStreams[normalizedEmail].filter(c => c !== res);
    console.log(`🖥️ Web client disconnected from screen stream for ${normalizedEmail}. Remaining: ${screenStreams[normalizedEmail].length}`);

    // Auto-stop screen share on phone if no clients are viewing
    if (screenStreams[normalizedEmail].length === 0) {
      const db = readDb();
      const state = getUserState(db, normalizedEmail);
      if (state.screenShareActive) {
        state.screenShareActive = false;
        writeDb(db);
        triggerScreenControl(normalizedEmail, false);
      }
    }
  });
});

// 11. POST Screen Frame Upload (called by Android App)
app.post('/api/screen/upload', express.raw({ type: 'image/jpeg', limit: '2mb' }), (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const clients = screenStreams[normalizedEmail];

  if (clients && clients.length > 0) {
    clients.forEach(clientRes => {
      try {
        clientRes.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${req.body.length}\r\n\r\n`);
        clientRes.write(req.body);
        clientRes.write('\r\n');
      } catch (err) {
        console.error('Error piping screen frame to client:', err.message);
      }
    });
  }

  res.json({ success: true });
});

// 12. POST Screen Control (start/stop)
app.post('/api/screen/control', authenticateAdmin, async (req, res) => {
  const { email, action } = req.body;
  if (!email || !action) {
    return res.status(400).json({ success: false, error: 'Email and Action are required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  if (action === 'start') {
    userState.screenShareActive = true;
    writeDb(db);
    triggerScreenControl(normalizedEmail, true);
    return res.json({ success: true, message: 'Screen share start sent' });
  } else if (action === 'stop') {
    userState.screenShareActive = false;
    writeDb(db);
    triggerScreenControl(normalizedEmail, false);
    return res.json({ success: true, message: 'Screen share stop sent' });
  }

  res.status(400).json({ success: false, error: 'Invalid action' });
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'ok', firebase: isFirebaseInitialized ? 'connected' : 'mocked' });
});

// Crash logging endpoint
app.post('/api/log', (req, res) => {
  const { email, log } = req.body;
  console.log(`[ANDROID CRASH LOG] [${email}]`, log);
  const logFile = path.join(__dirname, '..', 'crash_logs.txt');
  fs.appendFileSync(logFile, `[${new Date().toISOString()}] [${email}]\n${log}\n\n`, 'utf8');
  res.json({ success: true });
});

// View crash logs
app.get('/api/logs', (req, res) => {
  const logFile = path.join(__dirname, '..', 'crash_logs.txt');
  if (fs.existsSync(logFile)) {
    res.type('text/plain').send(fs.readFileSync(logFile, 'utf8'));
  } else {
    res.send('No crash logs yet.');
  }
});

// Call Recording upload endpoint
app.post('/api/recordings/upload', express.raw({ type: 'audio/*', limit: '20mb' }), (req, res) => {
  const { email, number, timestamp } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const callerNumber = number || 'Unknown';
  const time = timestamp || new Date().toISOString();

  const safeEmail = normalizedEmail.replace(/[^a-z0-9]/g, '_');
  const safeTime = time.replace(/[^a-z0-9]/gi, '_');
  const safeNumber = callerNumber.replace(/[^a-z0-9+]/gi, '_');
  const filename = `${safeEmail}_${safeTime}_${safeNumber}.mp4`;
  const filePath = path.join(RECORDINGS_DIR, filename);

  try {
    fs.writeFileSync(filePath, req.body);
    const fileUrl = `/uploads/recordings/${filename}`;
    const fileSize = req.body.length;

    const db = readDb();
    const userState = getUserState(db, normalizedEmail);

    if (!userState.recordings) {
      userState.recordings = [];
    }

    const newRecording = {
      id: `${safeTime}_${Math.floor(Math.random() * 1000)}`,
      filename: filename,
      number: callerNumber,
      timestamp: time,
      url: fileUrl,
      size: fileSize
    };

    userState.recordings.unshift(newRecording);
    writeDb(db);

    console.log(`🎙️ Call recording uploaded for ${normalizedEmail}: ${callerNumber} (${fileSize} bytes)`);
    res.json({ success: true, recording: newRecording });
  } catch (err) {
    console.error('Failed to save uploaded call recording:', err);
    res.status(500).json({ success: false, error: 'Failed to save recording file' });
  }
});

// Fetch call recordings for specific email
app.get('/api/recordings', (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  res.json({ success: true, recordings: userState.recordings || [] });
});

// Delete a call recording
app.post('/api/recordings/delete', authenticateAdmin, (req, res) => {
  const { email, recordingId } = req.body;
  if (!email || !recordingId) {
    return res.status(400).json({ success: false, error: 'Email and recordingId are required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  if (!userState.recordings) {
    return res.status(404).json({ success: false, error: 'No recordings found' });
  }

  const recIndex = userState.recordings.findIndex(r => r.id === recordingId);
  if (recIndex === -1) {
    return res.status(404).json({ success: false, error: 'Recording not found' });
  }

  const rec = userState.recordings[recIndex];
  const filePath = path.join(RECORDINGS_DIR, rec.filename);

  try {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
  } catch (err) {
    console.error('Error deleting recording file:', err);
  }

  userState.recordings.splice(recIndex, 1);
  writeDb(db);

  console.log(`🎙️ Call recording deleted: ${rec.filename}`);
  res.json({ success: true, message: 'Recording deleted successfully' });
});


// Start Server
app.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`🔊 Remote Alarm Backend running at http://localhost:${PORT}`);
  console.log(`🔑 Admin Auth Token: ${ADMIN_TOKEN}`);
  console.log(`=======================================================`);
});
