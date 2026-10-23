package dev.zehan.phonebridge;

/** 一台被遥控的电脑：一个 Tailscale 域名 + 一个配对 token。 */
public class Host {
    public String id;
    public String name;
    public String url;
    public String token;
    public long addedAt;

    public Host() {}

    public Host(String id, String name, String url, String token, long addedAt) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.token = token;
        this.addedAt = addedAt;
    }

    /** 去掉尾部斜杠、补上 https:// —— 手输最容易错的两处。 */
    public static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public String api(String path) {
        return url + (path.startsWith("/") ? path : "/" + path);
    }
}
