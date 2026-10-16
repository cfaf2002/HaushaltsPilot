package de.frohwerk.haushaltspilot;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HaushaltsPilot Handy-App: zeigt die eingebaute Web-App (assets/index.html) an.
 * Keine Verbindung ins Internet: alle Dateien kommen aus der App selbst,
 * Daten bleiben im Speicher der App bzw. im gewählten Austauschordner.
 * Belege werden auf dem Handy gelesen (ML Kit, ohne Internet); das Foto wird danach gelöscht.
 */
public class MainActivity extends Activity {

    private static final String START = "https://appassets.androidplatform.net/assets/index.html";
    private static final int DATEI_WAHL = 1;
    private static final int BELEG_KAMERA = 2;
    private static final int BELEG_GALERIE = 3;

    private WebView web;
    private ValueCallback<Uri[]> dateiRueckruf;
    private Uri fotoUri;

    @Override
    protected void onCreate(Bundle zustand) {
        super.onCreate(zustand);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader lader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse antwort = lader.shouldInterceptRequest(r.getUrl());
                // alles, was nicht aus der App kommt, wird blockiert
                return antwort != null ? antwort : new WebResourceResponse("text/plain", "utf-8", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !"appassets.androidplatform.net".equals(r.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rueckruf, FileChooserParams p) {
                if (dateiRueckruf != null) dateiRueckruf.onReceiveValue(null);
                dateiRueckruf = rueckruf;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "HaushaltsPilot_Handy.json wählen"), DATEI_WAHL);
                } catch (Exception e) {
                    dateiRueckruf = null;
                    rueckruf.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bruecke(), "HPAndroid");

        if (zustand == null) web.loadUrl(START);
        else web.restoreState(zustand);
    }

    @Override
    protected void onSaveInstanceState(Bundle aus) {
        super.onSaveInstanceState(aus);
        web.saveState(aus);
    }

    @Override
    protected void onActivityResult(int anfrage, int ergebnis, Intent daten) {
        if (anfrage == BELEG_KAMERA || anfrage == BELEG_GALERIE) {
            Uri quelle = null;
            if (ergebnis == RESULT_OK) {
                if (anfrage == BELEG_GALERIE) quelle = daten != null ? daten.getData() : null;
                else quelle = fotoUri != null ? fotoUri : FileProvider.getUriForFile(this, getPackageName() + ".dateien", fotoDatei());
            }
            if (quelle == null) belegAntwort(abbruch());
            else erkennen(quelle);
            return;
        }
        if (anfrage == DATEI_WAHL && dateiRueckruf != null) {
            Uri[] r = null;
            if (ergebnis == RESULT_OK && daten != null && daten.getData() != null) r = new Uri[]{daten.getData()};
            dateiRueckruf.onReceiveValue(r);
            dateiRueckruf = null;
            return;
        }
        super.onActivityResult(anfrage, ergebnis, daten);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------- Beleg fotografieren und Text erkennen ----------

    private File fotoDatei() {
        File ordner = new File(getCacheDir(), "fotos");
        //noinspection ResultOfMethodCallIgnored
        ordner.mkdirs();
        return new File(ordner, "beleg.jpg");
    }

    private void belegStarten(boolean galerie) {
        try {
            if (galerie) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Foto des Belegs wählen"), BELEG_GALERIE);
            } else {
                File f = fotoDatei();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                fotoUri = FileProvider.getUriForFile(this, getPackageName() + ".dateien", f);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                i.setClipData(ClipData.newRawUri("", fotoUri));
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, BELEG_KAMERA);
            }
        } catch (Exception e) {
            belegAntwort(fehler("Kamera bzw. Galerie ist nicht verfügbar."));
        }
    }

    private void erkennen(Uri uri) {
        final TextRecognizer leser;
        final InputImage bild;
        try {
            bild = InputImage.fromFilePath(this, uri);
            leser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Throwable e) {
            fotoLoeschen();
            belegAntwort(fehler("Das Foto konnte nicht geöffnet werden."));
            return;
        }
        leser.process(bild)
                .addOnSuccessListener(text -> {
                    String antwort;
                    try {
                        JSONArray zeilen = new JSONArray();
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            for (Text.Line zeile : block.getLines()) {
                                JSONObject o = new JSONObject();
                                o.put("t", zeile.getText());
                                Rect r = zeile.getBoundingBox();
                                if (r != null) {
                                    o.put("x", r.left); o.put("y", r.top); o.put("w", r.width()); o.put("h", r.height());
                                } else {
                                    o.put("x", 0); o.put("y", zeilen.length() * 40); o.put("w", 0); o.put("h", 30);
                                }
                                zeilen.put(o);
                            }
                        }
                        JSONObject a = new JSONObject();
                        a.put("ok", true);
                        a.put("zeilen", zeilen);
                        antwort = a.toString();
                    } catch (Exception e) {
                        antwort = fehler("Der Text konnte nicht ausgewertet werden.");
                    }
                    fotoLoeschen();
                    leser.close();
                    belegAntwort(antwort);
                })
                .addOnFailureListener(e -> {
                    fotoLoeschen();
                    leser.close();
                    belegAntwort(fehler("Auf dem Foto wurde kein Text erkannt."));
                });
    }

    private void fotoLoeschen() {
        //noinspection ResultOfMethodCallIgnored
        fotoDatei().delete();
        fotoUri = null;
    }

    private void belegAntwort(String json) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.belegErgebnis && window.belegErgebnis(" + JSONObject.quote(json) + ")", null));
    }

    private static String fehler(String t) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("fehler", t);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static String abbruch() {
        return "{\"ok\":false,\"abbruch\":true}";
    }

    /** Wird aus der Web-App aufgerufen: Buchungsdatei über das Teilen-Menü weitergeben. */
    private class Bruecke {
        @JavascriptInterface
        public void belegScannen(String quelle) {
            final boolean galerie = "galerie".equals(quelle);
            runOnUiThread(() -> belegStarten(galerie));
        }

        @JavascriptInterface
        public boolean teilen(String name, String inhalt) {
            try {
                if (name == null || inhalt == null) return false;
                if (!name.matches("HP_Handy_[0-9]{8}-[0-9]{6}\\.json") || inhalt.length() > 5_000_000) return false;
                File ordner = new File(getCacheDir(), "senden");
                if (!ordner.isDirectory() && !ordner.mkdirs()) return false;
                File[] alt = ordner.listFiles();
                if (alt != null) for (File f : alt) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File datei = new File(ordner, name);
                try (OutputStream o = new FileOutputStream(datei)) {
                    o.write(inhalt.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".dateien", datei);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_SUBJECT, name);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final Intent wahl = Intent.createChooser(i, "Im Austauschordner speichern");
                runOnUiThread(() -> startActivity(wahl));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
