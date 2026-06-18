// State Configuration
let config = {
  serverUrl: localStorage.getItem('server_url') || window.location.origin || 'http://localhost:3000',
  adminToken: localStorage.getItem('admin_token') || 'Aryanayush@1',
  adminEmail: localStorage.getItem('admin_email') || 'user@example.com',
  sound: localStorage.getItem('selected_sound') || 'default',
  customSoundUrl: localStorage.getItem('custom_sound_url') || ''
};

// If loaded locally via file://, fallback to default port 3000
if (config.serverUrl.startsWith('file://')) {
  config.serverUrl = 'http://localhost:3000';
}

// Leaflet Map State
let map = null;
let mapMarker = null;
let pollingInterval = null;
let isMapInitialized = false;

// DOM Elements
const serverIndicator = document.getElementById('server-indicator');
const serverStatusText = document.getElementById('server-status-text');
const firebaseStatusBadge = document.getElementById('firebase-status-badge');
const ringBtn = document.getElementById('ring-btn');
const stopBtn = document.getElementById('stop-btn');
const alarmStateText = document.getElementById('alarm-state-text');
const actionStatusMsg = document.getElementById('action-status-msg');

const deviceModel = document.getElementById('device-model');
const deviceSdk = document.getElementById('device-sdk');
const deviceEmail = document.getElementById('device-email');
const deviceLastUpdate = document.getElementById('device-last-update');

const gpsAccuracyBadge = document.getElementById('gps-accuracy-badge');
const latVal = document.getElementById('lat-val');
const lngVal = document.getElementById('lng-val');
const googleMapsBtn = document.getElementById('google-maps-btn');

// Camera DOM Elements
const cameraIndicator = document.getElementById('media-indicator');
const cameraStatusText = document.getElementById('media-status-text');
const cameraPlaceholder = document.getElementById('camera-placeholder');
const cameraVideoFrame = document.getElementById('camera-video-frame');
const cameraLoader = document.getElementById('camera-loader');
const toggleCameraBtn = document.getElementById('toggle-camera-btn');
const frontCamBtn = document.getElementById('front-cam-btn');
const backCamBtn = document.getElementById('back-cam-btn');

// Screen DOM Elements
const tabCameraBtn = document.getElementById('tab-camera-btn');
const tabScreenBtn = document.getElementById('tab-screen-btn');
const cameraPanel = document.getElementById('camera-panel');
const screenPanel = document.getElementById('screen-panel');
const screenPlaceholder = document.getElementById('screen-placeholder');
const screenVideoFrame = document.getElementById('screen-video-frame');
const screenLoader = document.getElementById('screen-loader');
const toggleScreenBtn = document.getElementById('toggle-screen-btn');

// Modal Elements
const settingsModal = document.getElementById('settings-modal');
const openSettingsBtn = document.getElementById('open-settings-btn');
const closeSettingsBtn = document.getElementById('close-settings-btn');
const settingsForm = document.getElementById('settings-form');
const serverUrlInput = document.getElementById('server-url-input');
const adminTokenInput = document.getElementById('admin-token-input');
const adminEmailInput = document.getElementById('admin-email-input');
const customFileGroup = document.getElementById('custom-file-group');
const customSoundFile = document.getElementById('custom-sound-file');
const uploadStatus = document.getElementById('upload-status');

