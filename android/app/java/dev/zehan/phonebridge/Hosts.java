package dev.zehan.phonebridge;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 主机列表存在 filesDir/hosts.json，一个软件管多台电脑。 */
public class Hosts {
    private static final String FILE = "hosts.json";

    public static synchronized List<Host> load(Context c) {
        List<Host> out = new ArrayList<Host>();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return out;
        try {
            String raw = new String(readAll(f), "UTF-8");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Host h = new Host(
                        o.optString("id", ""),
                        o.optString("name", ""),
                        o.optString("url", ""),
                        o.optString("token", ""),
                        o.optLong("addedAt", 0));
                if (!h.id.isEmpty() && !h.url.isEmpty()) out.add(h);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void save(Context c, List<Host> list) {
        JSONArray arr = new JSONArray();
        for (Host h : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", h.id);
                o.put("name", h.name);
                o.put("url", h.url);
                o.put("token", h.token);
                o.put("addedAt", h.addedAt);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        try {
            FileOutputStream os = new FileOutputStream(new File(c.getFilesDir(), FILE));
            os.write(arr.toString().getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void upsert(Context c, Host h) {
        List<Host> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(h.id)) {
                list.set(i, h);
                save(c, list);
                return;
            }
        }
        list.add(h);
        save(c, list);
    }

    public static synchronized void remove(Context c, String id) {
        List<Host> list = load(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        save(c, list);
    }

    public static Host byId(Context c, String id) {
        if (id == null) return null;
        for (Host h : load(c)) if (id.equals(h.id)) return h;
        return null;
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
