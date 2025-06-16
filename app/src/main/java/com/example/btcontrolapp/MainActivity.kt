package com.example.btcontrolapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    // BLUETOOTH DEĞİŞKENLERİ
    // Bluetooth adaptörü - cihazın bluetooth özelliklerini yönetir
    private lateinit var bluetoothAdapter: BluetoothAdapter

    // Bluetooth bağlantı soketi - uzak cihazla iletişim kurar
    private var bluetoothSocket: BluetoothSocket? = null

    // Veri gönderme akışı - Arduino'ya komut göndermek için
    private var outputStream: OutputStream? = null

    // --- EKLENENLER ---
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null
    private var isReading = false
    // --- EKLENENLER SONU ---

    // Bluetooth bağlantı durumu kontrolü
    private var isConnected = false

    // Uygulama çalışma durumu kontrolü
    private var isRunning = false

    // UI ELEMANLARI - Kullanıcı arayüzü bileşenleri
    private lateinit var btnConnect: Button      // Bağlantı butonu
    private lateinit var btnStartStop: Button    // Başlat/Durdur butonu
    private lateinit var btnForward: Button      // İleri hareket butonu
    private lateinit var btnBackward: Button     // Geri hareket butonu
    private lateinit var btnLeft: Button         // Sol hareket butonu
    private lateinit var btnRight: Button        // Sağ hareket butonu
    private lateinit var tvDebug: TextView       // Debug mesajları gösterme alanı

    // Bluetooth UUID - Arduino ile aynı UUID kullanılmalı (standart seri port UUID)
    private val UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Layout dosyasını bağla
        setContentView(R.layout.activity_main)

        // UI elemanlarını başlat
        initializeViews()

        // Cihazın varsayılan bluetooth adaptörünü al
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Buton tıklama olaylarını ayarla
        setupClickListeners()
    }

    // UI elemanlarını layout'tan bul ve değişkenlere ata
    private fun initializeViews() {
        btnConnect = findViewById(R.id.btnConnect)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnForward = findViewById(R.id.btnForward)
        btnBackward = findViewById(R.id.btnBackward)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        tvDebug = findViewById(R.id.tvDebug)
    }

    // Tüm butonlar için tıklama olaylarını ayarla
    private fun setupClickListeners() {
        // Bağlantı butonu
        btnConnect.setOnClickListener {
            if (!isConnected) {
                // Bağlı değilse izinleri kontrol et ve bağlan
                checkBluetoothPermissions()
            } else {
                // Bağlıysa bağlantıyı kes
                disconnectBluetooth()
            }
        }

        // Başlat/Durdur butonu
        btnStartStop.setOnClickListener {
            if (isConnected) {
                isRunning = !isRunning
                btnStartStop.text = if (isRunning) "Stop" else "Start"
                sendCommand(if (isRunning) "START" else "STOP")
            }
        }

        // İleri hareket butonu
        btnForward.setOnClickListener {
            // Sadece bağlıysa ve sistem çalışıyorsa komut gönder
            if (isConnected && isRunning) sendCommand("FORWARD")
        }

        // Geri hareket butonu
        btnBackward.setOnClickListener {
            if (isConnected && isRunning) sendCommand("BACKWARD")
        }

        // Sol hareket butonu
        btnLeft.setOnClickListener {
            if (isConnected && isRunning) sendCommand("LEFT")
        }

        // Sağ hareket butonu
        btnRight.setOnClickListener {
            if (isConnected && isRunning) sendCommand("RIGHT")
        }
    }

    // Bluetooth izinlerini kontrol et
    private fun checkBluetoothPermissions() {
        // Gerekli izinler listesi
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH,           // Temel bluetooth izni
            Manifest.permission.BLUETOOTH_ADMIN,     // Bluetooth yönetim izni
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,   // Konum izni (bluetooth tarama için)
            Manifest.permission.ACCESS_COARSE_LOCATION  // Yaklaşık konum izni
        )

        // Hangi izinlerin eksik olduğunu kontrol et
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        // Eksik izin varsa kullanıcıdan iste
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, 1)
        } else {
            // Tüm izinler varsa bluetooth'a bağlan
            connectToBluetooth()
        }
    }

    // Bluetooth bağlantısını kur
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToBluetooth() {

        // Bluetooth desteklenip desteklenmediğini kontrol et
        if (bluetoothAdapter == null) {
            addDebugMessage("Bluetooth desteklenmiyor!")
            return
        }

        // Bluetooth'un açık olup olmadığını kontrol et
        if (!bluetoothAdapter.isEnabled) {
            addDebugMessage("Bluetooth açık değil!")
            return
        }

        // Daha önce eşleştirilmiş cihazları al
        val pairedDevices = bluetoothAdapter.bondedDevices
        if (pairedDevices.isEmpty()) {
            addDebugMessage("Eşleşmiş cihaz bulunamadı!")
            return
        }

        // HC-05 veya HC-06 bluetooth modülünü bul
        val arduinoDevice = pairedDevices.find {
            it.name.contains("MAVERA")
        } ?: run {
            // Cihaz bulunamazsa hata mesajı ver ve çık
            addDebugMessage("Arduino cihazı bulunamadı!")
            return
        }

        try {
            // Bluetooth soketi oluştur
            bluetoothSocket = arduinoDevice.createRfcommSocketToServiceRecord(
                UUID.fromString(UUID_STRING)
            )
            // Bağlantıyı kur
            bluetoothSocket?.connect()
            // Veri gönderme akışını al
            outputStream = bluetoothSocket?.outputStream
            // Veri okuma akışını al
            inputStream = bluetoothSocket?.inputStream

            startReading()
            // Okuma işlemi için thread oluştur
            readThread = Thread {
                while (isReading) {
                    try {
                        val buffer = ByteArray(1024)
                        val bytesRead = inputStream?.read(buffer)
                        if (bytesRead != null && bytesRead > 0) {
                            val incoming = String(buffer, 0, bytesRead)
                            runOnUiThread {
                                addDebugMessage("LOG: $incoming")
                            }
                        }else
                            addDebugMessage("LOG: girmedi kral")
                    } catch (e: IOException) {
                        runOnUiThread {
                            addDebugMessage("Veri okuma hatası: ${e.message}")
                        }
                        break
                    }
                }
            }
            readThread?.start()

            // Bağlantı durumunu güncelle
            isConnected = true

            // UI'ı güncelle - bağlantı başarılı
            btnConnect.text = "Bağlantıyı Kes"
            btnStartStop.isEnabled = true    // Kontrol butonlarını aktifleştir
            btnForward.isEnabled = true
            btnBackward.isEnabled = true
            btnLeft.isEnabled = true
            btnRight.isEnabled = true

            addDebugMessage("Bluetooth bağlantısı başarılı!")
        } catch (e: IOException) {
            // Bağlantı hatası durumunda
            addDebugMessage("Bağlantı hatası: ${e.message}")
            disconnectBluetooth()
        }
    }

    // Bluetooth bağlantısını kes
    private fun disconnectBluetooth() {
        // --- EKLENENLER ---
        stopReading()
        // --- EKLENENLER SONU ---
        try {
            // Veri akışını kapat
            outputStream?.close()
            // Veri okuma akışını kapat
            inputStream?.close()
            // Bluetooth soketini kapat
            bluetoothSocket?.close()
        } catch (e: IOException) {
            addDebugMessage("Bağlantı kesme hatası: ${e.message}")
        } finally {
            // Değişkenleri sıfırla
            isConnected = false
            outputStream = null
            // --- EKLENENLER ---
            inputStream = null
            // --- EKLENENLER SONU ---
            bluetoothSocket = null

            // UI'ı güncelle - bağlantı kesildi
            btnConnect.text = "Bluetooth Bağlan"
            btnStartStop.isEnabled = false   // Kontrol butonlarını pasifleştir
            btnForward.isEnabled = false
            btnBackward.isEnabled = false
            btnLeft.isEnabled = false
            btnRight.isEnabled = false

            addDebugMessage("Bluetooth bağlantısı kesildi!")
        }
    }

    // Arduino'ya komut gönder
    private fun sendCommand(command: String) {
        try {
            // Komutu byte dizisine çevir ve satır sonu ekleyerek gönder
            outputStream?.write("$command\n".toByteArray())
            addDebugMessage("Gönderilen komut: $command")
        } catch (e: IOException) {
            // Gönderme hatası durumunda bağlantıyı kes
            addDebugMessage("Komut gönderme hatası: ${e.message}")
            disconnectBluetooth()
        }
    }

    // Debug mesajlarını ekrana yazdır
    private fun addDebugMessage(message: String) {
        runOnUiThread {
            // Mevcut metni al ve yeni mesajı ekle
            val currentText = tvDebug.text.toString()
            val newText = if (currentText.isEmpty()) message else "$currentText\n$message"
            
            // Metni güncelle
            tvDebug.text = newText
            
            // Otomatik scroll
            val scrollView = tvDebug.parent as ScrollView
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    // Activity yok edildiğinde çalışan fonksiyon
    override fun onDestroy() {
        super.onDestroy()
        // Bluetooth bağlantısını temizle
        disconnectBluetooth()
    }

    // --- EKLENENLER ---
    private fun startReading() {
        isReading = true
        readThread = Thread {
            val buffer = ByteArray(1024)
            while (isReading) {
                try {
                    val bytes = inputStream?.read(buffer)
                    if (bytes != null && bytes > 0) {
                        val incoming = String(buffer, 0, bytes)
                        runOnUiThread {
                            addDebugMessage("LOG: $incoming")
                        }
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        addDebugMessage("Veri okuma hatası: ${e.message}")
                    }
                    break
                }
            }
        }
        readThread?.start()
    }

    private fun stopReading() {
        isReading = false
        readThread?.interrupt()
        readThread = null
    }
    // --- EKLENENLER SONU ---
}