package com.lucas.trippermeteor;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayDeque;

/**
 * Conexão com o Tripper pod, do jeito do app oficial 12.10:
 * 1) o celular abre um servidor GATT com o mesmo serviço/característica (o pod escreve as respostas ali);
 * 2) conecta no pod como cliente, descobre o serviço e manda 21 00 (retomar) ou 21 01 (pedir PIN);
 * 3) depois do 21 00 escrito, ou do "PIN aceito", manda a hora;
 * 4) navegação: pacotes 10 11 ... reenviados a cada 1 s.
 */
@SuppressLint("MissingPermission")
public class Pod {

    public interface Listener {
        void onLog(String line, int kind); // 0 info, 1 tx, 2 rx, 3 erro
        void onState(int state);           // 0 desconectado, 1 procurando, 2 conectando, 3 conectado
        void onPinResult(boolean ok);
    }

    public static final int LOG_INFO = 0, LOG_TX = 1, LOG_RX = 2, LOG_ERR = 3;

    private final Context ctx;
    private final Listener l;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final BluetoothManager bm;
    private final BluetoothAdapter adapter;
    private final SharedPreferences prefs;

    private BluetoothGattServer server;
    private boolean serverReady = false;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private boolean userDisconnect = false;
    private int retries = 0;
    private long connectedAt = 0;
    private String deviceAddress;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private byte[] keepalive = null;
    private boolean keepaliveOn = true;

    /** null = automático (usa o que foi salvo), true = força retomar, false = força pedir PIN. */
    public Boolean forceKnown = null;
    /** Durante a navegação: tenta reconectar sem limite. */
    public boolean persistent = false;

    public Pod(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.l = l;
        this.bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm.getAdapter();
        this.prefs = ctx.getSharedPreferences("pod", Context.MODE_PRIVATE);
        this.deviceAddress = prefs.getString("last", null);
        h.postDelayed(tick, 1000);
    }

    private void log(String s, int k) { h.post(() -> l.onLog(s, k)); }
    private void state(int s) { h.post(() -> l.onState(s)); }

    public boolean isConnected() { return gatt != null && ch != null; }

    public boolean isKnown(String addr) { return addr != null && prefs.getBoolean("known_" + addr, false); }

    private void setKnown(String addr, boolean v) {
        if (addr != null) prefs.edit().putBoolean("known_" + addr, v).apply();
    }

    public void forgetPairing() {
        prefs.edit().clear().apply();
        log("pareamento salvo apagado", LOG_INFO);
    }

    // ---------- 1. servidor GATT no celular ----------