// Initialize application
document.addEventListener('DOMContentLoaded', () => {
  // Populate settings form inputs with current config
  serverUrlInput.value = config.serverUrl;
  adminTokenInput.value = config.adminToken;
  adminEmailInput.value = config.adminEmail;
  const soundSelect = document.getElementById('sound-select');
  if (soundSelect) {
    if (config.sound.startsWith('http://') || config.sound.startsWith('https://') || config.sound === 'custom') {
      soundSelect.value = 'custom';
      customFileGroup.style.display = 'block';
      if (config.customSoundUrl) {
        uploadStatus.textContent = `Active file: ${config.customSoundUrl.split('/').pop()}`;
      }
    } else {
      soundSelect.value = config.sound;
      customFileGroup.style.display = 'none';
    }

    soundSelect.addEventListener('change', (e) => {
      if (e.target.value === 'custom') {
        customFileGroup.style.display = 'block';
      } else {
        customFileGroup.style.display = 'none';
      }
    });
  }

  // Event Listeners
  openSettingsBtn.addEventListener('click', openModal);
  closeSettingsBtn.addEventListener('click', closeModal);
  settingsForm.addEventListener('submit', saveSettings);
  ringBtn.addEventListener('click', triggerAlarm);
  stopBtn.addEventListener('click', stopAlarm);
  toggleCameraBtn.addEventListener('click', toggleCamera);
  frontCamBtn.addEventListener('click', () => setCameraSource('front'));
  backCamBtn.addEventListener('click', () => setCameraSource('back'));
  tabCameraBtn.addEventListener('click', () => switchMediaTab('camera'));
  tabScreenBtn.addEventListener('click', () => switchMediaTab('screen'));
  toggleScreenBtn.addEventListener('click', toggleScreenShare);

  // Close modal when clicking outside content
  window.addEventListener('click', (e) => {
    if (e.target === settingsModal) closeModal();
  });

  // Start polling backend for status updates
  checkStatus();
  pollingInterval = setInterval(checkStatus, 3000);
});

// Modal Logic
function openModal() {
  serverUrlInput.value = config.serverUrl;
  adminTokenInput.value = config.adminToken;
  adminEmailInput.value = config.adminEmail;
  const soundSelect = document.getElementById('sound-select');
  if (soundSelect) {
    if (config.sound.startsWith('http://') || config.sound.startsWith('https://') || config.sound === 'custom') {
      soundSelect.value = 'custom';
      customFileGroup.style.display = 'block';
      if (config.customSoundUrl) {
        uploadStatus.textContent = `Active file: ${config.customSoundUrl.split('/').pop()}`;
      }
    } else {
      soundSelect.value = config.sound;
      customFileGroup.style.display = 'none';
    }
  }
  settingsModal.classList.add('active');
}

function closeModal() {
  settingsModal.classList.remove('active');
}

