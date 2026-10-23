package dev.zehan.phonebridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 极简 HTTP：只够跟 phone-bridge 的 JSON 接口说话。 */
public class Http {

    public static String request(String method, String urlStr, String token, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code;
        try {
            code = c.getResponseCode();
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = readAll(in);
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + " " + trim(text));
        return text;
    }

    public static String get(String url, String token) throws IOException {
        return request("GET", url, token, null, 15000);
    }

    public static String post(String url, String token, String json) throws IOException {
        return request("POST", url, token, json == null ? "{}" : json, 15000);
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    static String trim(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
