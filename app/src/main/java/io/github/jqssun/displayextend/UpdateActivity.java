package io.github.jqssun.displayextend;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Opt-in, GitHub Releases based updater. Never silently installs APKs.
 * Only a newer APK signed with our installed certificate is offered to Android.
 */
public class UpdateActivity extends AppCompatActivity {
  private static final String RELEASE_API =
      "https://api.github.com/repos/StrokeDot/DisplayBridge/releases/latest";
  private static final String RELEASE_ASSET = "DisplayBridge.apk";
  private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
  private static final Pattern VERSION =
      Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(.*)$");
  private static final Pattern DIGEST =
      Pattern.compile("^sha256:([0-9a-fA-F]{64})$");
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  private TextView statusText;
  private TextView installedText;
  private ProgressBar progressBar;
  private MaterialButton checkButton;
  private MaterialButton updateButton;
  private ReleaseInfo availableRelease;
  private File downloadedApk;
  private boolean busy;

  private static final class ReleaseInfo {
    final String version;
    final String downloadUrl;
    final String sha256;
    ReleaseInfo(String version, String downloadUrl, String sha256) {
      this.version = version;
      this.downloadUrl = downloadUrl;
      this.sha256 = sha256;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_update);
    findViewById(R.id.update_back_button).setOnClickListener(v -> finish());
    statusText = findViewById(R.id.update_status);
    installedText = findViewById(R.id.update_installed_version);
    progressBar = findViewById(R.id.update_progress);
    checkButton = findViewById(R.id.update_check_button);
    updateButton = findViewById(R.id.update_install_button);
    installedText.setText(getString(R.string.update_installed_version, BuildConfig.VERSION_NAME));
    checkButton.setOnClickListener(v -> checkForUpdates());
    updateButton.setOnClickListener(v -> {
      if (downloadedApk != null && downloadedApk.isFile()) {
        installDownloadedApk();
      } else if (availableRelease != null) {
        downloadUpdate(availableRelease);
      }
    });
    checkForUpdates();
  }

  private void setBusy(boolean value) {
    busy = value;
    progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
    checkButton.setEnabled(!value);
    updateButton.setEnabled(!value && availableRelease != null);
  }