async function saveSettings(e) {
  e.preventDefault();
  const targetServerUrl = serverUrlInput.value.trim().replace(/\/$/, ""); // Remove trailing slash
  const targetAdminToken = adminTokenInput.value.trim();
  const targetAdminEmail = adminEmailInput.value.trim().toLowerCase();
  const soundSelect = document.getElementById('sound-select');
  let selectedSound = soundSelect ? soundSelect.value : 'default';

  if (selectedSound === 'custom') {
    if (customSoundFile.files && customSoundFile.files[0]) {
      const file = customSoundFile.files[0];
      uploadStatus.textContent = 'Uploading sound file... ⏳';
      uploadStatus.style.color = '#f59e0b';
      
      try {
        const fileExt = file.name.split('.').pop();
        const response = await fetch(`${targetServerUrl}/api/upload-sound?email=${encodeURIComponent(targetAdminEmail)}&ext=${fileExt}`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${targetAdminToken}`,
            'Content-Type': file.type
          },
          body: file
        });
        
        const json = await response.json();
        if (!json.success) {
          throw new Error(json.error || 'Upload failed');
        }
        
        // Save the full URL of the uploaded custom sound
        config.customSoundUrl = targetServerUrl + json.url;
        localStorage.setItem('custom_sound_url', config.customSoundUrl);
        selectedSound = config.customSoundUrl;
        uploadStatus.textContent = `Uploaded successfully! 🎉 (${file.name})`;
        uploadStatus.style.color = '#10b981';
      } catch (err) {
        console.error('File upload error:', err);
        uploadStatus.textContent = `Upload failed: ${err.message} ❌`;
        uploadStatus.style.color = '#f43f5e';
        return; // Prevent settings from saving or closing modal on error
      }
    } else if (config.customSoundUrl) {
      // Use previously uploaded sound URL
      selectedSound = config.customSoundUrl;
    } else {
      uploadStatus.textContent = 'Please select a file to upload first! ❌';
      uploadStatus.style.color = '#f43f5e';
      return; // Prevent save
    }
  }

  config.serverUrl = targetServerUrl;
  config.adminToken = targetAdminToken;
  config.adminEmail = targetAdminEmail;
  config.sound = selectedSound;

  localStorage.setItem('server_url', config.serverUrl);
  localStorage.setItem('admin_token', config.adminToken);
  localStorage.setItem('admin_email', config.adminEmail);
  localStorage.setItem('selected_sound', config.sound);

  closeModal();
  showFeedback('Settings updated. Reconnecting...', 'success');
  
  // Re-run status check immediately
  checkStatus();
}

// Show temporary status messages in card footer
function showFeedback(message, type = 'info') {
  actionStatusMsg.textContent = message;
  actionStatusMsg.className = `status-msg ${type}`;
  
  // Reset message after 5 seconds
  setTimeout(() => {
    if (actionStatusMsg.textContent === message) {
      actionStatusMsg.textContent = 'System ready';
      actionStatusMsg.className = 'status-msg';
    }
  }, 5000);
}

// Format ISO strings to local readable strings
function formatTimestamp(isoString) {
  if (!isoString) return '-';
  try {
    const date = new Date(isoString);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + ' ' + date.toLocaleDateString();
  } catch (e) {
    return isoString;
  }
}

// Initialize Leaflet Map with Dark Theme
function initializeMap(lat, lng) {
  const mapElement = document.getElementById('map');
  
  // Remove placeholder contents
  mapElement.innerHTML = '';
  
  // Create map instance
  map = L.map('map').setView([lat, lng], 15);
  
  // Use CartoDB Dark Matter map tile layer for premium dark aesthetics
  L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
    subdomains: 'abcd',
    maxZoom: 20
  }).addTo(map);

  // Add marker
  mapMarker = L.marker([lat, lng]).addTo(map)
    .bindPopup('<b>Phone Location</b><br>Alarm triggered area.')
    .openPopup();

  isMapInitialized = true;
  console.log('🗺️ Leaflet Map initialized.');
}

// Update Map Position
function updateMap(lat, lng, accuracy) {
  if (!isMapInitialized) {
    initializeMap(lat, lng);
    return;
  }

  // Update map center and marker position
  const newLatLng = new L.LatLng(lat, lng);
  mapMarker.setLatLng(newLatLng);
  map.setView(newLatLng, map.getZoom());
  
  if (accuracy) {
    mapMarker.getPopup().setContent(`<b>Phone Location</b><br>Accuracy: ±${Math.round(accuracy)} meters`);
  }
}

// API Call: Fetch status from server
async function checkStatus() {
  if (!config.adminEmail) {
    setServerOffline('Email address not configured');
    return;
  }

  try {
    const response = await fetch(`${config.serverUrl}/api/status?email=${encodeURIComponent(config.adminEmail)}`);
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    
    const json = await response.json();
    if (json.success) {
      updateUI(json.data);
    } else {
      throw new Error(json.error || 'Unknown error');
    }
  } catch (error) {
    console.error('Failed to poll status:', error.message);
    setServerOffline(error.message);
  }
}

// Update UI elements based on state data
function updateUI(data) {
  // Update Server Connection Status
  serverIndicator.className = 'status-indicator online';
  serverStatusText.textContent = 'Server Connected';

  // Update Firebase Badge
  if (data.firebaseInitialized) {
    firebaseStatusBadge.textContent = 'FCM v1 Active';
    firebaseStatusBadge.className = 'badge firebase-active';
  } else {
    firebaseStatusBadge.textContent = 'FCM Mock Mode';
    firebaseStatusBadge.className = 'badge';
  }

  deviceEmail.textContent = config.adminEmail;

  // Update Device Info Card
  if (data.token) {
    ringBtn.classList.remove('disabled');
    
    if (data.deviceInfo) {
      deviceModel.textContent = data.deviceInfo.model || 'Unknown Android';
      deviceSdk.textContent = `API ${data.deviceInfo.sdkVersion || '-'}`;
    } else {
      deviceModel.textContent = 'Android Device';
      deviceSdk.textContent = 'API Level Unknown';
    }
  } else {
    ringBtn.classList.add('disabled');
    deviceModel.textContent = 'No Device Connected';
    deviceSdk.textContent = '-';
  }

  deviceLastUpdate.textContent = formatTimestamp(data.lastUpdated);

  // Update Alarm State & Ring Button
  if (data.alarmActive) {
    alarmStateText.textContent = 'RINGING';
    alarmStateText.className = 'state-active-label';
    ringBtn.classList.add('ringing');
    stopBtn.classList.remove('disabled');
    stopBtn.disabled = false;
  } else {
    alarmStateText.textContent = 'INACTIVE';
    alarmStateText.className = 'state-inactive';
    ringBtn.classList.remove('ringing');
    stopBtn.classList.add('disabled');
    stopBtn.disabled = true;
  }

  // Update Location Data
  if (data.location) {
    const lat = parseFloat(data.location.latitude);
    const lng = parseFloat(data.location.longitude);
    const accuracy = data.location.accuracy;

    latVal.textContent = lat.toFixed(6);
    lngVal.textContent = lng.toFixed(6);
    gpsAccuracyBadge.textContent = accuracy ? `±${Math.round(accuracy)}m` : 'GPS Active';
    
    // Enable Google Maps Link
    googleMapsBtn.href = `https://www.google.com/maps/search/?api=1&query=${lat},${lng}`;
    googleMapsBtn.classList.remove('disabled');

    // Update map marker
    updateMap(lat, lng, accuracy);
  } else {
    latVal.textContent = '-';
    lngVal.textContent = '-';
    gpsAccuracyBadge.textContent = '-';
    googleMapsBtn.href = '#';
    googleMapsBtn.classList.add('disabled');
  }

  // Update Media Status Header Badge
  const isMediaActive = data.cameraActive || data.screenShareActive;
  if (isMediaActive) {
    cameraIndicator.className = 'status-indicator online blinking';
    cameraStatusText.textContent = 'ACTIVE';
    cameraStatusText.className = 'state-active-label';
  } else {
    cameraIndicator.className = 'status-indicator offline';
    cameraStatusText.textContent = 'OFFLINE';
    cameraStatusText.className = 'state-inactive';
  }

  // Update Media UI availability
  if (data.token) {
    toggleCameraBtn.classList.remove('disabled');
    frontCamBtn.classList.remove('disabled');
    backCamBtn.classList.remove('disabled');
    toggleScreenBtn.classList.remove('disabled');
  } else {
    toggleCameraBtn.classList.add('disabled');
    frontCamBtn.classList.add('disabled');
    backCamBtn.classList.add('disabled');
    toggleScreenBtn.classList.add('disabled');
  }

  // Set active class on active camera source
  if (data.cameraSource === 'front') {
    frontCamBtn.classList.add('active');
    backCamBtn.classList.remove('active');
  } else {
    backCamBtn.classList.add('active');
    frontCamBtn.classList.remove('active');
  }

  // Update Camera Panel Stream
  if (data.cameraActive) {
    toggleCameraBtn.innerHTML = '<i class="fa-solid fa-video-slash"></i> Stop Feed';
    toggleCameraBtn.classList.add('active');

    const remoteStreamUrl = `${config.serverUrl}/api/camera/stream?email=${encodeURIComponent(config.adminEmail)}&token=${encodeURIComponent(config.adminToken)}`;
    const localIp = data.deviceInfo && data.deviceInfo.localIp;
    const localStreamUrl = localIp && localIp !== '0.0.0.0' ? `http://${localIp}:8085/stream` : null;
    const targetUrl = localStreamUrl || remoteStreamUrl;

    if (cameraVideoFrame.src !== targetUrl && cameraVideoFrame.src !== remoteStreamUrl) {
      cameraLoader.style.display = 'flex';
      cameraPlaceholder.style.display = 'none';
      cameraVideoFrame.style.display = 'none';
      cameraVideoFrame.src = targetUrl;

      // When image begins loading frames
      cameraVideoFrame.onload = () => {
        cameraLoader.style.display = 'none';
        cameraVideoFrame.style.display = 'block';
      };
      cameraVideoFrame.onerror = () => {
        if (localStreamUrl && cameraVideoFrame.src === localStreamUrl) {
          console.warn("Local Wi-Fi camera stream failed. Swapping to Render remote stream.");
          cameraVideoFrame.src = remoteStreamUrl;
        } else {
          cameraLoader.style.display = 'none';
          cameraPlaceholder.style.display = 'flex';
          cameraPlaceholder.querySelector('p').textContent = 'Failed to load camera stream.';
        }
      };
    }
  } else {
    toggleCameraBtn.innerHTML = '<i class="fa-solid fa-video"></i> Start Feed';
    toggleCameraBtn.classList.remove('active');

    cameraPlaceholder.style.display = 'flex';
    cameraPlaceholder.querySelector('p').textContent = 'Camera feed not started. Click "Start Feed" below.';
    cameraVideoFrame.style.display = 'none';
    cameraLoader.style.display = 'none';
    cameraVideoFrame.src = '';
  }

  // Update Screen Mirror Panel Stream
  if (data.screenShareActive) {
    toggleScreenBtn.innerHTML = '<i class="fa-solid fa-desktop"></i> Stop Mirroring';
    toggleScreenBtn.classList.add('active');

    const remoteScreenUrl = `${config.serverUrl}/api/screen/stream?email=${encodeURIComponent(config.adminEmail)}&token=${encodeURIComponent(config.adminToken)}`;
    const localIp = data.deviceInfo && data.deviceInfo.localIp;
    const localScreenUrl = localIp && localIp !== '0.0.0.0' ? `http://${localIp}:8085/screen` : null;
    const targetScreenUrl = localScreenUrl || remoteScreenUrl;

    if (screenVideoFrame.src !== targetScreenUrl && screenVideoFrame.src !== remoteScreenUrl) {
      screenLoader.style.display = 'flex';
      screenPlaceholder.style.display = 'none';
      screenVideoFrame.style.display = 'none';
      screenVideoFrame.src = targetScreenUrl;

      screenVideoFrame.onload = () => {
        screenLoader.style.display = 'none';
        screenVideoFrame.style.display = 'block';
      };
      screenVideoFrame.onerror = () => {
        if (localScreenUrl && screenVideoFrame.src === localScreenUrl) {
          console.warn("Local Wi-Fi screen stream failed. Swapping to Render remote stream.");
          screenVideoFrame.src = remoteScreenUrl;
        } else {
          screenLoader.style.display = 'none';
          screenPlaceholder.style.display = 'flex';
          screenPlaceholder.querySelector('p').textContent = 'Failed to load screen mirroring.';
        }
      };
    }
  } else {
    toggleScreenBtn.innerHTML = '<i class="fa-solid fa-desktop"></i> Start Mirroring';
    toggleScreenBtn.classList.remove('active');

    screenPlaceholder.style.display = 'flex';
    screenPlaceholder.querySelector('p').textContent = 'Screen mirroring not started. Click "Start Mirroring" below.';
    screenVideoFrame.style.display = 'none';
    screenLoader.style.display = 'none';
    screenVideoFrame.src = '';
  }
}

// Handle Connection Errors
function setServerOffline(errMessage) {
  serverIndicator.className = 'status-indicator offline';
  serverStatusText.textContent = 'Disconnected';
  
  ringBtn.classList.add('disabled');
  stopBtn.classList.add('disabled');
  stopBtn.disabled = true;
  
  alarmStateText.textContent = 'UNKNOWN';
  alarmStateText.className = 'state-inactive';
  ringBtn.classList.remove('ringing');

  toggleCameraBtn.classList.add('disabled');
  frontCamBtn.classList.add('disabled');
  backCamBtn.classList.add('disabled');
  toggleScreenBtn.classList.add('disabled');
  cameraIndicator.className = 'status-indicator offline';
  cameraStatusText.textContent = 'UNKNOWN';
  cameraStatusText.className = 'state-inactive';
  
  cameraVideoFrame.src = '';
  cameraVideoFrame.style.display = 'none';
  cameraLoader.style.display = 'none';
  cameraPlaceholder.style.display = 'flex';

  screenVideoFrame.src = '';
  screenVideoFrame.style.display = 'none';
  screenLoader.style.display = 'none';
  screenPlaceholder.style.display = 'flex';

  showFeedback(`Server error: ${errMessage}`, 'error');
}

// API Call: Trigger Alarm
async function triggerAlarm() {
  if (ringBtn.classList.contains('disabled')) {
    showFeedback('Cannot trigger: No device registered or server is offline.', 'error');
    return;
  }

  const selectedSound = config.sound || 'default';

  showFeedback('Sending ring request...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/trigger`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({ 
        email: config.adminEmail,
        sound: selectedSound 
      })
    });

    const json = await response.json();
    if (json.success) {
      if (json.mockMode) {
        showFeedback('Trigger sent (Demo Mode: Not routed via FCM)', 'success');
      } else {
        showFeedback('Phone is ringing!', 'success');
      }
      checkStatus(); // Force state check
    } else {
      showFeedback(`Failed: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}

// API Call: Stop Alarm
async function stopAlarm() {
  if (stopBtn.classList.contains('disabled')) {
    return;
  }

  showFeedback('Sending stop request...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/stop`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({ email: config.adminEmail })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback('Stop alarm sent!', 'success');
      checkStatus(); // Force state check
    } else {
      showFeedback(`Failed: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}

// Toggle Camera Stream
async function toggleCamera() {
  if (toggleCameraBtn.classList.contains('disabled')) return;

  const isActive = toggleCameraBtn.classList.contains('active');
  const action = isActive ? 'stop' : 'start';

  showFeedback(isActive ? 'Stopping stream...' : 'Requesting stream start...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/camera/control`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        action: action
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback(isActive ? 'Camera stream stopped!' : 'Camera stream requested!', 'success');
      checkStatus(); // Force poll
    } else {
      showFeedback(`Failed: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}

// Set camera source (front/back)
async function setCameraSource(source) {
  if (frontCamBtn.classList.contains('disabled')) return;

  showFeedback(`Switching to ${source} camera...`, 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/camera/control`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        action: 'switch',
        cameraSource: source
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback(`Switched to ${source} camera!`, 'success');
      checkStatus(); // Force poll
    } else {
      showFeedback(`Failed to switch camera: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}

// Switch Media tab (Camera Feed vs Screen Mirror)
function switchMediaTab(tab) {
  if (tab === 'camera') {
    tabCameraBtn.classList.add('active');
    tabScreenBtn.classList.remove('active');
    cameraPanel.style.display = 'block';
    screenPanel.style.display = 'none';
  } else if (tab === 'screen') {
    tabScreenBtn.classList.add('active');
    tabCameraBtn.classList.remove('active');
    screenPanel.style.display = 'block';
    cameraPanel.style.display = 'none';
  }
}

// Toggle Screen Share
async function toggleScreenShare() {
  if (toggleScreenBtn.classList.contains('disabled')) return;

  const isActive = toggleScreenBtn.classList.contains('active');
  const action = isActive ? 'stop' : 'start';

  showFeedback(isActive ? 'Stopping screen mirroring...' : 'Requesting screen mirroring... Allow on device.', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/screen/control`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        action: action
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback(isActive ? 'Screen mirror stopped!' : 'Screen mirror requested!', 'success');
      checkStatus(); // Force poll
    } else {
      showFeedback(`Failed: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}