    public void openServer() {
        if (adapter == null || !adapter.isEnabled()) { log("Bluetooth desligado", LOG_ERR); return; }
        if (server != null) return;
        server = bm.openGattServer(ctx, serverCb);
        if (server == null) { log("não consegui abrir o servidor GATT", LOG_ERR); return; }
        // mesmas propriedades do app oficial: 5 = BROADCAST|WRITE_NO_RESPONSE, permissão 16 = WRITE
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(Proto.CHAR,
                BluetoothGattCharacteristic.PROPERTY_BROADCAST | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattService s = new BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        s.addCharacteristic(c);
        server.addService(s);
        log("abrindo servidor GATT no celular…", LOG_INFO);
    }

    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            serverReady = status == BluetoothGatt.GATT_SUCCESS;
            log(serverReady ? "servidor GATT pronto ✔" : "servidor GATT falhou (" + status + ")", serverReady ? LOG_INFO : LOG_ERR);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            log("servidor: " + device.getAddress() + (newState == BluetoothProfile.STATE_CONNECTED ? " conectou" : " saiu"), LOG_INFO);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic c,
                                                 boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && server != null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            final byte[] v = value == null ? new byte[0] : value.clone();
            h.post(() -> onPodMessage(v));
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, android.bluetooth.BluetoothGattDescriptor d,
                                             boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic c) {
            if (server != null) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
        }
    };

    private void onPodMessage(byte[] v) {
        String t = "";
        if (v.length > 0) {
            int b0 = v[0] & 0xFF;
            if (b0 == 0x20) {
                boolean ok = v.length > 1 && v[1] == 1;
                t = ok ? "PIN ACEITO ✔ " : "PIN RECUSADO ✖ ";
                if (ok) {
                    setKnown(deviceAddress, true);
                    send(Proto.time(), "hora");
                }
                final boolean fok = ok;
                l.onPinResult(fok);
            } else if (b0 == 0x03 && v.length > 2) {
                t = "firmware " + (v[1] & 0xFF) + "." + (v[2] & 0xFF) + " ";
            } else if (b0 == 0x30) {
                t = "número de série ";
            } else if (b0 == 0x10) {
                t = "ACK nav ";
            }
        }
        l.onLog("← " + t + Proto.hex(v), LOG_RX);
    }

    // ---------- 2. procurar e conectar ----------

    public void connect() {
        if (adapter == null || !adapter.isEnabled()) { log("liga o Bluetooth", LOG_ERR); return; }
        openServer();
        userDisconnect = false;
        retries = 0;
        whenServerReady(0);
    }

    // o pod procura o serviço no celular logo que conecta, então o servidor precisa estar no ar antes
    private void whenServerReady(int tries) {
        if (!serverReady && tries < 20) { h.postDelayed(() -> whenServerReady(tries + 1), 100); return; }
        if (!serverReady) log("servidor GATT não confirmou, tentando mesmo assim", LOG_ERR);
        if (deviceAddress != null) { connectTo(adapter.getRemoteDevice(deviceAddress)); return; }
        startScan();
    }

    private void startScan() {
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { log("scanner indisponível", LOG_ERR); return; }
        ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        scanner.startScan(null, st, scanCb);
        state(1);
        log("procurando o pod (liga a ignição)…", LOG_INFO);
        h.postDelayed(() -> {
            if (scanning) { stopScan(); log("pod não encontrado em 20 s", LOG_ERR); state(0); }
        }, 20000);
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult r) {
            if (!scanning) return;
            BluetoothDevice d = r.getDevice();
            String name = d.getName();
            ScanRecord rec = r.getScanRecord();
            if (name == null && rec != null) name = rec.getDeviceName();
            boolean hasSvc = false;
            if (rec != null && rec.getServiceUuids() != null) {
                for (ParcelUuid u : rec.getServiceUuids()) if (Proto.SERVICE.equals(u.getUuid())) hasSvc = true;
            }
            if (hasSvc || (name != null && name.startsWith("RE_"))) {
                stopScan();
                final String nm = name;
                h.post(() -> {
                    log("achei " + (nm == null ? "(sem nome)" : nm) + " · " + d.getAddress() + " · " + r.getRssi() + " dBm", LOG_INFO);
                    connectTo(d);
                });
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            log("busca falhou (" + errorCode + ")", LOG_ERR);
            state(0);
        }
    };

    private void connectTo(BluetoothDevice d) {
        deviceAddress = d.getAddress();
        prefs.edit().putString("last", deviceAddress).apply();
        state(2);
        log("conectando em " + deviceAddress + "…", LOG_INFO);
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
        gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    public void disconnect() {
        userDisconnect = true;
        stopScan();
        keepalive = null;
        if (gatt != null) gatt.disconnect();
        else state(0);
    }

    public void forgetDevice() { deviceAddress = null; prefs.edit().remove("last").apply(); }

    public void close() {
        disconnect();
        h.removeCallbacksAndMessages(null);
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
        if (server != null) { try { server.close(); } catch (Exception ignored) {} server = null; }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedAt = System.currentTimeMillis();
                log("conectado (status " + status + "), descobrindo serviços…", LOG_INFO);
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                long dt = System.currentTimeMillis() - connectedAt;
                h.post(() -> {
                    ch = null;
                    queue.clear();
                    writing = false;
                    try { g.close(); } catch (Exception ignored) {}
                    if (gatt == g) gatt = null;
                    l.onLog(String.format("pod desconectou (status %d, após %.1f s)", status, dt / 1000f), LOG_ERR);
                    l.onState(0);
                    if (!userDisconnect && (persistent || retries < 5) && deviceAddress != null) {
                        retries++;
                        l.onLog("reconectando (" + retries + (persistent ? "" : "/5") + ")…", LOG_INFO);
                        h.postDelayed(() -> { if (!userDisconnect && gatt == null) connectTo(adapter.getRemoteDevice(deviceAddress)); },
                                Math.min(10000, 1500L * Math.min(retries, 6)));
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService s = g.getService(Proto.SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(Proto.CHAR);
            if (c == null) { log("serviço do pod não encontrado", LOG_ERR); return; }
            g.setCharacteristicNotification(c, true);
            h.post(() -> {
                ch = c;
                l.onState(3);
                boolean known = forceKnown != null ? forceKnown : isKnown(deviceAddress);
                l.onLog(String.format("pronto em %d ms · %s", System.currentTimeMillis() - connectedAt,
                        known ? "retomando sessão" : "pedindo PIN"), LOG_INFO);
                send(Proto.handshake(known), known ? "retomar" : "pedir PIN");
                if (!known) l.onLog("digite o PIN que aparecer no pod e toque em Enviar PIN", LOG_INFO);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            final byte[] v = c.getValue() == null ? null : c.getValue().clone();
            h.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) l.onLog("escrita falhou (" + status + ")", LOG_ERR);
                // igual ao app oficial: depois que o "retomar" sai, manda a hora
                if (Proto.isResume(v)) { retries = 0; send(Proto.time(), "hora"); }
                writing = false;
                pump();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue() == null ? new byte[0] : c.getValue().clone();
            h.post(() -> onPodMessage(v));
        }
    };

    // ---------- 3. envio com fila ----------

    public void send(byte[] f, String label) {
        if (f == null) return;
        queue.add(f);
        if (label != null) l.onLog("→ " + label + "  " + Proto.hex(f), LOG_TX);
        pump();
    }

    private final Runnable writeTimeout = () -> { writing = false; pump(); };

    @SuppressWarnings("deprecation")
    private void pump() {
        if (writing || ch == null || gatt == null || queue.isEmpty()) return;
        byte[] f = queue.poll();
        ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        ch.setValue(f);
        writing = true;
        boolean ok = gatt.writeCharacteristic(ch);
        h.removeCallbacks(writeTimeout);
        if (!ok) {
            writing = false;
            queue.addFirst(f);
            h.postDelayed(this::pump, 60);
        } else {
            h.postDelayed(writeTimeout, 300);
        }
    }

    /** Pacote que fica sendo repetido a cada 1 s (como o app oficial faz durante a navegação). */
    public void setKeepalive(byte[] f) { keepalive = f; }
    public void setKeepaliveOn(boolean on) { keepaliveOn = on; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (keepaliveOn && keepalive != null && isConnected() && queue.isEmpty() && !writing) {
                queue.add(keepalive);
                pump();
            }
            h.postDelayed(this, 1000);
        }
    };

    public String address() { return deviceAddress; }
}