  private void checkForUpdates() {
    if (busy) return;
    availableRelease = null;
    downloadedApk = null;
    statusText.setText(R.string.update_checking);
    setBusy(true);
    worker.execute(() -> {
      try {
        ReleaseInfo release = fetchLatestRelease();
        boolean newer = isNewer(release.version, BuildConfig.VERSION_NAME);
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          availableRelease = newer ? release : null;
          setBusy(false);
          if (newer) {
            statusText.setText(getString(R.string.update_available, release.version));
            updateButton.setText(R.string.update_download_install);
          } else {
            statusText.setText(R.string.update_up_to_date);
          }
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          setBusy(false);
          statusText.setText(getString(R.string.update_check_failed, readableError(error)));
        });
      }
    });
  }

  private static ReleaseInfo fetchLatestRelease() throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = openHttps(RELEASE_API);
      connection.setRequestProperty("Accept", "application/vnd.github+json");
      connection.setRequestProperty("User-Agent", "DisplayBridge-Updater");
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new Exception("GitHub returned HTTP " + connection.getResponseCode());
      }
      JSONObject release = new JSONObject(readResponse(connection.getInputStream(), 1024 * 1024));
      String version = release.getString("tag_name");
      JSONArray assets = release.getJSONArray("assets");
      for (int i = 0; i < assets.length(); ++i) {
        JSONObject asset = assets.getJSONObject(i);
        if (!RELEASE_ASSET.equals(asset.optString("name"))) continue;
        String sha = asset.optString("digest", "");
        Matcher match = DIGEST.matcher(sha);
        if (!match.matches()) {
          throw new Exception("The published APK has no valid SHA-256 digest");
        }
        String url = asset.getString("browser_download_url");
        if (!url.startsWith(
            "https://github.com/StrokeDot/DisplayBridge/releases/download/")) {
          throw new Exception("Unexpected download URL");
        }
        return new ReleaseInfo(version, url, match.group(1));
      }
      throw new Exception("No DisplayBridge.apk in the latest release");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private void downloadUpdate(ReleaseInfo release) {
    if (busy) return;
    downloadedApk = null;
    statusText.setText(R.string.update_downloading);
    setBusy(true);
    worker.execute(() -> {
      File partial = new File(getCacheDir(), "DisplayBridge-update.apk.part");
      File target = new File(getCacheDir(), "DisplayBridge-update.apk");
      try {
        if (partial.exists() && !partial.delete()) throw new Exception("Cannot clear old download");
        HttpURLConnection connection = null;
        String sha;
        try {
          connection = openHttps(release.downloadUrl);
          if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Download returned HTTP " + connection.getResponseCode());
          }
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          long total = 0;
          byte[] buffer = new byte[16 * 1024];
          try (InputStream in = connection.getInputStream();
              FileOutputStream out = new FileOutputStream(partial)) {
            int size;
            while ((size = in.read(buffer)) != -1) {
              total += size;
              if (total > MAX_APK_BYTES) throw new Exception("APK exceeds size limit");
              digest.update(buffer, 0, size);
              out.write(buffer, 0, size);
            }
          }
          sha = toHex(digest.digest());
        } finally {
          if (connection != null) connection.disconnect();
        }
        if (!sha.equalsIgnoreCase(release.sha256)) {
          throw new Exception("APK checksum does not match GitHub's release");
        }
        verifyApkPackageAndSigner(partial);
        if (target.exists() && !target.delete()) throw new Exception("Cannot replace old download");
        if (!partial.renameTo(target)) throw new Exception("Cannot save verified APK");
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          downloadedApk = target;
          setBusy(false);
          statusText.setText(R.string.update_ready);
          updateButton.setText(R.string.update_install);
        });
      } catch (Exception error) {
        partial.delete();
        runOnUiThread(() -> {
          if (isFinishing() || isDestroyed()) return;
          setBusy(false);
          statusText.setText(getString(R.string.update_download_failed, readableError(error)));
        });
      }
    });
  }

  private void verifyApkPackageAndSigner(File apk) throws Exception {
    PackageManager pm = getPackageManager();
    int flag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ? PackageManager.GET_SIGNING_CERTIFICATES
        : PackageManager.GET_SIGNATURES;
    PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flag);
    PackageInfo installed = pm.getPackageInfo(getPackageName(), flag);
    if (archive == null || !getPackageName().equals(archive.packageName)) {
      throw new Exception("Downloaded APK does not belong to DisplayBridge");
    }
    long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ? archive.getLongVersionCode() : archive.versionCode;
    long installedVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ? installed.getLongVersionCode() : installed.versionCode;
    if (archiveVersion <= installedVersion) {
      throw new Exception("Downloaded APK is not a newer Android build");
    }
    Signature[] archiveSigners = signaturesOf(archive);
    Signature[] installedSigners = signaturesOf(installed);
    if (archiveSigners == null || installedSigners == null
        || archiveSigners.length == 0 || !Arrays.equals(archiveSigners, installedSigners)) {
      throw new Exception("APK signing key differs from the installed app. "
          + "A debug build cannot be upgraded to a differently signed release.");
    }
  }

  private static Signature[] signaturesOf(PackageInfo info) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }
    return info.signatures;
  }

  private void installDownloadedApk() {
    if (downloadedApk == null || !downloadedApk.isFile()) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        && !getPackageManager().canRequestPackageInstalls()) {
      statusText.setText(R.string.update_allow_install);
      Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:" + getPackageName()));
      try {
        startActivity(settingsIntent);
      } catch (ActivityNotFoundException ignored) {
        statusText.setText(R.string.update_permission_unavailable);
      }
      return;
    }

    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", downloadedApk);
    Intent installer = new Intent(Intent.ACTION_VIEW);
    installer.setDataAndType(uri, "application/vnd.android.package-archive");
    installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      startActivity(installer);
      statusText.setText(R.string.update_install_prompt);
    } catch (ActivityNotFoundException error) {
      statusText.setText(R.string.update_permission_unavailable);
    }
  }

  private static HttpURLConnection openHttps(String address) throws Exception {
    URL url = new URL(address);
    if (!"https".equalsIgnoreCase(url.getProtocol())) {
      throw new Exception("HTTPS required");
    }
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(30000);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "DisplayBridge-Updater");
    return connection;
  }

  private static String readResponse(InputStream stream, int limit) throws Exception {
    try (InputStream in = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (output.size() + n > limit) throw new Exception("Server response too large");
        output.write(buffer, 0, n);
      }
      return output.toString("UTF-8");
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) builder.append(String.format("%02x", b & 0xff));
    return builder.toString();
  }

  private static String readableError(Exception error) {
    String message = error.getMessage();
    return message == null || message.trim().isEmpty() ? "Please try again" : message;
  }

  private static boolean isNewer(String remote, String local) throws Exception {
    Matcher remoteMatcher = VERSION.matcher(remote);
    Matcher localMatcher = VERSION.matcher(local);
    if (!remoteMatcher.matches() || !localMatcher.matches()) {
      throw new Exception("Unsupported release version");
    }
    for (int i = 1; i <= 3; i++) {
      int result = Integer.compare(Integer.parseInt(remoteMatcher.group(i)),
          Integer.parseInt(localMatcher.group(i)));
      if (result != 0) return result > 0;
    }
    // A released 0.1.0 is newer than a 0.1.0-dev test build.
    return !remoteMatcher.group(4).startsWith("-")
        && localMatcher.group(4).startsWith("-");
  }

  @Override
  protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
