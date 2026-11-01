package io.github.sjcud.stockagent.deployment;

import java.net.HttpURLConnection;
import java.net.URL;

/** 容器内轻量HTTP探针，仅依赖JRE，避免额外安装curl或暴露配置。 */
public final class HealthProbe {
    /** 禁止实例化命令行入口。 */
    private HealthProbe() {}

    /** 就绪返回200才退出0；有界连接和读取，不回显URL或响应正文。 */
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            if (args.length != 1) throw new IllegalArgumentException("需要一个健康地址");
            connection = (HttpURLConnection) new URL(args[0]).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) {
                System.err.println("Readiness is DOWN");
                System.exit(1);
            }
            System.out.println("Readiness is UP");
        } catch (Exception ex) {
            System.err.println("Readiness unavailable");
            System.exit(1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
