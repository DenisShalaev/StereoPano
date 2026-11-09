package com.example.stereopano;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimaler Google-Fotos-Zugriff (Photos Library API, REST). Muss in einem Hintergrund-Thread laufen.
 * Token ueber den AccountManager (Konto aus Einstellungen). Voraussetzung: Cloud-Projekt mit aktivierter
 * "Photos Library API" und einem OAuth-Client vom Typ Android (Paketname + SHA-1 der Signatur).
 * Seit 2025 liefert die API nur Dateien, die diese App selbst hochgeladen hat.
 */
final class GooglePhotos {
    static final String SCOPE = "oauth2:https://www.googleapis.com/auth/photoslibrary.appendonly "
            + "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata "
            + "https://www.googleapis.com/auth/photoslibrary.sharing";
    private static final String BASE = "https://photoslibrary.googleapis.com/v1/";

    static final class Item {
        String id, name, baseUrl, time, productUrl;
    }

    private GooglePhotos() {}

    static boolean hatKonto(Context c) {
        return !Settings.p(c).getString(Settings.K_GACC, "").isEmpty();
    }

    /** Token holen; mit Activity erscheint ggf. der Zustimmungsdialog (Aufruf nicht im UI-Thread). */
    static String token(Activity a, boolean erneuern, String alt) throws Exception {
        String name = Settings.p(a).getString(Settings.K_GACC, "");
        if (name.isEmpty()) throw new Exception(a.getString(R.string.gp_no_account));
        AccountManager am = AccountManager.get(a);
        if (erneuern && alt != null) am.invalidateAuthToken("com.google", alt);
        Account konto = new Account(name, "com.google");
        Bundle r = am.getAuthToken(konto, SCOPE, null, a, null, null).getResult();
        String t = r.getString(AccountManager.KEY_AUTHTOKEN);
        if (t == null) throw new Exception(a.getString(R.string.gp_no_token));
        return t;
    }

