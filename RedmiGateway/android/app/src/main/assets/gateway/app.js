(() => {
  const $ = (id) => document.getElementById(id);
  const status = $('connection-status');
  const feedback = $('feedback');
  let socket = null;
  let authenticated = false;
  let currentToken = '';
  let currentCall = null;
  let reconnectTimer = null;
  let reconnectDelay = 2000;
  let keepConnecting = false;
  let pendingSms = null;
  const seenMessageIds = new Set();

  // Web Audio Ringtone & Duration Timer
  let audioCtx = null;
  let ringtoneInterval = null;
  let activeCallDurationTimer = null;
  let activeCallStart = null;

  const unlockAudioContext = () => {
    if (!audioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) audioCtx = new AudioContextClass();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
  };

  const playChimeTone = (freq, duration, delay) => {
    try {
      unlockAudioContext();
      if (!audioCtx) return;
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, audioCtx.currentTime + delay);
      gain.gain.setValueAtTime(0.001, audioCtx.currentTime + delay);
      gain.gain.exponentialRampToValueAtTime(0.3, audioCtx.currentTime + delay + 0.05);
      gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + delay + duration);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start(audioCtx.currentTime + delay);
      osc.stop(audioCtx.currentTime + delay + duration);
    } catch (e) {}
  };

  const startRingtone = () => {
    if (ringtoneInterval) return;
    const playPattern = () => {
      playChimeTone(880, 0.25, 0.0);
      playChimeTone(1174, 0.25, 0.15);
      playChimeTone(1318, 0.35, 0.30);
      playChimeTone(1760, 0.45, 0.45);
      if (navigator.vibrate) {
        try { navigator.vibrate([300, 150, 300, 150, 600]); } catch (e) {}
      }
    };
    playPattern();
    ringtoneInterval = setInterval(playPattern, 2800);
  };

  const stopRingtone = () => {
    if (ringtoneInterval) {
      clearInterval(ringtoneInterval);
      ringtoneInterval = null;
    }
  };

  const startCallTimer = () => {
    if (activeCallDurationTimer) return;
    activeCallStart = Date.now();
    activeCallDurationTimer = setInterval(() => {
      if (!currentCall || currentCall.state !== 'active') {
        stopCallTimer();
        return;
      }
      const sec = Math.floor((Date.now() - activeCallStart) / 1000);
      const m = String(Math.floor(sec / 60)).padStart(2, '0');
      const s = String(sec % 60).padStart(2, '0');
      $('call-state').textContent = `Görüşme sürüyor (${m}:${s})`;
    }, 1000);
  };

  const stopCallTimer = () => {
    if (activeCallDurationTimer) {
      clearInterval(activeCallDurationTimer);
      activeCallDurationTimer = null;
    }
  };

  const readSavedToken = () => { try { return localStorage.getItem('redmi_pairing_token') || ''; } catch { return ''; } };
  const saveToken = (token) => { try { localStorage.setItem('redmi_pairing_token', token); } catch {} };
  const forgetToken = () => { try { localStorage.removeItem('redmi_pairing_token'); } catch {} };

  const setStatus = (text, connected = false) => {
    status.textContent = text;
    status.classList.toggle('connected', connected);
  };

  const notice = (text) => { feedback.textContent = text; };

  const send = (payload) => {
    if (!authenticated || !socket || socket.readyState !== WebSocket.OPEN) {
      notice('Önce geçide bağlanın.');
      return false;
    }
    socket.send(JSON.stringify(payload));
    return true;
  };

  const updateCall = (call) => {
    currentCall = call && call.state !== 'idle' && call.state !== 'disconnected' ? call : null;
    const stateNames = {
      ringing: 'Gelen arama (Çalıyor)',
      dialing: 'Aranıyor...',
      connecting: 'Bağlanıyor...',
      active: 'Görüşme sürüyor',
      holding: 'Beklemede',
      disconnecting: 'Sonlandırılıyor'
    };

    $('call-state').textContent = currentCall ? (stateNames[currentCall.state] || 'Arama sürüyor') : 'Arama yok';
    $('call-number').textContent = currentCall?.number || '';
    $('incoming-actions').hidden = currentCall?.state !== 'ringing';
    $('active-actions').hidden = !currentCall || currentCall.state === 'ringing';
    $('dial-button').disabled = !!currentCall;

    if (currentCall?.state === 'ringing') {
      startRingtone();
      stopCallTimer();
    } else {
      stopRingtone();
      if (currentCall?.state === 'active') {
        startCallTimer();
      } else {
        stopCallTimer();
      }
    }
  };

  const addMessage = (number, body, timestamp, outgoing, id = null) => {
    if (id != null && seenMessageIds.has(id)) return;
    if (id != null) seenMessageIds.add(id);
    const item = document.createElement('li');
    if (outgoing) item.className = 'outgoing';
    const name = document.createElement('strong');
    name.textContent = outgoing ? `Alıcı: ${number}` : `Gönderen: ${number}`;
    const time = document.createElement('time');
    const date = new Date(timestamp);
    time.dateTime = date.toISOString();
    time.textContent = new Intl.DateTimeFormat('tr-TR', {hour: '2-digit', minute: '2-digit'}).format(date);
    const message = document.createElement('p');
    message.textContent = body;
    item.append(name, time, message);
    $('message-list').prepend(item);
    while ($('message-list').children.length > 50) $('message-list').lastChild.remove();
    $('messages-empty').hidden = true;
  };

  const showDashboard = (visible) => {
    $('dashboard').hidden = !visible;
    $('pairing-section').hidden = visible;
    $('connect-button').disabled = false;
  };

  const scheduleReconnect = () => {
    if (!keepConnecting || reconnectTimer) return;
    setStatus('Bağlantı kesildi · yeniden deneniyor');
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      reconnectDelay = Math.min(reconnectDelay * 2, 30000);
      connect();
    }, reconnectDelay);
  };

  const connect = () => {
    if (!currentToken) return;
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
    unlockAudioContext();
    setStatus('Bağlanıyor...');
    $('connect-button').disabled = true;
    const ws = new WebSocket(`ws://${location.hostname}:8787/`);
    socket = ws;

    ws.onmessage = (event) => {
      if (socket !== ws) return;
      let packet;
      try { packet = JSON.parse(event.data); } catch { return; }
      switch (packet.type) {
        case 'hello':
          ws.send(JSON.stringify({type: 'auth', token: currentToken}));
          break;
        case 'auth_result':
          if (packet.success) {
            authenticated = true;
            reconnectDelay = 2000;
            setStatus('Geçide Bağlı (Online)', true);
            showDashboard(true);
            notice('Bağlantı hazır.');
            send({type: 'get_state'});
            send({type: 'get_sms_history', afterId: 0});
          } else {
            keepConnecting = false;
            forgetToken();
            setStatus('Kod kabul edilmedi');
            notice('Geçit telefonunun ekranındaki eşleştirme kodunu kontrol edin.');
            ws.close();
          }
          break;
        case 'state':
          updateCall(packet.call);
          break;
        case 'call':
          updateCall(packet);
          if (packet.state === 'ringing') notice('Telefona gelen arama var. Ses geçit telefonunda kalır.');
          break;
        case 'sms_received':
          addMessage(packet.sender || 'Bilinmeyen', packet.message || '', packet.timestamp || Date.now(), false, packet.id ?? null);
          notice('Yeni SMS alındı.');
          break;
        case 'sms_history':
          for (const sms of packet.messages || []) {
            addMessage(sms.sender || 'Bilinmeyen', sms.message || '', sms.timestamp || Date.now(), false, sms.id ?? null);
          }
          if (packet.hasMore && Number.isSafeInteger(packet.lastId) && packet.lastId > 0) {
            send({type: 'get_sms_history', afterId: packet.lastId});
          }
          break;
        case 'sms_send_result':
          if (packet.success && pendingSms) {
            addMessage(pendingSms.number, pendingSms.body, Date.now(), true);
            $('sms-text').value = '';
            notice('SMS gönderim isteği kabul edildi; teslim onayı alınmadı.');
          } else {
            notice(`SMS gönderilemedi: ${packet.error || 'Bilinmeyen hata'}`);
          }
          pendingSms = null;
          $('sms-button').disabled = false;
          break;
        case 'ack':
          notice(packet.success ? 'İşlem iletildi.' : 'İşlem yapılamadı; arama durumu değişmiş olabilir.');
          if (!packet.success) send({type: 'get_state'});
          break;
        case 'error':
          notice(packet.message || 'Geçit hatası.');
          break;
      }
    };

    ws.onerror = () => { if (socket === ws) notice('Geçit telefonuna ulaşılamıyor. Wi‑Fi bağlantısını kontrol edin.'); };
    ws.onclose = () => {
      if (socket !== ws) return;
      authenticated = false;
      pendingSms = null;
      stopRingtone();
      stopCallTimer();
      $('sms-button').disabled = false;
      showDashboard(false);
      if (keepConnecting) scheduleReconnect();
      else setStatus('Bağlanılmadı');
    };
  };

  $('pairing-form').addEventListener('submit', (event) => {
    event.preventDefault();
    unlockAudioContext();
    currentToken = $('pairing-token').value.trim().toUpperCase();
    if ($('remember-token').checked) saveToken(currentToken);
    else forgetToken();
    keepConnecting = true;
    notice('');
    connect();
  });

  $('disconnect-button').addEventListener('click', () => {
    keepConnecting = false;
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
    socket?.close();
    showDashboard(false);
    setStatus('Bağlanılmadı');
  });

  $('answer-button').addEventListener('click', () => {
    stopRingtone();
    send({type: 'answer', callId: currentCall?.callId});
  });

  $('reject-button').addEventListener('click', () => {
    stopRingtone();
    send({type: 'reject', callId: currentCall?.callId});
  });

  $('hangup-button').addEventListener('click', () => {
    stopRingtone();
    stopCallTimer();
    send({type: 'hangup', callId: currentCall?.callId});
  });

  $('dial-form').addEventListener('submit', (event) => {
    event.preventDefault();
    unlockAudioContext();
    const number = $('dial-number').value.trim();
    if (!/^\+?[0-9]{3,15}$/.test(number)) { notice('Geçerli bir telefon numarası girin.'); return; }
    if (!confirm(`${number} aransın mı? Görüşme sesi geçit telefonunda kalacaktır.`)) return;
    if (send({type: 'dial', number})) $('dial-number').value = '';
  });

  $('sms-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const number = $('sms-number').value.trim();
    const body = $('sms-text').value.trim();
    if (!/^\+?[0-9]{3,15}$/.test(number) || !body) { notice('Alıcı numarasını ve mesajı kontrol edin.'); return; }
    pendingSms = {number, body};
    $('sms-button').disabled = true;
    if (!send({type: 'sms_send', recipient: number, message: body})) $('sms-button').disabled = false;
  });

  document.addEventListener('touchstart', unlockAudioContext, {once: true});
  document.addEventListener('click', unlockAudioContext, {once: true});

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && keepConnecting && (!socket || socket.readyState === WebSocket.CLOSED)) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
      connect();
    }
  });

  const saved = readSavedToken();
  if (saved) {
    $('pairing-token').value = saved;
    $('remember-token').checked = true;
    currentToken = saved;
    keepConnecting = true;
    connect();
  }
})();