    private static byte[] lese(HttpURLConnection h) throws Exception {
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
        }
        if (code >= 400) throw new Exception("HTTP " + code + " " + new String(bo.toByteArray(), StandardCharsets.UTF_8));
        return bo.toByteArray();
    }

    /** Laedt die Datei hoch und legt das Medienobjekt an; liefert die ID. */
    static Item hochladen(Activity a, byte[] daten, String name, String beschreibung) throws Exception {
        String tok = token(a, false, null);
        for (int versuch = 0; ; versuch++) {
            try {
                return hochladen1(tok, daten, name, beschreibung);
            } catch (Exception e) {
                if (versuch == 0 && String.valueOf(e.getMessage()).startsWith("HTTP 401")) { tok = token(a, true, tok); continue; }
                throw e;
            }
        }
    }

    private static Item hochladen1(String tok, byte[] daten, String name, String beschreibung) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(BASE + "uploads").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(120000);
        h.setFixedLengthStreamingMode(daten.length);
        h.setRequestProperty("Authorization", "Bearer " + tok);
        h.setRequestProperty("Content-type", "application/octet-stream");
        h.setRequestProperty("X-Goog-Upload-Content-Type", "image/jpeg");
        h.setRequestProperty("X-Goog-Upload-Protocol", "raw");
        h.setRequestProperty("X-Goog-Upload-File-Name", name);
        try (OutputStream os = h.getOutputStream()) { os.write(daten); }
        String upTok = new String(lese(h), StandardCharsets.UTF_8).trim();

        JSONObject item = new JSONObject();
        if (beschreibung != null && !beschreibung.isEmpty()) item.put("description", beschreibung);
        item.put("simpleMediaItem", new JSONObject().put("fileName", name).put("uploadToken", upTok));
        JSONObject body = new JSONObject().put("newMediaItems", new JSONArray().put(item));
        byte[] bb = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h2 = (HttpURLConnection) new URL(BASE + "mediaItems:batchCreate").openConnection();
        h2.setRequestMethod("POST");
        h2.setDoOutput(true);
        h2.setConnectTimeout(20000);
        h2.setReadTimeout(60000);
        h2.setRequestProperty("Authorization", "Bearer " + tok);
        h2.setRequestProperty("Content-type", "application/json; charset=utf-8");
        try (OutputStream os = h2.getOutputStream()) { os.write(bb); }
        JSONObject res = new JSONObject(new String(lese(h2), StandardCharsets.UTF_8));
        JSONObject r0 = res.getJSONArray("newMediaItemResults").getJSONObject(0);
        JSONObject st = r0.optJSONObject("status");
        if (st != null && st.optInt("code", 0) != 0) throw new Exception(st.optString("message"));
        JSONObject mi = r0.getJSONObject("mediaItem");
        Item it = new Item();
        it.id = mi.optString("id");
        it.name = mi.optString("filename", name);
        it.baseUrl = mi.optString("baseUrl");
        it.productUrl = mi.optString("productUrl");
        return it;
    }

    private static JSONObject post(String tok, String url, JSONObject body) throws Exception {
        byte[] bb = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + tok);
        h.setRequestProperty("Content-type", "application/json; charset=utf-8");
        try (OutputStream os = h.getOutputStream()) { os.write(bb); }
        return new JSONObject(new String(lese(h), StandardCharsets.UTF_8));
    }

    /** Legt ein Album mit dem Objekt an und gibt es per Link frei (nur ansehen); liefert die Freigabe-URL. */
    static String linkTeilen(Activity a, Item it) throws Exception {
        String tok = token(a, false, null);
        String titel = "StereoPano " + it.name;
        String albumId = post(tok, BASE + "albums", new JSONObject().put("album", new JSONObject().put("title", titel))).getString("id");
        post(tok, BASE + "albums/" + albumId + ":batchAddMediaItems",
                new JSONObject().put("mediaItemIds", new JSONArray().put(it.id)));
        JSONObject r = post(tok, BASE + "albums/" + albumId + ":share", new JSONObject().put("sharedAlbumOptions",
                new JSONObject().put("isCollaborative", false).put("isCommentable", false)));
        String url = r.getJSONObject("shareInfo").optString("shareableUrl");
        if (url.isEmpty()) throw new Exception("no shareableUrl");
        return url;
    }

    /** Laedt das Original zurueck und prueft, ob rechtes Auge und Ton noch enthalten sind (und die Bytes identisch). */
    static String pruefen(Activity a, Item it, byte[] gesendet) throws Exception {
        byte[] g = laden(it);
        VrJpgReader.Result r = VrJpgReader.parse(g);
        boolean gleich = java.util.Arrays.equals(g, gesendet);
        return a.getString(R.string.gp_check_result, r.rightJpeg != null ? "\u2713" : "\u2717", r.audio != null ? "\u2713" : "\u2717",
                gleich ? "\u2713" : "\u2717", g.length / 1024);
    }

    /** Alle von dieser App hochgeladenen Objekte (neueste zuerst, wie von der API geliefert). */
    static List<Item> auflisten(Activity a) throws Exception {
        String tok = token(a, false, null);
        List<Item> out = new ArrayList<>();
        String seite = null;
        boolean neu = false;
        do {
            String u = BASE + "mediaItems?pageSize=100" + (seite != null ? "&pageToken=" + seite : "");
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(20000);
            h.setReadTimeout(60000);
            h.setRequestProperty("Authorization", "Bearer " + tok);
            byte[] raw;
            try {
                raw = lese(h);
            } catch (Exception e) {
                if (!neu && String.valueOf(e.getMessage()).startsWith("HTTP 401")) { neu = true; tok = token(a, true, tok); continue; }
                throw e;
            }
            JSONObject j = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray arr = j.optJSONArray("mediaItems");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.name = o.optString("filename", it.id);
                it.baseUrl = o.optString("baseUrl");
                JSONObject md = o.optJSONObject("mediaMetadata");
                it.time = md != null ? md.optString("creationTime") : "";
                out.add(it);
            }
            seite = j.optString("nextPageToken", "");
            if (seite.isEmpty()) seite = null;
        } while (seite != null && out.size() < 1000);
        return out;
    }

    /** Originaldatei (=d liefert die Bytes inklusive Metadaten). */
    static byte[] laden(Item it) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(it.baseUrl + "=d").openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(120000);
        return lese(h);
    }
}
